package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 租户初始化服务（PRJ-002 T18）：新租户注册后复制租户级治理 seed——
 * 六条架构原则 + 三条门禁规则（内容与 V4 r3 迁移 seed 逐字一致，dogfooding 原则对新租户生效）。
 *
 * <p><b>为什么需要本服务（R8）</b>：V4 迁移 seed 只覆盖执行迁移时的存量租户，之后新建的租户
 * 若不主动灌入则是"零原则/零门禁"裸租户。杜绝 tenant_id=0 平台全局行方案——该行会被租户拦截器
 * 过滤掉，等于没灌。</p>
 *
 * <p><b>幂等</b>：按 code（原则）/name（门禁）查重后插入，重复调用只补缺不重复
 * （uk_principle_code / uk_gate_tenant_name 双保险）。</p>
 *
 * <p><b>调用方约定</b>：TenantController.register 成功后调用，失败仅 warn 不阻塞注册
 * （注册是收费/体验主路径，seed 是治理增强）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisionService {

    /** seed 造数据者标识（与 V4 迁移 seed 的 create_by='system' 一致，便于回滚识别） */
    private static final String SEED_BY = "system";

    private final PrincipleService principleService;
    private final QualityGateRuleService qualityGateRuleService;

    /** 原则 seed（六条，内容与 V4 seed 逐字一致：P3/P6/P7/P8/P11/P13，全部 must 级启用） */
    private record PrincipleSeed(String code, String title, String content, String type) {
        static final List<PrincipleSeed> ALL = List.of(
                new PrincipleSeed("P3", "依赖方向单向无环",
                        "模块依赖 runtime → capability/adapter/data → common 单向无环，禁止反向引用；"
                                + "分层架构 L3→L2→L1 单向引用，L1 编排不得反向依赖 L3 存在。",
                        "tech"),
                new PrincipleSeed("P6", "平台零硬编码",
                        "平台代码不得硬编码角色名、门禁角色集合、流程阶段与流水线注入内容，"
                                + "必须由租户可配置数据（如 t_quality_gate_rule）驱动，体系变更不改平台代码。",
                        "governance"),
                new PrincipleSeed("P7", "唯一调度入口",
                        "所有角色派生必经统一编排入口（OrchestrationService → DerivationEngine → AdapterFactory），"
                                + "不得绕过直调 LLM/Git/DocStore，保证埋点、持久化、配额与检查点治理一致。",
                        "tech"),
                new PrincipleSeed("P8", "模型档位与具体模型解耦",
                        "角色定义只写能力档位（reasoning/structured/mechanical），不得写具体模型名；"
                                + "档位→模型映射走 t_model_routing 配置表，模型换代只改路由表不改代码。",
                        "tech"),
                new PrincipleSeed("P11", "多租户隔离贯穿",
                        "所有业务表带 tenant_id，所有查询经租户拦截器自动注入过滤；"
                                + "架构原则、门禁规则与注入行为按租户生效，禁止任何跨租户数据访问。",
                        "security"),
                new PrincipleSeed("P13", "灵活接入",
                        "企业客户可从 L3/L2/L1 任一层接入，平台不强制全层使用；每层可独立启用，"
                                + "任一层关闭或数据为空时下层功能必须完整可用，上层存在不得成为下层运行的前置条件。",
                        "governance"));
    }

    /** 门禁规则 seed（三条，与 V4 r3 seed 一致：a/b 等价既有 GATE_ROLES，c 承接 team-ops 检查点） */
    private record GateSeed(String name, String gateType, String gateRole, String stage,
                            Integer maxRetries, String failAction, Integer priority) {
        static final List<GateSeed> ALL = List.of(
                // a) 等价 GATE_ROLES 中 team-reviewer
                new GateSeed("开发评审门禁（team-reviewer）", "llm_review", "team-reviewer",
                        "post_dev", 2, "block", 100),
                // b) 等价 GATE_ROLES 中 team-qa
                new GateSeed("测试评审门禁（team-qa）", "llm_review", "team-qa",
                        "post_test", 2, "block", 110),
                // c) 等价 team-ops 部署前人工审批检查点（V4 r3 增补，AC-F6.5 升级等价）
                new GateSeed("部署人工审批", "human_approval", null,
                        "pre_deploy", 1, "block", 120));
    }

    /**
     * 为新租户灌入治理 seed（幂等：按 code/name 查重，只补缺不重复）。
     *
     * <p>租户表两表均走租户拦截器，插入前切换 TenantContext 到目标租户（雪花 ID 由
     * ASSIGN_ID 自动生成，与 V4 seed 的 7000000+/8000000+ 保留区间不冲突），finally 恢复。</p>
     *
     * @param tenantId 新租户 ID
     */
    public void provision(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        Long prev = TenantContext.get();
        boolean switched = !tenantId.equals(prev);
        if (switched) {
            TenantContext.set(tenantId);
        }
        try {
            int principles = seedPrinciples(tenantId);
            int gates = seedGateRules(tenantId);
            log.info("[Provision] 新租户治理 seed 完成 tenantId={}, 原则 {} 条, 门禁规则 {} 条",
                    tenantId, principles, gates);
        } finally {
            if (switched) {
                TenantContext.set(prev);
            }
        }
    }

    /** 灌六原则（code 查重幂等）。 */
    private int seedPrinciples(Long tenantId) {
        int inserted = 0;
        for (PrincipleSeed seed : PrincipleSeed.ALL) {
            boolean exists = principleService.count(new LambdaQueryWrapper<ArchitecturePrinciple>()
                    .eq(ArchitecturePrinciple::getCode, seed.code())) > 0;
            if (exists) {
                continue;
            }
            ArchitecturePrinciple p = new ArchitecturePrinciple();
            p.setCode(seed.code());
            p.setTitle(seed.title());
            p.setContent(seed.content());
            p.setPrincipleType(seed.type());
            p.setEnforceLevel("must");
            p.setEnabled(1);
            p.setCreateBy(SEED_BY);
            principleService.save(p);
            inserted++;
        }
        if (inserted < PrincipleSeed.ALL.size()) {
            log.info("[Provision] 原则 seed 部分跳过（已存在）tenantId={}, 新增 {}/{}",
                    tenantId, inserted, PrincipleSeed.ALL.size());
        }
        return inserted;
    }

    /** 灌三门禁规则（name 查重幂等）。 */
    private int seedGateRules(Long tenantId) {
        int inserted = 0;
        for (GateSeed seed : GateSeed.ALL) {
            boolean exists = qualityGateRuleService.count(new LambdaQueryWrapper<QualityGateRule>()
                    .eq(QualityGateRule::getName, seed.name())) > 0;
            if (exists) {
                continue;
            }
            QualityGateRule r = new QualityGateRule();
            r.setName(seed.name());
            r.setGateType(seed.gateType());
            r.setGateRole(seed.gateRole());
            r.setAppliesTo("all");
            r.setStage(seed.stage());
            r.setMaxRetries(seed.maxRetries());
            r.setFailAction(seed.failAction());
            r.setEnabled(1);
            r.setPriority(seed.priority());
            r.setCreateBy(SEED_BY);
            qualityGateRuleService.save(r);
            inserted++;
        }
        if (inserted < GateSeed.ALL.size()) {
            log.info("[Provision] 门禁 seed 部分跳过（已存在）tenantId={}, 新增 {}/{}",
                    tenantId, inserted, GateSeed.ALL.size());
        }
        return inserted;
    }
}
