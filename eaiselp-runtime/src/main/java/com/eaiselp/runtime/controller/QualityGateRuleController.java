package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.hierarchy.QualityGateRule;
import com.eaiselp.runtime.hierarchy.QualityGateRuleService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 质量门禁规则管理 REST API（L1 治理配置，PRJ-002 T27，路径前缀 /api/v1/gate-rules）。
 *
 * <p>契约对齐 SE §8.1：CRUD + 启停。规则数据驱动替换 OrchestrationService 硬编码
 * GATE_ROLES/team-ops 检查点（P6 零硬编码，AC-F6.3）；改参数/启停对<b>新编排</b>即时生效
 * （无运行期缓存，D-3：每次编排启动一条 SELECT 加载快照）、在跑编排走快照不受影响
 * （AC-F6.2/6.4）。</p>
 *
 * <p>门禁属 L1 治理配置：不判分层开关（SE §7.3），LayerGuardInterceptor 不拦本前缀。</p>
 *
 * <p>权限（V4 r3 seed 1041~1043）：读 {@code gate:view}、建 {@code gate:create}、
 * 改/删/启停 {@code gate:edit}（project_manager 只读）。写操作全审计
 * （gate_create/update/enabled/delete）。</p>
 */
@RestController
@RequestMapping("/api/v1/gate-rules")
@RequiredArgsConstructor
public class QualityGateRuleController {

    /** 门禁类型合法集（三类型分派见 SE §6.4） */
    private static final Set<String> GATE_TYPES = Set.of("auto_check", "llm_review", "human_approval");

    /** 挂载阶段合法集（stage 边界探测见 SE §6.3；边界不存在时该规则跳过 + WARN，R10） */
    private static final Set<String> STAGES = Set.of("post_dev", "post_test", "pre_deploy");

    /** 失败动作合法集 */
    private static final Set<String> FAIL_ACTIONS = Set.of("block", "warn");

    /** 生效范围合法集 */
    private static final Set<String> APPLIES_TO = Set.of("all", "code", "design", "doc");

    private final QualityGateRuleService gateRuleService;
    private final AuditService auditService;

    /** 全量列表（priority 升序；可选 enabled 过滤——管理页需同时展示启用/停用规则）。 */
    @GetMapping
    @RequirePermission("gate:view")
    public R<List<QualityGateRule>> list(@RequestParam(required = false) Integer enabled) {
        LambdaQueryWrapper<QualityGateRule> wrapper = new LambdaQueryWrapper<QualityGateRule>()
                .orderByAsc(QualityGateRule::getPriority)
                .orderByAsc(QualityGateRule::getId);
        if (enabled != null) {
            wrapper.eq(QualityGateRule::getEnabled, enabled);
        }
        return R.ok(gateRuleService.list(wrapper));
    }

    /** 创建规则（AC-F6.1：enabled=1 对新编排立即生效）。 */
    @PostMapping
    @RequirePermission("gate:create")
    public R<QualityGateRule> create(@RequestBody GateRuleSaveRequest req) {
        if (req.getName() == null || req.getName().trim().isEmpty()) {
            return R.fail(400, "name 不能为空");
        }
        String err = validate(req);
        if (err != null) return R.fail(400, err);
        gateRuleService.checkNameAvailable(req.getName().trim(), null);
        QualityGateRule r = new QualityGateRule();
        applyRequest(r, req);
        applyDefaults(r);
        gateRuleService.save(r);
        auditService.log("gate_create", "gate_rule", String.valueOf(r.getId()),
                "{\"name\":\"" + safeJson(r.getName()) + "\",\"gateType\":\"" + r.getGateType()
                        + "\",\"stage\":\"" + r.getStage() + "\"}");
        return R.ok(r);
    }

    /** 规则详情。 */
    @GetMapping("/{id}")
    @RequirePermission("gate:view")
    public R<QualityGateRule> get(@PathVariable Long id) {
        QualityGateRule r = gateRuleService.getById(id);
        if (r == null) return R.fail(404, "门禁规则不存在: " + id);
        return R.ok(r);
    }

    /** 编辑规则（AC-F6.4：改 max_retries/fail_action 等参数对新编排即时生效）。 */
    @PutMapping("/{id}")
    @RequirePermission("gate:edit")
    public R<QualityGateRule> update(@PathVariable Long id, @RequestBody GateRuleSaveRequest req) {
        QualityGateRule existing = gateRuleService.getById(id);
        if (existing == null) return R.fail(404, "门禁规则不存在: " + id);
        String err = validate(req);
        if (err != null) return R.fail(400, err);
        if (req.getName() != null && !req.getName().trim().equals(existing.getName())) {
            gateRuleService.checkNameAvailable(req.getName().trim(), id);
        }
        applyRequest(existing, req);
        gateRuleService.updateById(existing);
        auditService.log("gate_update", "gate_rule", String.valueOf(id),
                "{\"name\":\"" + safeJson(existing.getName()) + "\",\"maxRetries\":"
                        + (existing.getMaxRetries() == null ? "null" : existing.getMaxRetries())
                        + ",\"failAction\":\"" + existing.getFailAction() + "\"}");
        return R.ok(existing);
    }

