package com.eaiselp.runtime.orchestration;

/**
 * 门禁规则快照（PRJ-002 T19，SE §6.2 / 决策 D-3）：编排启动时由
 * {@code QualityGateRuleService.listEnabledByStage(null)} 一次读全部启用规则映射而来，
 * 不可变 record，整个编排生命周期（含门禁打回回跳、断点续跑）只消费本快照。
 *
 * <p><b>为什么是编排包内纯数据类而非复用 hierarchy 实体</b>（P12/P3 单向依赖）：
 * {@code OrchestrationState} 会被序列化为 API 响应与内存态持有，若直接持有
 * {@code QualityGateRule} 实体则 L1 编排状态反向依赖 L2 实体类——实体加删字段会波及编排层
 * 序列化兼容。本 record 字段集是编排消费的最小闭包（判定/打回/审批/收尾检查所需全部字段），
 * 实体 → 快照的映射收口在 {@link #from} 一处。</p>
 *
 * <p>不做运行期缓存：每次编排启动一条 SELECT（idx_gate_tenant_enabled 命中），
 * 换取消除缓存一致性问题——规则改完对"新编排"立即生效、对"在跑编排"零影响（AC-F6.2 快照语义）。</p>
 *
 * @param id         规则 ID（t_quality_gate_rule.id，留痕到 StepResult.gateRuleId）
 * @param name       规则名（插入门禁步骤的 instruction 与审批闸消息引用）
 * @param gateType   门禁类型：llm_review / human_approval / auto_check
 * @param gateRole   llm_review 的门禁角色（team-reviewer 等）；其余类型为 null
 * @param checkKey   auto_check 的检查项（code_validation 等）；其余类型为 null
 * @param appliesTo  生效范围（all/code/design/doc，本期不参与分派，快照留痕）
 * @param stage      挂载阶段：post_dev / post_test / pre_deploy
 * @param maxRetries 门禁 FAIL 打回重做上限（null = yml 兜底，PRD F6.4）
 * @param failAction 失败动作：block / warn（空值视为 block）
 * @param priority   同阶段执行顺序（小者先）
 */
public record GateRuleSnapshot(
        Long id,
        String name,
        String gateType,
        String gateRole,
        String checkKey,
        String appliesTo,
        String stage,
        Integer maxRetries,
        String failAction,
        int priority) {

    /** 实体 → 快照映射（编排启动一次；唯一的 hierarchy 实体引用点，收口于此）。 */
    static GateRuleSnapshot from(com.eaiselp.runtime.hierarchy.QualityGateRule r) {
        return new GateRuleSnapshot(
                r.getId(),
                r.getName(),
                r.getGateType(),
                r.getGateRole(),
                r.getCheckKey(),
                r.getAppliesTo(),
                r.getStage(),
                r.getMaxRetries(),
                r.getFailAction(),
                r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority());
    }

    /** 失败动作是否为告警放行（非 warn 一律按 block 处理，含空值兜底）。 */
    public boolean isWarnAction() {
        return "warn".equalsIgnoreCase(failAction);
    }

    /** 是否为 llm_review 门禁。 */
    public boolean isLlmReview() {
        return "llm_review".equalsIgnoreCase(gateType);
    }

    /** 是否为人工审批门禁。 */
    public boolean isHumanApproval() {
        return "human_approval".equalsIgnoreCase(gateType);
    }

    /** 是否为自动检查门禁。 */
    public boolean isAutoCheck() {
        return "auto_check".equalsIgnoreCase(gateType);
    }
}
