package com.eaiselp.runtime.orchestration;

import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.adapter.spi.LlmAdapter;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.runtime.context.DerivationContext;
import com.eaiselp.runtime.engine.DerivationEngine;
import com.eaiselp.runtime.hierarchy.GovernanceInjectionService;
import com.eaiselp.runtime.hierarchy.QualityGateRuleService;
import com.eaiselp.runtime.workspace.ArtifactFileService;
import com.eaiselp.runtime.workspace.CICDTriggerService;
import com.eaiselp.runtime.workspace.CodeValidationService;
import com.eaiselp.runtime.workspace.GitService;
import com.eaiselp.data.entity.Checkpoint;
import com.eaiselp.data.service.CheckpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编排服务 —— 一句话需求自动按流水线派生所有角色。
 *
 * <p><b>核心价值</b>：从"手动逐个角色派生"升级为"一句话需求 → 自动全流程编排"。
 * 用户只需输入一句话需求，平台自动按 PO→SE→Dev→Reviewer→QA→Ops 流水线串行派生，
 * 每步把前面步骤的产出通过 {@link DerivationContext#getUpstreamArtifacts()} 传给下一步。</p>
 *
 * <p><b>流水线定义</b>：Fast 模式 6 步，覆盖需求→方案→编码→审查→测试→部署核心链路。
 * 步骤间有数据依赖（Dev 需要 PO 的 PRD + SE 的技术方案），故只能串行不能并行。</p>
 *
 * <p><b>上下文传递</b>：复用已有的 {@code DerivationContext.upstreamArtifacts} 管道
 * （{@code ContextAssembler} 已实现"## 上游产出（必读）"渲染），每步产出填入 Map 供下一步使用。</p>
 *
 * <p><b>限流防护</b>：步骤间间隔 {@code step-interval-ms}（默认 3 秒），防 LLM provider 429 限流。
 * 某步失败不中断整条流水线（降级策略：记录失败，继续下一步）。</p>
 *
 * <p><b>线程隔离</b>：编排走独立线程池 {@code orchestrationExecutor}（core=2/max=3），
 * 不占用单角色派生的 {@code runtimeLlmExecutor} 线程池。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationService {

    private final DerivationEngine engine;
    private final CapabilityLoader capabilityLoader;
    private final AdapterFactory adapterFactory;
    private final ArtifactFileService artifactFileService;
    private final GitService gitService;
    private final CICDTriggerService cicdTriggerService;
    private final CodeValidationService codeValidationService;
    private final CheckpointService checkpointService;
    private final OrchestrationRecordMapper recordMapper;
    private final com.eaiselp.runtime.workspace.DingTalkNotifier dingTalkNotifier;
    /** 下行注入解析（PRJ-002 T22，SE 决策 D-1：runAsync 解析一次，每步复用同一份） */
    private final GovernanceInjectionService governanceInjectionService;
    /** 门禁规则快照数据源（PRJ-002 T19/T20，SE 决策 D-3：启动一次读全部 enabled 规则） */
    private final QualityGateRuleService qualityGateRuleService;

    /** 不可逆操作（部署）前的检查点等待超时：30 分钟 */
    @Value("${eaiselp.orchestration.approval-timeout-ms:1800000}")
    private long approvalTimeoutMs;

    @Value("${eaiselp.orchestration.step-interval-ms:3000}")
    private long stepIntervalMs;

    /** 编排任务内存态（重启丢失，M2 dogfooding 可接受） */
    private final ConcurrentHashMap<Long, OrchestrationState> stateMap = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    /** Fast 模式流水线（6 步） */
    private static final String[][] FAST_PIPELINE = {
            // {role, roleLabel, artifactType}
            {"team-po",       "产品经理(PO)",   "prd"},
            {"team-se",       "系统工程(SE)",    "tech-design"},
            {"team-dev",      "开发(Dev)",      "code"},
            {"team-reviewer", "代码审查",        "review"},
            {"team-qa",       "测试(QA)",       "test"},
            {"team-ops",      "运维(Ops)",      "deploy"},
    };

    // ======================== 质量门禁（Quality Gate，规则驱动） ====================
    // T21/AC-F6.3：原 GATE_ROLES 角色集合常量、isGateStep(role) 角色硬编码、team-ops 检查点
    // 触发硬编码已全部删除——门禁行为由 t_quality_gate_rule 数据驱动（启动快照 + 附着优先插入兜底，
    // 见 loadGateSnapshot/applyGateRulesToSteps；默认 seed 三条规则与删前行为逐项等价，SE §6.5）

    /** 门禁打回重做的最大轮次兜底值（超过则编排失败，拒绝部署有问题产出；
     *  规则级 max_retries 非空时优先用规则值，PRD F6.4） */
    @Value("${eaiselp.orchestration.gate-max-retries:2}")
    private int gateMaxRetries;

    /** 门禁判定标记 */
    private static final Pattern GATE_PASS_PATTERN = Pattern.compile("GATE\\s*:\\s*PASS", Pattern.CASE_INSENSITIVE);
    private static final Pattern GATE_FAIL_PATTERN = Pattern.compile(
            "GATE\\s*:\\s*FAIL\\s*[:：]?\\s*(.{0,500})", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 门禁角色步骤的指令增强：强制输出结构化判定 */
    private static final String GATE_INSTRUCTION_SUFFIX = "\n\n【质量门禁要求】你是质量门禁角色。" +
            "审查/测试完成后，必须在产出的最后一行给出明确判定（二选一）：\n" +
            "- 通过：输出 GATE:PASS\n" +
            "- 不通过：输出 GATE:FAIL: 具体问题清单（每个问题一行，供开发修复）\n" +
            "判定必须基于实际检查结果，不得敷衍放行。";

    /**
     * 解析门禁判定。
     * @return "PASS" / "FAIL" / null（未找到判定标记，视为 PASS 放行并告警——
     *         LLM 忘记输出判定时不卡死流程，但 log 提示）
     */
    static String parseGateResult(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return null;
        // 先查 FAIL（PASS 是 FAIL 的子串反例不存在，但 FAIL 带原因优先匹配）
        Matcher fail = GATE_FAIL_PATTERN.matcher(llmOutput);
        if (fail.find()) return "FAIL";
        Matcher pass = GATE_PASS_PATTERN.matcher(llmOutput);
        if (pass.find()) return "PASS";
        return null;
    }

    /** 提取门禁 FAIL 的原因（供打回注入）。 */
    static String extractGateReason(String llmOutput) {
        if (llmOutput == null) return "未提供原因";
        Matcher m = GATE_FAIL_PATTERN.matcher(llmOutput);
        if (m.find()) {
            String reason = m.group(1) == null ? "" : m.group(1).trim();
            // 截断到 500 字符（防超长）
            return reason.length() > 500 ? reason.substring(0, 500) : reason;
        }
        return "未提供原因";
    }

    /** 找门禁步骤应打回的上游步骤索引（最近的 code 类型步骤，通常为 Dev）。 */
    static int findRerunTarget(List<OrchestrationState.StepResult> steps, int gateIndex) {
        for (int i = gateIndex - 1; i >= 0; i--) {
            if ("code".equals(steps.get(i).getArtifactType())) return i;
        }
        // 无 code 步骤时打回门禁前一步
        return Math.max(0, gateIndex - 1);
    }

    // ==================== 门禁规则化（PRJ-002 T19/T20，SE §6 决策 D-3） ====================

    /**
     * 加载门禁规则快照（T19）：编排启动一次读全部 enabled 规则（listEnabledByStage(null)，
     * priority 升序），映射为不可变 {@link GateRuleSnapshot} 列表——整个编排生命周期只用它
     * （含打回回跳/断点续跑），运行期不缓存、不再查库（快照语义，AC-F6.2）。
     *
     * <p>降级：读取异常 → 空快照 + WARN，编排不中断（等价"无门禁规则"的保守放行，SE R5 同源语义）。</p>
     */
    List<GateRuleSnapshot> loadGateSnapshot(Long tenantId) {
        try {
            // TenantContext 已在 runAsync 开头 set，拦截器自动注入租户过滤（F-3/R4）
            List<GateRuleSnapshot> snapshot = qualityGateRuleService.listEnabledByStage(null).stream()
                    .map(GateRuleSnapshot::from)
                    .toList();   // Stream.toList() 不可变——快照生命周期内不可篡改
            log.info("[Orchestration][Gate] 门禁规则快照加载 tenantId={}, 规则数={}, 明细={}",
                    tenantId, snapshot.size(),
                    snapshot.stream().map(r -> r.name() + "[" + r.gateType() + "/" + r.stage() + "]").toList());
            return snapshot;
        } catch (Exception e) {
            log.warn("[Orchestration][Gate] 门禁规则快照加载失败（降级为无门禁规则，保守放行不阻塞编排）tenantId={}",
                    tenantId, e);
            return List.of();
        }
    }

    /**
     * 规则 → 步骤绑定（T19/T20，SE §6.3"附着优先、插入兜底"）。
     *
     * <p>按 stage 边界把启用规则挂到步骤上（边界基于步骤产物类型探测，规划完成后计算）：</p>
     * <ul>
     *   <li><b>llm_review</b>：边界紧邻处（边界位置或其前一步，就近先查）存在同 gate_role
     *       且未挂规则的步骤 → <b>附着</b>（标注 gateType/gateRuleId/maxRetries/failAction，
     *       执行时追加 GATE 判定要求——默认 FAST_PIPELINE 的 reviewer（边界位置）/qa（边界前一步）
     *       恰好命中附着，与改造前 GATE_ROLES 行为逐项等价且 token 不翻倍）；否则 <b>插入</b>
     *       一个门禁步骤（智能规划精简掉该角色时，企业治理强制生效）</li>
     *   <li><b>human_approval</b>：在边界步骤上标记审批闸（不新增步骤；同 stage 多条合并为
     *       一次等待，防多次 30 分钟阻塞，SE R3）</li>
     *   <li><b>auto_check</b>：不标注步骤（收尾 Git commit 前统一分发，见 dispatchAutoCheckGates）</li>
     * </ul>
     *
     * <p>边界不存在（LLM 规划出无 code/test/deploy 步骤的精简流水线，SE R10）→ 该规则跳过 +
     * WARN 留痕（保守放行）。插入会重排 index 并使后续边界右移——逐 stage 处理且每条规则
     * 实时重算边界，天然消化位移。</p>
     */
    void applyGateRulesToSteps(OrchestrationState state, List<GateRuleSnapshot> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        // stage 处理顺序固定（post_dev → post_test → pre_deploy）：靠前的插入使靠后的边界
        // 右移，实时重算保证挂载位置始终正确
        for (String stage : List.of("post_dev", "post_test", "pre_deploy")) {
            List<GateRuleSnapshot> stageRules = rules.stream()
                    .filter(r -> stage.equalsIgnoreCase(r.stage()))
                    .toList();
            if (stageRules.isEmpty()) {
                continue;
            }
            for (GateRuleSnapshot rule : stageRules) {
                int boundary = stageBoundary(state.getSteps(), stage);   // 实时重算（前序插入已右移）
                if (boundary < 0) {
                    log.warn("[Orchestration][Gate] 规则[{}]挂载阶段 {} 无对应边界步骤（流水线无该类型产物），"
                            + "规则跳过（保守放行，SE R10）caseId={}",
                            rule.name(), stage, state.getCaseId());
                    continue;
                }
                if (rule.isLlmReview()) {
                    attachOrInsertLlmGate(state, rule, boundary);
                } else if (rule.isHumanApproval()) {
                    markApprovalGate(state, rule, boundary);
                } else if (rule.isAutoCheck()) {
                    log.info("[Orchestration][Gate] auto_check 规则[{}]登记收尾执行（Git commit 前分发 checkKey={}）caseId={}",
                            rule.name(), rule.checkKey(), state.getCaseId());
                } else {
                    log.warn("[Orchestration][Gate] 未知 gate_type（规则跳过，保守放行）规则={} gateType={}",
                            rule.name(), rule.gateType());
                }
            }
        }
    }

    /**
     * 计算 stage 边界的步骤插入位置（基于步骤产物类型探测）：
     * post_dev = 最后一个 code 步骤之后；post_test = 最后一个 test 步骤之后；
     * pre_deploy = 第一个 deploy 步骤处（其前）。
     *
     * @return 插入位置索引；-1 = 边界不存在（无对应类型产物步骤）
     */
    static int stageBoundary(List<OrchestrationState.StepResult> steps, String stage) {
        if (steps == null || steps.isEmpty()) {
            return -1;
        }
        switch (stage == null ? "" : stage.toLowerCase()) {
            case "post_dev": {
                int last = -1;
                for (int i = 0; i < steps.size(); i++) {
                    if ("code".equals(steps.get(i).getArtifactType())) last = i;
                }
                return last < 0 ? -1 : last + 1;
            }
            case "post_test": {
                int last = -1;
                for (int i = 0; i < steps.size(); i++) {
                    if ("test".equals(steps.get(i).getArtifactType())) last = i;
                }
                return last < 0 ? -1 : last + 1;
            }
            case "pre_deploy": {
                for (int i = 0; i < steps.size(); i++) {
                    if ("deploy".equals(steps.get(i).getArtifactType())) return i;
                }
                return -1;
            }
            default:
                return -1;
        }
    }

    /**
     * llm_review 规则的"附着优先、插入兜底"（SE §6.3）。
     *
     * <p>附着窗口 = 边界位置与其前一步（就近）：FAST_PIPELINE 的 team-reviewer 恰在 post_dev
     * 边界位置（dev 之后第一个步骤）、team-qa 恰在 post_test 边界前一步（qa 自身产出 test 产物，
     * 边界在其后）——两条 seed 规则均命中附着，与改造前 GATE_ROLES 行为完全等价。
     * 窗口外不再向前/向后搜索：更远的同角色步骤语义上不属于该边界（SE §6.3"已消费"约束），
     * 宁可插入也不误挂。</p>
     */
    private void attachOrInsertLlmGate(OrchestrationState state, GateRuleSnapshot rule, int boundary) {
        List<OrchestrationState.StepResult> steps = state.getSteps();
        // ① 附着优先：边界位置 → 其前一步，就近找同 gate_role 且未挂门禁的步骤
        for (int idx : new int[]{boundary, boundary - 1}) {
            if (idx < 0 || idx >= steps.size()) {
                continue;
            }
            OrchestrationState.StepResult cand = steps.get(idx);
            if (rule.gateRole() != null && rule.gateRole().equals(cand.getRole()) && cand.getGateType() == null) {
                markGate(cand, rule);
                log.info("[Orchestration][Gate] 门禁规则附着既有步骤: 规则[{}], 步骤{}({}), stage={}",
                        rule.name(), idx + 1, cand.getRole(), rule.stage());
                return;
            }
        }
        // ② 插入兜底：边界前无同角色步骤（智能规划精简掉该角色）→ 插入一个门禁步骤强制生效
        String gateRole = rule.gateRole() != null ? rule.gateRole() : "team-reviewer";
        OrchestrationState.StepResult gate = OrchestrationState.StepResult.pending(
                0, gateRole, OrchestrationState.StepResult.ROLE_LABELS.getOrDefault(gateRole, gateRole), "gate");
        gate.setInstruction("质量门禁审查（规则：" + rule.name() + "）：请对上游产出按本规则要求进行严格审查。");
        markGate(gate, rule);
        steps.add(boundary, gate);
        reindex(steps);
        log.info("[Orchestration][Gate] 边界前无同角色步骤，插入门禁步骤: 规则[{}], 角色={}, 位置={}, stage={}",
                rule.name(), gateRole, boundary + 1, rule.stage());
    }

    /** 把规则属性标注到步骤上（附着与插入共用）。 */
    private static void markGate(OrchestrationState.StepResult step, GateRuleSnapshot rule) {
        step.setGateRuleId(rule.id());
        step.setGateType(rule.gateType());
        step.setGateStage(rule.stage());
        step.setGateMaxRetries(rule.maxRetries());
        step.setGateFailAction(rule.failAction());
    }

    /**
     * human_approval 审批闸标记（不新增步骤）：挂在边界位置的首个步骤上（pre_deploy = 第一个
     * deploy 步骤，执行前等待审批）；同 stage 多条规则合并为一次等待（规则名顿号拼接，SE R3）。
     */
    private void markApprovalGate(OrchestrationState state, GateRuleSnapshot rule, int boundary) {
        List<OrchestrationState.StepResult> steps = state.getSteps();
        if (boundary >= steps.size()) {
            // post_dev/post_test 边界恰在流水线末尾（其后无步骤可拦）→ 无可挂载点，跳过 + WARN
            log.warn("[Orchestration][Gate] 人工审批规则[{}]边界在流水线末尾（其后无步骤），规则跳过（保守放行）caseId={}",
                    rule.name(), state.getCaseId());
            return;
        }
        OrchestrationState.StepResult step = steps.get(boundary);
        step.setApprovalRuleNames(step.getApprovalRuleNames() == null
                ? rule.name()
                : step.getApprovalRuleNames() + "、" + rule.name());   // 同 stage 合并一次等待
        if (step.getGateStage() == null) {
            step.setGateStage(rule.stage());
        }
        log.info("[Orchestration][Gate] 人工审批闸标记: 规则[{}], 挂到步骤{}({})执行前, stage={}",
                rule.name(), boundary + 1, step.getRole(), rule.stage());
    }

    /** 插入步骤后重排序号（index 从 1 连续）。 */
    private static void reindex(List<OrchestrationState.StepResult> steps) {
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).setIndex(i + 1);
        }
    }

    /**
     * auto_check 门禁分发（T20，SE §6.4）：收尾 Git commit/push 前按 check_key 执行。
     *
     * <p>check_key → 检查器注册表（本期仅一项）：code_validation → 复用无条件执行的
     * {@code CodeValidationService.validateWorkspace} 结果（state.validation）。allPassed=false
     * 且 fail_action=block → 编排终判失败（调用方不 commit/push/不触发 CI，AC-F6.6）；
     * warn → FAIL 告警放行。新增检查器 = 新增注册项（Map 分发，不留 if-else 硬编码生长点）。</p>
     *
     * @return true = 存在 block 规则未通过，必须阻断 Git 落地
     */
    private boolean dispatchAutoCheckGates(OrchestrationState state) {
        List<GateRuleSnapshot> rules = state.getGateRules() == null ? List.of() : state.getGateRules();
        boolean blocked = false;
        for (GateRuleSnapshot rule : rules) {
            if (!rule.isAutoCheck()) {
                continue;
            }
            if (!"code_validation".equalsIgnoreCase(rule.checkKey())) {
                // 未知 check_key：无注册检查器，保守放行 + WARN 留痕（与 R5/R10 同源语义）
                log.warn("[Orchestration][Gate] auto_check 规则[{}]的 check_key={} 无对应检查器，跳过（保守放行）caseId={}",
                        rule.name(), rule.checkKey(), state.getCaseId());
                continue;
            }
            OrchestrationState.CodeValidationSummary summary = state.getValidation();
            if (summary == null) {
                // 验证未产出结果（基础设施异常）：无法判定 → 告警放行留痕，不因检查设施故障阻断交付
                log.warn("[Orchestration][Gate] auto_check 规则[{}]无验证结果可用（验证异常？），放行留痕 caseId={}",
                        rule.name(), state.getCaseId());
                continue;
            }
            if (summary.isAllPassed()) {
                log.info("[Orchestration][Gate] auto_check 通过: 规则[{}], {}/{} 文件验证通过 caseId={}",
                        rule.name(), summary.getPassedFiles(), summary.getTotalFiles(), state.getCaseId());
            } else if (rule.isWarnAction()) {
                log.warn("[Orchestration][Gate] auto_check 未通过但 fail_action=warn，放行: 规则[{}], {}/{} 通过 caseId={}",
                        rule.name(), summary.getPassedFiles(), summary.getTotalFiles(), state.getCaseId());
            } else {
                blocked = true;
                log.error("[Orchestration][Gate] auto_check 阻断（fail_action=block）: 规则[{}], {}/{} 通过 caseId={}",
                        rule.name(), summary.getPassedFiles(), summary.getTotalFiles(), state.getCaseId());
            }
        }
        return blocked;
    }

    /**
     * 创建编排任务（同步返回 ID，异步执行流水线）。
     *
     * @param requirement 一句话需求
     * @param caseId      关联 Case
     * @param tier        模式（目前只支持 fast）
     * @return 编排任务 ID
     */
    public Long start(String requirement, String caseId, String tier) {
        Long id = idGenerator.getAndIncrement();
        OrchestrationState state = new OrchestrationState();
        state.setId(id);
        state.setCaseId(caseId);
        state.setRequirement(requirement);
        state.setTier(tier != null ? tier : "fast");
        state.setStatus("pending");
        state.setCreatedAt(LocalDateTime.now());

        // 初始化步骤列表（默认 fast 预填；runAsync 时 LLM 智能规划可能替换）
        String[][] pipeline = FAST_PIPELINE;
        for (int i = 0; i < pipeline.length; i++) {
            state.getSteps().add(OrchestrationState.StepResult.pending(
                    i + 1, pipeline[i][0], pipeline[i][1], pipeline[i][2]));
        }

        stateMap.put(id, state);
        persistState(state, tenantIdOfContext());   // 持久化初始状态
        log.info("[Orchestration] 编排任务创建 id={}, caseId={}, steps={}", id, caseId, pipeline.length);
        return id;
    }

    /** 从上下文取当前租户（启动线程有 TenantContext；异步线程显式传）。 */
    private Long tenantIdOfContext() {
        Long t = TenantContext.get();
        return t != null ? t : 0L;
    }

    /**
     * 编排状态持久化到 t_orchestration（#15：重启不丢）。
     *
     * <p>失败只 log 不阻塞（持久化是增强，内存态照常工作）。</p>
     */
    void persistState(OrchestrationState state, Long tenantId) {
        try {
            OrchestrationRecord r = new OrchestrationRecord();
            r.setId(state.getId());
            r.setTenantId(tenantId);
            r.setCaseId(state.getCaseId());
            r.setRequirement(state.getRequirement());
            r.setTier(state.getTier());
            r.setStatus(state.getStatus());
            r.setCurrentRole(state.getCurrentRole());
            r.setPendingCheckpointId(state.getPendingCheckpointId());
            r.setApprovalMessage(state.getApprovalMessage());
            r.setStepsJson(OM.writeValueAsString(state.getSteps()));
            r.setValidationJson(state.getValidation() != null ? OM.writeValueAsString(state.getValidation()) : null);
            r.setInjectedPrinciplesJson(state.getInjectedPrinciplesJson());
            r.setCreatedAt(state.getCreatedAt());
            r.setFinishedAt(state.getFinishedAt());
            // upsert：存在则 update，不存在则 insert
            OrchestrationRecord exist = recordMapper.selectById(state.getId());
            if (exist == null) recordMapper.insert(r);
            else recordMapper.updateById(r);
        } catch (Exception e) {
            log.warn("[Orchestration] 状态持久化失败 id={}（不阻塞）: {}", state.getId(), e.getMessage());
        }
    }

    /**
     * 解析下行注入并写入编排状态（T22，SE 决策 D-1）。
     *
     * <p>一次解析、整条编排复用：预渲染章节文本（含标题）存 {@code state.governanceContext}，
     * 原则 code 清单序列化存 {@code state.injectedPrinciplesJson}（AC-F7.1 三处留痕：
     * ①LLM 上下文章节 ②t_orchestration.injected_json ③服务端日志——本方法补编排侧 INFO 摘要，
     * 解析链路日志由 GovernanceInjectionService 输出）。</p>
     *
     * <p>降级（AC-F7 硬约束）：resolveInjection 内部已 catch 全部异常返回空结果；本方法再包一层
     * try-catch 防 JSON 序列化意外——任何失败 = 空注入 + WARN，编排不中断。</p>
     */
    private void resolveGovernanceInjection(OrchestrationState state, Long tenantId) {
        try {
            GovernanceInjectionService.InjectionResult inj =
                    governanceInjectionService.resolveInjection(state.getCaseId(), tenantId);
            state.setGovernanceContext(inj.getGovernanceText());   // null/blank = 整体省略章节（空标题禁止）
            state.setInjectedPrinciplesJson(OM.writeValueAsString(inj.getInjectedPrinciples()));
            log.info("[Orchestration][Inject] 下行注入完成 id={}, caseId={}, 注入原则={}, 字符={}, 截断={}",
                    state.getId(), state.getCaseId(), inj.getInjectedPrinciples(),
                    inj.getRenderedChars(), inj.isTruncated());
        } catch (Exception e) {
            state.setGovernanceContext(null);
            log.warn("[Orchestration][Inject] 下行注入解析异常（降级为无注入，不阻塞编排）id={}, caseId={}",
                    state.getId(), state.getCaseId(), e);
        }
    }

    /**
     * 异步执行编排流水线（在一个异步线程内串行循环调 engine.derive）。
     *
     * <p>每步把前面所有步骤的产出填入 DerivationContext.upstreamArtifacts，
     * ContextAssembler 会渲染成"## 上游产出（必读）"注入 prompt。</p>
     */
    @Async("orchestrationExecutor")
    public void runAsync(Long id, Long tenantId) {
        OrchestrationState state = stateMap.get(id);
        if (state == null) {
            log.error("[Orchestration] 编排任务不存在 id={}", id);
            return;
        }

        TenantContext.set(tenantId);
        state.setStatus("running");
        Map<String, String> upstreamArtifacts = new HashMap<>();

        log.info("[Orchestration] 开始执行编排 id={}, requirement={}", id, state.getRequirement());

        // ★ 智能规划（#2 编排智能化）：用 team-orchestrator LLM 分析需求动态生成流水线
        // 规划失败降级为默认 fast 流水线（start 时已预填）
        boolean planned = planPipelineWithLlm(state);

        // ★ T22 下行注入（SE 决策 D-1）：智能规划后、循环前解析一次（caseId→project→原则），
        //   预渲染章节文本存入 state，每步构建 DerivationContext 时复用同一份；
        //   失败降级为空注入 + WARN，绝不中断编排（AC-F7）
        resolveGovernanceInjection(state, tenantId);

        // ★ T19/T20 门禁规则快照 + 步骤绑定（SE 决策 D-3）：启动时一次读全部 enabled 规则
        //   （不可变快照，整个编排生命周期只用它）；"附着优先、插入兜底"挂到步骤（§6.3），
        //   门禁行为从此数据驱动（替换 GATE_ROLES/team-ops 检查点硬编码，AC-F6.3）
        List<GateRuleSnapshot> gateRules = loadGateSnapshot(tenantId);
        state.setGateRules(gateRules);
        applyGateRulesToSteps(state, gateRules);

        List<OrchestrationState.StepResult> steps = state.getSteps();
        int total = steps.size();
        persistState(state, tenantId);   // 规划+注入+门禁标注后的首个完整快照落库
        if (planned) {
            log.info("[Orchestration] 智能规划成功 id={}, 步骤数={}", id, total);
        } else {
            log.info("[Orchestration] 智能规划降级为默认 fast 流水线 id={}", id);
        }

        // while 循环 + 门禁回跳（Quality Gate：门禁 FAIL 自动打回上游重做）
        int i = 0;
        java.util.Map<Integer, Integer> gateRerunCounts = new HashMap<>();
        while (i < total) {
            OrchestrationState.StepResult stepResult = steps.get(i);
            if ("success".equals(stepResult.getStatus()) || "skipped".equals(stepResult.getStatus())) {
                i++; continue;   // 已完成步骤（重跑后回到此处跳过已成功前序）
            }
            String role = stepResult.getRole();
            String roleLabel = stepResult.getRoleLabel();
            String artifactType = stepResult.getArtifactType();
            state.setCurrentRole(role);
            stepResult.setStatus("running");
            stepResult.setStartedAt(LocalDateTime.now());

            try {
                // ★ T20 人工审批门禁（human_approval 规则驱动，SE §6.4，承接原 team-ops 硬编码
                //   检查点，AC-F6.5 复用检查点页面与 30 分钟超时）：本步骤被规则标记为审批闸
                //   （同 stage 多条 human_approval 已在 applyGateRulesToSteps 合并为一次等待，SE R3）
                //   → 执行前暂停等待人工审批
                if (stepResult.getApprovalRuleNames() != null && !stepResult.getApprovalRuleNames().isBlank()) {
                    ApprovalDecision decision = awaitApproval(state, stepResult);
                    if (decision == ApprovalDecision.REJECTED || decision == ApprovalDecision.TIMEOUT) {
                        // 拒绝/超时 → 跳过本步骤及其后所有 deploy 类步骤（等价原"跳过部署"，
                        // 其余步骤成果保留），编排继续收尾
                        stepResult.setStatus("skipped");
                        stepResult.setError(decision == ApprovalDecision.REJECTED
                                ? "人工审批门禁被拒绝，跳过部署" : "审批超时（30分钟），跳过部署");
                        stepResult.setFinishedAt(LocalDateTime.now());
                        for (int j = i + 1; j < total; j++) {
                            if ("deploy".equals(steps.get(j).getArtifactType())
                                    && "pending".equals(steps.get(j).getStatus())) {
                                steps.get(j).setStatus("skipped");
                                steps.get(j).setError("人工审批门禁未通过，部署步骤不执行");
                            }
                        }
                        state.setCurrentRole(null);
                        log.warn("[Orchestration][Gate] 人工审批未通过，跳过部署 id={}, 规则={}, 原因={}",
                                id, stepResult.getApprovalRuleNames(), decision);
                        i++; continue;
                    }
                    // approved → 继续执行该步骤
                }

                // 获取角色定义
                AgentDefinition agent = capabilityLoader.getAgent(role);
                if (agent == null) {
                    throw new RuntimeException("角色未注册: " + role);
                }

                // 构建上下文：把前面所有步骤的产出传给当前步骤
                // 指令优先用编排者 LLM 规划的定制指令（智能规划），无则用默认文案
                String instruction = (stepResult.getInstruction() != null && !stepResult.getInstruction().isBlank())
                        ? stepResult.getInstruction()
                        : "这是流水线编排的第 " + (i + 1) + " 步（共 " + total + " 步）。请基于需求和上游产出，产出你的专业内容。";
                // 门禁打回反馈注入（重跑步骤带上审查意见）
                if (state.getPendingGateFeedback() != null && !state.getPendingGateFeedback().isBlank()) {
                    instruction += "\n\n【上一轮质量审查未通过，必须修复以下问题后重新产出】\n"
                            + state.getPendingGateFeedback();
                    state.setPendingGateFeedback(null);   // 用后清空
                }
                // 门禁判定格式要求（T19：规则驱动——步骤被 llm_review 规则附着/插入即门禁步骤，
                // 与原 GATE_ROLES 行为等价：默认 seed 下同为 reviewer/qa 两步追加同一 SUFFIX）
                boolean isGate = "llm_review".equals(stepResult.getGateType());
                if (isGate) instruction += GATE_INSTRUCTION_SUFFIX;

                DerivationContext ctx = DerivationContext.builder()
                        .task(state.getRequirement())
                        .stage(artifactType)
                        .upstreamArtifacts(upstreamArtifacts.isEmpty() ? null : new HashMap<>(upstreamArtifacts))
                        .extraInstructions(instruction)
                        .governanceContext(state.getGovernanceContext())   // T22：每步复用同一份注入快照
                        .build();

                log.info("[Orchestration] 步骤 {}/{} 派生: role={}, case={}{}", i + 1, total, role, state.getCaseId(),
                        isGate ? " [GATE]" : "");

                // 同步调用 engine.derive（在异步线程内阻塞等待 LLM 返回）
                DerivationEngine.DerivationResult result = engine.derive(agent, state.getRequirement(), state.getCaseId(), ctx);

                // ★ 质量门禁判定：解析 GATE:PASS/FAIL（T19 规则驱动，SE §6.4 llm_review 分派）
                if (isGate && result != null && result.getOutput() != null) {
                    String gate = parseGateResult(result.getOutput());
                    if ("FAIL".equals(gate)) {
                        String reason = extractGateReason(result.getOutput());
                        // 规则级失败动作：warn → FAIL_WARN 记录放行（SE §6.4；block/空 → 打回/终判）
                        if (stepResult.getGateFailAction() != null
                                && "warn".equalsIgnoreCase(stepResult.getGateFailAction())) {
                            stepResult.setGateResult("FAIL_WARN");
                            stepResult.setGateReason(reason);
                            log.warn("[Orchestration][Gate] 门禁 FAIL 但 fail_action=warn，记录放行 id={}, gate={}, 规则={}, 原因={}",
                                    id, role, stepResult.getGateRuleId(), reason);
                        } else {
                        int maxRetries = stepResult.getGateMaxRetries() != null
                                ? stepResult.getGateMaxRetries() : gateMaxRetries;   // 规则级优先，null 走 yml 兜底（PRD F6.4）
                        int rerun = gateRerunCounts.merge(i, 1, Integer::sum);
                        if (rerun > maxRetries) {
                            // 超过重做上限 → 门禁终判失败，编排终止（拒绝部署有问题产出）
                            stepResult.setStatus("failed");
                            stepResult.setGateResult("FAIL");
                            stepResult.setGateReason(reason);
                            stepResult.setError("质量门禁 " + maxRetries + " 轮重做后仍未通过: " + reason);
                            stepResult.setFinishedAt(LocalDateTime.now());
                            log.warn("[Orchestration][Gate] 质量门禁终判失败 id={}, gate={}, 规则={}, 轮次={}, 原因={}",
                                    id, role, stepResult.getGateRuleId(), rerun - 1, reason);
                            // 后续步骤全部标记 skipped（不部署有问题产出）
                            for (int j = i + 1; j < total; j++) {
                                steps.get(j).setStatus("skipped");
                                steps.get(j).setError("上游质量门禁未通过，本步骤不执行");
                            }
                            break;
                        }
                        // 打回重做：审查意见存入 pendingGateFeedback，回退到 stage 边界对应的上游步骤
                        stepResult.setGateResult("FAIL");
                        stepResult.setGateReason(reason);
                        stepResult.setRerunCount(rerun);
                        state.setPendingGateFeedback(reason);
                        // 打回目标按规则的 stage 边界计算：post_dev/post_test/pre_deploy 一律打回
                        // 门禁前最后一个 code 步骤（findRerunTarget 即"最近的 code 类型步骤"）——
                        // 与改造前打回 Dev 的行为逐项等价（SE §6.5 等价表第 1 行）
                        int target = findRerunTarget(steps, i);
                        steps.get(target).setRerunCount(steps.get(target).getRerunCount() + 1);
                        log.warn("[Orchestration][Gate] 质量门禁 FAIL 打回重做 id={}, gate={}, 规则={}, 第{}轮, 打回到步骤{} ({}), 原因={}",
                                id, role, stepResult.getGateRuleId(), rerun, target + 1, steps.get(target).getRole(), reason);
                        // 重置打回步骤为 pending（本门禁步骤也重置）
                        steps.get(target).setStatus("pending");
                        stepResult.setStatus("pending");
                        i = target;   // 回跳
                        persistState(state, tenantId);
                        continue;
                        }
                    }
                    // PASS（或 LLM 忘输出判定视为 PASS 放行并告警——现状语义保留）
                    stepResult.setGateResult("PASS");
                    if (gate == null) {
                        log.warn("[Orchestration][Gate] 门禁角色 {} 未输出 GATE 判定标记，视为 PASS 放行（建议检查 prompt）", role);
                    } else {
                        log.info("[Orchestration][Gate] 质量门禁 PASS: {} (caseId={})", role, state.getCaseId());
                    }
                }

                // 把这一步的产出存入 upstreamArtifacts 供后续步骤使用
                if (result != null && result.getOutput() != null) {
                    // 截断到 4000 字符（与 ContextAssembler 的 upstreamArtifacts 渲染限制对齐）
                    String output = result.getOutput();
                    if (output.length() > 4000) {
                        output = output.substring(0, 4000) + "\n... (已截断)";
                    }
                    upstreamArtifacts.put(roleLabel + "的" + artifactType, output);
                }

                stepResult.setStatus("success");
                stepResult.setFinishedAt(LocalDateTime.now());
                persistState(state, tenantId);   // 步骤完成持久化
                log.info("[Orchestration] 步骤 {}/{} 完成: role={}", i + 1, total, role);

                // ★ 产出落地：把 LLM 产出写入工作区文件系统
                if (result != null && result.getOutput() != null) {
                    try {
                        var written = artifactFileService.writeToWorkspace(
                                state.getCaseId(), role, result.getOutput());
                        log.info("[Orchestration] 产出落地: role={}, 文件数={}", role, written.size());
                    } catch (Exception fe) {
                        log.warn("[Orchestration] 产出落地失败（不阻塞流程）: role={}", role, fe);
                    }
                }

                // 步骤间间隔（防 LLM 429 限流）
                if (i < total - 1 && stepIntervalMs > 0) {
                    Thread.sleep(stepIntervalMs);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stepResult.setStatus("failed");
                stepResult.setError("编排被中断");
                stepResult.setFinishedAt(LocalDateTime.now());
                state.setStatus("failed");
                log.error("[Orchestration] 编排被中断 id={}", id);
                break;
            } catch (Throwable t) {
                // 降级策略：某步失败不中断整条流水线，记录失败继续下一步
                String errMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                stepResult.setStatus("failed");
                stepResult.setError(errMsg);
                stepResult.setFinishedAt(LocalDateTime.now());
                log.error("[Orchestration] 步骤 {}/{} 失败: role={}, error={}", i + 1, total, role, errMsg, t);

                // 失败后也等一下（可能是因为 429 限流，给后续步骤恢复时间）
                try {
                    if (i < total - 1 && stepIntervalMs > 0) {
                        Thread.sleep(stepIntervalMs);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            i++;   // while 循环手动递增（门禁回跳时 continue 前已设置目标索引）
        }

        state.setStatus("done");
        state.setCurrentRole(null);
        state.setFinishedAt(LocalDateTime.now());
        persistState(state, tenantId);   // 编排结束持久化
        TenantContext.clear();

        int success = (int) state.getSteps().stream().filter(s -> "success".equals(s.getStatus())).count();
        int failed = (int) state.getSteps().stream().filter(s -> "failed".equals(s.getStatus())).count();
        log.info("[Orchestration] 编排完成 id={}, 成功={}, 失败={}", id, success, failed);

        // #28 钉钉通知：编排完成推送（配置了 Webhook 才发）
        try {
            var validation = state.getValidation();
            dingTalkNotifier.notifyOrchestrationDone(state.getCaseId(), success, state.totalSteps(),
                    validation != null && validation.isAllPassed());
        } catch (Exception ne) { log.debug("[Orchestration] 钉钉通知失败（忽略）", ne); }

        // ★ Git 落地：编排完成后，把工作区 commit + push
        if (success > 0) {
            try {
                // ★ 产出验证（核心价值闭环：AI 产出必须验证，结果记录到编排状态供前端展示）
                try {
                    var vr = codeValidationService.validateWorkspace(state.getCaseId());
                    OrchestrationState.CodeValidationSummary summary = new OrchestrationState.CodeValidationSummary();
                    summary.setAllPassed(vr.isAllPassed());
                    summary.setTotalFiles(vr.getTotalFiles());
                    summary.setPassedFiles(vr.getPassedFiles());
                    summary.setFailedFiles(vr.getFailedFiles());
                    summary.setValidatedAt(vr.getValidatedAt());
                    state.setValidation(summary);
                    log.info("[Orchestration] 产出验证: {}/{} 通过, 全部通过={}",
                            vr.getPassedFiles(), vr.getTotalFiles(), vr.isAllPassed());
                } catch (Exception ve) {
                    log.warn("[Orchestration] 产出验证失败（不阻塞）", ve);
                }

                // ★ T20 auto_check 门禁（SE §6.4）：收尾 Git commit/push 前按 check_key 分发执行
                //   （本期注册表仅 code_validation → CodeValidationService.validateWorkspace，其结果
                //   上面已算好存 state.validation，这里直接消费）。无 auto_check 规则时验证仍执行
                //   （现状行为）——验证=总跑+记录，auto_check 规则=是否阻断的治理决策，升级等价由此保持
                boolean gateBlocked = dispatchAutoCheckGates(state);
                if (gateBlocked) {
                    // fail_action=block 且未通过 → 编排终判失败：不 commit/push/不触发 CI（AC-F6.6）
                    state.setStatus("failed");
                    persistState(state, tenantId);
                    log.error("[Orchestration][Gate] auto_check 门禁阻断：不 commit/push/不触发 CI id={}, caseId={}",
                            id, state.getCaseId());
                }

                if (!gateBlocked) {
                    String commitMsg = "eAISEDP 编排产出: " + state.getCaseId()
                            + " (成功" + success + "/" + state.totalSteps() + ")";
                    String commitHash = gitService.commitWorkspace(state.getCaseId(), commitMsg);
                    if (commitHash != null) {
                        log.info("[Orchestration] Git commit 成功: {} → {}", state.getCaseId(), commitHash.substring(0, 8));
                        // 远程推送（配置了远程地址才 push）
                        gitService.pushWorkspace(state.getCaseId());
                        // CI/CD 触发（配置了 Webhook URL 才触发，适配 Jenkins/GitLab/GitHub/Gitea 等）
                        var files = artifactFileService.listFiles(state.getCaseId());
                        cicdTriggerService.triggerBuild(state.getCaseId(), commitHash, files, state.getRequirement());
                    }
                }
            } catch (Exception ge) {
                log.warn("[Orchestration] Git 落地失败（不阻塞流程）", ge);
            }
        }
    }

    /** 查询编排进度（内存优先，重启后从 DB 恢复）。 */
    public OrchestrationState getState(Long id) {
        OrchestrationState s = stateMap.get(id);
        if (s != null) return s;
        // #15 重启恢复：内存 miss 从 t_orchestration 读回
        try {
            OrchestrationRecord r = recordMapper.selectById(id);
            if (r == null) return null;
            OrchestrationState restored = new OrchestrationState();
            restored.setId(r.getId());
            restored.setCaseId(r.getCaseId());
            restored.setRequirement(r.getRequirement());
            restored.setTier(r.getTier());
            restored.setStatus(r.getStatus());
            restored.setCurrentRole(r.getCurrentRole());
            restored.setPendingCheckpointId(r.getPendingCheckpointId());
            restored.setApprovalMessage(r.getApprovalMessage());
            restored.setCreatedAt(r.getCreatedAt());
            restored.setFinishedAt(r.getFinishedAt());
            if (r.getStepsJson() != null) {
                restored.setSteps(OM.readValue(r.getStepsJson(),
                        OM.getTypeFactory().constructCollectionType(java.util.ArrayList.class, OrchestrationState.StepResult.class)));
            }
            if (r.getValidationJson() != null) {
                restored.setValidation(OM.readValue(r.getValidationJson(), OrchestrationState.CodeValidationSummary.class));
            }
            // 注入清单留痕恢复（governanceContext 不落库：断点续跑时按需重解析重建，见 runFromStep）
            restored.setInjectedPrinciplesJson(r.getInjectedPrinciplesJson());
            // 服务重启后 running 态的编排线程已死，标记为 failed（成果在产物/工作区可查）
            if ("running".equals(restored.getStatus()) || "awaiting_approval".equals(restored.getStatus())) {
                restored.setStatus("failed");
                restored.setApprovalMessage(null);
                log.warn("[Orchestration] 重启恢复: 编排 {} 曾在运行中（线程已丢失），标记 failed", id);
            }
            stateMap.put(id, restored);
            return restored;
        } catch (Exception e) {
            log.warn("[Orchestration] DB 恢复失败 id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /** JSON 解析器（LLM 规划结果解析）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 智能规划流水线（#2 编排智能化）。
     *
     * <p>用 team-orchestrator 角色的 LLM 分析需求，动态生成流水线：
     * 选哪些角色、什么顺序、每步定制指令。小需求少步骤省 token，大需求全流程。</p>
     *
     * <p><b>降级策略</b>：规划失败（LLM 异常/JSON 解析失败/角色不存在/步骤数越界）
     * 一律保留 start() 预填的默认 fast 流水线，编排不中断。</p>
     *
     * @return true=规划成功并替换了步骤列表 / false=降级保留默认
     */
    boolean planPipelineWithLlm(OrchestrationState state) {
        try {
            AgentDefinition orchestrator = capabilityLoader.getAgent("team-orchestrator");
            if (orchestrator == null) {
                log.warn("[Orchestration] team-orchestrator 角色未注册，降级默认流水线");
                return false;
            }

            // 用编排者角色的模型档位调 LLM（prompt 定义在 agents/team-orchestrator.md）
            String tier = orchestrator.getModel() != null ? orchestrator.getModel() : "sonnet";
            String model = adapterFactory.resolveModel(tier);
            LlmAdapter llm = adapterFactory.getLlmAdapter(tier);
            String prompt = orchestrator.getPrompt() + "\n\n---\n## 待规划需求\n" + state.getRequirement();
            LlmAdapter.LlmResponse resp = llm.invoke(model, prompt,
                    LlmAdapter.LlmOptions.builder().timeoutMs(60000L).build());

            // 解析 JSON（清洗可能的 markdown 代码块包裹）
            String content = cleanJson(resp.getContent());
            com.fasterxml.jackson.databind.JsonNode root = OM.readTree(content);
            com.fasterxml.jackson.databind.JsonNode stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray() || stepsNode.size() < 1) {
                log.warn("[Orchestration] LLM 规划 steps 为空，降级");
                return false;
            }
            if (stepsNode.size() > 8) {
                log.warn("[Orchestration] LLM 规划步骤数 {} 超上限 8，降级", stepsNode.size());
                return false;
            }

            // 逐项校验角色存在，构建动态步骤列表
            List<OrchestrationState.StepResult> planned = new java.util.ArrayList<>();
            for (int i = 0; i < stepsNode.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode s = stepsNode.get(i);
                String role = s.path("role").asText(null);
                String artifactType = s.path("artifactType").asText("other");
                String instruction = s.path("instruction").asText(null);
                if (role == null || capabilityLoader.getAgent(role) == null) {
                    log.warn("[Orchestration] LLM 规划的角色未注册: {}，降级", role);
                    return false;
                }
                String label = OrchestrationState.StepResult.ROLE_LABELS.getOrDefault(role, role);
                OrchestrationState.StepResult sr = OrchestrationState.StepResult.pending(
                        i + 1, role, label, artifactType);
                sr.setInstruction(instruction);
                planned.add(sr);
            }

            // 校验通过 → 替换步骤列表 + 更新档位
            state.setSteps(planned);
            String plannedTier = root.path("tier").asText("standard");
            state.setTier(plannedTier);
            log.info("[Orchestration] 智能流水线: tier={}, steps={}",
                    plannedTier, planned.stream().map(s -> s.getRole() + "(" + s.getArtifactType() + ")").toList());
            return true;

        } catch (Exception e) {
            log.warn("[Orchestration] 智能规划异常，降级默认流水线: {}", e.getMessage());
            return false;
        }
    }

    /** 清洗 LLM 返回的 JSON（去掉 markdown 代码块包裹和前后杂文）。 */
    private String cleanJson(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 去 ```json ... ``` 包裹
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) s = s.substring(first + 1, last).trim();
        }
        // 截取第一个 { 到最后一个 }（防前后杂文）
        int l = s.indexOf('{'), r = s.lastIndexOf('}');
        if (l >= 0 && r > l) s = s.substring(l, r + 1);
        return s;
    }

    /**
     * 单步重跑（#16 断点续跑：失败的编排不整条重来，只重跑失败步骤起的后半段）。
     *
     * <p>从指定步骤（默认第一个非 success 步骤）开始，重置该步及之后所有步骤为 pending，
     * 然后在编排线程池重新执行。已成功步骤的 upstreamArtifacts 从 t_artifact 重建。</p>
     */
    @Async("orchestrationExecutor")
    public void retryFromStep(Long id, int stepIndex, Long tenantId) {
        OrchestrationState state = getState(id);
        if (state == null) {
            log.warn("[Orchestration] 重试的编排不存在 id={}", id);
            return;
        }
        // 只允许 done/failed 状态的编排重试
        if (!"done".equals(state.getStatus()) && !"failed".equals(state.getStatus())) {
            log.warn("[Orchestration] 编排 {} 状态 {} 不可重试（仅 done/failed）", id, state.getStatus());
            return;
        }
        // 重置指定步骤及之后全部为 pending
        List<OrchestrationState.StepResult> steps = state.getSteps();
        int from = Math.max(1, Math.min(stepIndex, steps.size()));
        for (int i = from - 1; i < steps.size(); i++) {
            OrchestrationState.StepResult sr = steps.get(i);
            sr.setStatus("pending");
            sr.setError(null);
            sr.setStartedAt(null);
            sr.setFinishedAt(null);
        }
        state.setStatus("pending");
        state.setFinishedAt(null);
        persistState(state, tenantId);
        log.info("[Orchestration] 编排 {} 从步骤 {} 重试", id, from);
        // 复用主执行流程（重新走一遍 runAsync，跳过已成功的步骤：直接从 from 开始）
        runFromStep(id, tenantId, from);
    }

    /** 从指定步骤开始执行（runAsync 的变体，跳过前面已成功的步骤）。 */
    void runFromStep(Long id, Long tenantId, int fromStep) {
        OrchestrationState state = stateMap.get(id);
        if (state == null) return;
        TenantContext.set(tenantId);
        state.setStatus("running");
        // T22 复用注入快照：内存态已有（首次编排解析过）则直接复用同一份；缺失（重启恢复的旧记录，
        // governanceContext 不落库）则重解析一次重建——注入内容全局稳定，重解析结果一致，选简单实现
        if (state.getGovernanceContext() == null) {
            resolveGovernanceInjection(state, tenantId);
        }
        // 从产物重建 upstreamArtifacts（前面已成功步骤的产出）
        Map<String, String> upstreamArtifacts = rebuildUpstreamFromArtifacts(state, fromStep);
        List<OrchestrationState.StepResult> steps = state.getSteps();
        int total = steps.size();
        log.info("[Orchestration] 重试执行 id={}, 从步骤 {}/{}", id, fromStep, total);

        for (int i = fromStep - 1; i < total; i++) {
            executeStep(state, steps.get(i), i, total, upstreamArtifacts, tenantId);
        }

        state.setStatus("done");
        state.setCurrentRole(null);
        state.setFinishedAt(LocalDateTime.now());
        persistState(state, tenantId);
        TenantContext.clear();
        log.info("[Orchestration] 重试完成 id={}", id);
    }

    /** 从 t_artifact 重建前序步骤的 upstreamArtifacts（重试场景）。 */
    private Map<String, String> rebuildUpstreamFromArtifacts(OrchestrationState state, int fromStep) {
        Map<String, String> upstream = new HashMap<>();
        List<OrchestrationState.StepResult> steps = state.getSteps();
        for (int i = 0; i < fromStep - 1 && i < steps.size(); i++) {
            OrchestrationState.StepResult sr = steps.get(i);
            if (!"success".equals(sr.getStatus())) continue;
            // 从工作区文件读回产出（ArtifactFileService 落地的 {role}/{role}.md）
            try {
                var roleMd = java.nio.file.Paths.get(
                        System.getProperty("user.dir"), "workspaces", state.getCaseId(), sr.getRole(),
                        sr.getRole() + ".md");
                if (java.nio.file.Files.exists(roleMd)) {
                    String out = java.nio.file.Files.readString(roleMd);
                    if (out.length() > 4000) out = out.substring(0, 4000) + "\n... (已截断)";
                    upstream.put(sr.getRoleLabel() + "的" + sr.getArtifactType(), out);
                }
            } catch (Exception e) {
                log.warn("[Orchestration] 重建上游产出失败 step={}: {}", i + 1, e.getMessage());
            }
        }
        return upstream;
    }

    /**
     * 执行单个编排步骤（runAsync 主循环与 runFromStep 共用的核心逻辑）。
     *
     * <p>与主流程差异：重试路径不触发检查点（部署审批只在首次编排流走），
     * 不触发 Git/CI 收尾（收尾由调用方在全部步骤后统一处理——主流程已有，重试流程已有）。</p>
     */
    private void executeStep(OrchestrationState state, OrchestrationState.StepResult stepResult,
                             int i, int total, Map<String, String> upstreamArtifacts, Long tenantId) {
        String role = stepResult.getRole();
        String roleLabel = stepResult.getRoleLabel();
        String artifactType = stepResult.getArtifactType();
        state.setCurrentRole(role);
        stepResult.setStatus("running");
        stepResult.setStartedAt(LocalDateTime.now());
        try {
            AgentDefinition agent = capabilityLoader.getAgent(role);
            if (agent == null) throw new RuntimeException("角色未注册: " + role);
            String instruction = (stepResult.getInstruction() != null && !stepResult.getInstruction().isBlank())
                    ? stepResult.getInstruction()
                    : "这是流水线编排的第 " + (i + 1) + " 步（共 " + total + " 步）。请基于需求和上游产出，产出你的专业内容。";
            // 门禁步骤追加判定格式要求（T19：与主流程同一 SUFFIX，保证重试产物形状含 GATE 判定；
            // 重试路径不解析判定/不等待审批——既有断点续跑语义，不在本次等价改造范围内变更）
            if ("llm_review".equals(stepResult.getGateType())) {
                instruction += GATE_INSTRUCTION_SUFFIX;
            }
            DerivationContext ctx = DerivationContext.builder()
                    .task(state.getRequirement())
                    .stage(artifactType)
                    .upstreamArtifacts(upstreamArtifacts.isEmpty() ? null : new HashMap<>(upstreamArtifacts))
                    .extraInstructions(instruction)
                    .governanceContext(state.getGovernanceContext())   // T22：重试路径同样复用注入快照
                    .build();
            log.info("[Orchestration] 步骤 {}/{} 派生: role={}, case={}", i + 1, total, role, state.getCaseId());
            DerivationEngine.DerivationResult result = engine.derive(agent, state.getRequirement(), state.getCaseId(), ctx);
            if (result != null && result.getOutput() != null) {
                String output = result.getOutput();
                if (output.length() > 4000) output = output.substring(0, 4000) + "\n... (已截断)";
                upstreamArtifacts.put(roleLabel + "的" + artifactType, output);
            }
            stepResult.setStatus("success");
            stepResult.setFinishedAt(LocalDateTime.now());
            persistState(state, tenantId);
            // 产出落地
            if (result != null && result.getOutput() != null) {
                try {
                    artifactFileService.writeToWorkspace(state.getCaseId(), role, result.getOutput());
                } catch (Exception fe) {
                    log.warn("[Orchestration] 产出落地失败（不阻塞）: role={}", role, fe);
                }
            }
            if (i < total - 1 && stepIntervalMs > 0) Thread.sleep(stepIntervalMs);
        } catch (Throwable t) {
            String errMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            stepResult.setStatus("failed");
            stepResult.setError(errMsg);
            stepResult.setFinishedAt(LocalDateTime.now());
            log.error("[Orchestration] 步骤 {}/{} 失败: role={}, error={}", i + 1, total, role, errMsg, t);
            try {
                if (i < total - 1 && stepIntervalMs > 0) Thread.sleep(stepIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 审批决定。 */
    enum ApprovalDecision { APPROVED, REJECTED, TIMEOUT }

    /**
     * 创建检查点并等待人工审批（可靠性治理：不可逆操作人工锁）。
     *
     * <p>T20 门禁规则化改造：触发条件从"team-ops 角色硬编码"改为 human_approval 规则的
     * 审批闸标记（stepResult.approvalRuleNames 非空），检查点 operation 带规则 stage
     * （orchestration_gate_{stage}_{orchId}），消息注明命中的规则名（SE §6.4）；
     * 等待/超时/降级语义与改造前完全一致（30 分钟轮询、拒绝跳过部署、检查点服务异常降级放行）。</p>
     *
     * <p>编排状态转为 {@code awaiting_approval}，前端轮询可见并提示去检查点审批页操作。
     * 每 5 秒轮询检查点状态，最长等 {@code approval-timeout-ms}（默认 30 分钟）。</p>
     *
     * <p><b>实现权衡</b>：当前用线程阻塞等待（占编排线程），MVP 可接受
     * （orchestrationExecutor core=2）。生产版应改为"暂停/恢复"模式
     * （编排状态落库 + 审批回调触发继续），配合编排持久化一起做。</p>
     */
    private ApprovalDecision awaitApproval(OrchestrationState state, OrchestrationState.StepResult stepResult) {
        try {
            String stage = stepResult.getGateStage() != null ? stepResult.getGateStage() : "pre_deploy";
            // 1. 创建 pending 检查点（operation 带规则 stage，SE §6.4：orchestration_gate_{stage}_{orchId}）
            Checkpoint cp = checkpointService.create(
                    state.getCaseId(), "orchestration_gate_" + stage + "_" + state.getId(), null);

            // 2. 编排状态 → awaiting_approval（前端可见），消息注明命中的门禁规则名
            state.setStatus("awaiting_approval");
            state.setPendingCheckpointId(cp.getId());
            state.setApprovalMessage("命中人工审批门禁规则：" + stepResult.getApprovalRuleNames()
                    + "。部署为不可逆操作，等待人工审批（检查点 #" + cp.getId()
                    + "）。请到「检查点审批」页面确认或拒绝。30 分钟无审批自动跳过。");
            persistState(state, TenantContext.get() != null ? TenantContext.get() : 0L);   // 审批等待持久化
            log.info("[Orchestration] 等待部署审批 id={}, checkpointId={}", state.getId(), cp.getId());

            // 3. 轮询等待（5 秒一次）
            long deadline = System.currentTimeMillis() + approvalTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5000);
                Checkpoint cur = checkpointService.getById(cp.getId());
                if (cur == null) continue;
                String st = cur.getStatus();
                if (CheckpointService.STATUS_CONFIRMED.equals(st)) {
                    // 审批通过 → 恢复运行
                    state.setStatus("running");
                    state.setPendingCheckpointId(null);
                    state.setApprovalMessage(null);
                    log.info("[Orchestration] 部署审批通过 id={}", state.getId());
                    return ApprovalDecision.APPROVED;
                }
                if (CheckpointService.STATUS_REJECTED.equals(st)) {
                    state.setPendingCheckpointId(null);
                    state.setApprovalMessage(null);
                    log.info("[Orchestration] 部署审批被拒 id={}", state.getId());
                    return ApprovalDecision.REJECTED;
                }
                // 仍 pending → 继续等
            }
            // 超时
            state.setPendingCheckpointId(null);
            state.setApprovalMessage(null);
            log.warn("[Orchestration] 部署审批超时 id={}", state.getId());
            return ApprovalDecision.TIMEOUT;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.setPendingCheckpointId(null);
            state.setApprovalMessage(null);
            return ApprovalDecision.TIMEOUT;
        } catch (Exception e) {
            // 检查点服务异常：降级放行（不能因审批基础设施故障卡死编排）并 log 告警
            log.error("[Orchestration] 检查点服务异常，降级放行部署 id={}", state.getId(), e);
            state.setStatus("running");
            state.setPendingCheckpointId(null);
            state.setApprovalMessage(null);
            return ApprovalDecision.APPROVED;
        }
    }
}