    /** 启停规则（AC-F6.2：停用对新编排生效、在跑编排走快照不受影响）。 */
    @PutMapping("/{id}/enabled")
    @RequirePermission("gate:edit")
    public R<QualityGateRule> toggleEnabled(@PathVariable Long id, @RequestBody EnabledRequest req) {
        if (req.getEnabled() == null) return R.fail(400, "enabled 不能为空");
        QualityGateRule existing = gateRuleService.getById(id);
        if (existing == null) return R.fail(404, "门禁规则不存在: " + id);
        existing.setEnabled(req.getEnabled() ? 1 : 0);
        gateRuleService.updateById(existing);
        auditService.log("gate_enabled", "gate_rule", String.valueOf(id),
                "{\"enabled\":" + req.getEnabled() + "}");
        return R.ok(existing);
    }

    /** 逻辑删规则。 */
    @DeleteMapping("/{id}")
    @RequirePermission("gate:edit")
    public R<Void> delete(@PathVariable Long id) {
        gateRuleService.removeById(id);
        auditService.log("gate_delete", "gate_rule", String.valueOf(id));
        return R.ok();
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 门禁规则创建/编辑请求（三类型字段联动：llm_review 显 gateRole / auto_check 显 checkKey / human_approval 无角色） */
    @Data
    public static class GateRuleSaveRequest {
        private String name;
        /** auto_check / llm_review / human_approval */
        private String gateType;
        /** llm_review 必填（如 team-reviewer） */
        private String gateRole;
        /** auto_check 必填（本期仅 code_validation） */
        private String checkKey;
        /** all / code / design / doc */
        private String appliesTo;
        /** post_dev / post_test / pre_deploy */
        private String stage;
        /** null = 走 yml eaiselp.orchestration.gate-max-retries 兜底（F6.4） */
        private Integer maxRetries;
        /** block / warn */
        private String failAction;
        /** 1=启用 0=停用（默认启用） */
        private Boolean enabled;
        /** 同阶段多规则执行顺序（小者先） */
        private Integer priority;
    }

    @Data
    public static class EnabledRequest {
        private Boolean enabled;
    }

    /** 类型/阶段/动作/类型内必填字段校验（新建与编辑共用；null 字段跳过——编辑部分更新场景） */
    private String validate(GateRuleSaveRequest req) {
        if (req.getGateType() != null && !GATE_TYPES.contains(req.getGateType())) {
            return "gateType 非法: " + req.getGateType() + "（应为 auto_check/llm_review/human_approval）";
        }
        if (req.getStage() != null && !STAGES.contains(req.getStage())) {
            return "stage 非法: " + req.getStage() + "（应为 post_dev/post_test/pre_deploy）";
        }
        if (req.getFailAction() != null && !FAIL_ACTIONS.contains(req.getFailAction())) {
            return "failAction 非法: " + req.getFailAction() + "（应为 block/warn）";
        }
        if (req.getAppliesTo() != null && !APPLIES_TO.contains(req.getAppliesTo())) {
            return "appliesTo 非法: " + req.getAppliesTo() + "（应为 all/code/design/doc）";
        }
        if (req.getMaxRetries() != null && req.getMaxRetries() < 0) {
            return "maxRetries 不能为负（0 = 首次 FAIL 即终判）";
        }
        return null;
    }

    private void applyRequest(QualityGateRule r, GateRuleSaveRequest req) {
        if (req.getName() != null && !req.getName().trim().isEmpty()) r.setName(req.getName().trim());
        if (req.getGateType() != null) r.setGateType(req.getGateType());
        if (req.getGateRole() != null) r.setGateRole(req.getGateRole().trim());
        if (req.getCheckKey() != null) r.setCheckKey(req.getCheckKey().trim());
        if (req.getAppliesTo() != null) r.setAppliesTo(req.getAppliesTo());
        if (req.getStage() != null) r.setStage(req.getStage());
        if (req.getMaxRetries() != null) r.setMaxRetries(req.getMaxRetries());
        if (req.getFailAction() != null) r.setFailAction(req.getFailAction());
        if (req.getEnabled() != null) r.setEnabled(req.getEnabled() ? 1 : 0);
        if (req.getPriority() != null) r.setPriority(req.getPriority());
    }

    /** 新建默认值（stage/failAction 前端表单必填，此处兜底防脏数据） */
    private void applyDefaults(QualityGateRule r) {
        if (r.getStage() == null) r.setStage("post_dev");
        if (r.getFailAction() == null) r.setFailAction("block");
        if (r.getAppliesTo() == null) r.setAppliesTo("all");
        if (r.getEnabled() == null) r.setEnabled(1);
        if (r.getPriority() == null) r.setPriority(100);
    }

    /** JSON 字符串转义（审计 detail 防注入） */
    private static String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
