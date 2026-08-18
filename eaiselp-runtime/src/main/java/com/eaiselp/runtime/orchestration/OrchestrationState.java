package com.eaiselp.runtime.orchestration;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 编排任务状态（内存态，轮询查询用）。
 *
 * <p>一条编排任务内含多个流水线步骤，每步是一个单角色派生。
 * 整体状态：pending → running → done（含部分失败）/ failed（全部失败）。</p>
 */
@Data
public class OrchestrationState {

    private Long id;
    private String caseId;
    private String requirement;
    private String tier;

    /** pending / running / awaiting_approval / done / failed */
    private String status;

    /** 当前执行步骤的角色名 */
    private String currentRole;

    /** 等待审批的检查点 ID（status=awaiting_approval 时非空） */
    private Long pendingCheckpointId;

    /** 等待审批的提示信息 */
    private String approvalMessage;

    /** 门禁打回时待注入重跑步骤的审查意见（用后清空） */
    private String pendingGateFeedback;

    /** 流水线步骤列表 */
    private List<StepResult> steps = new ArrayList<>();

    /** 产出验证结果（编排完成后自动运行 CodeValidationService） */
    private CodeValidationSummary validation;

    /**
     * 下行注入留痕（PRJ-002 F7，AC-F7.1 三处留痕之一）：本次编排实际注入的原则 code 清单
     * （List&lt;String&gt; 的 JSON 序列化，GovernanceInjectionService 解析结果）。
     * 批3 T22 接线：runAsync 规划后解析一次写入，persistState 落 t_orchestration.injected_json。
     */
    private String injectedPrinciplesJson;

    /**
     * 治理注入章节文本（T22，SE 决策 D-1）：预渲染的"架构原则与项目约束"最终字符串，
     * 每步构建 DerivationContext 时复用同一份（一次解析、整条编排快照语义）。
     * null = 不注入（场景C/解析失败降级）。内存态字段，不持久化——断点续跑时重解析一次重建
     * （注入内容全局稳定，重解析结果一致，选简单实现；见 runFromStep 注释）。
     */
    private String governanceContext;

    /**
     * 门禁规则快照（T19，SE 决策 D-3）：编排启动一次读全部 enabled 规则（不可变 List），
     * 整个编排生命周期（含打回回跳/断点续跑）只用快照——规则修改对"新编排"立即生效、
     * 对"在跑编排"零影响。内存态字段，不持久化：重启恢复的旧记录按步骤已标注的 gate 属性
     * （steps_json 内 gateRuleId/gateType 等）执行，等价旧行为（SE §11 R2）。
     */
    private List<GateRuleSnapshot> gateRules;

    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    /** 步骤总数 */
    public int totalSteps() {
        return steps.size();
    }

    /** 已完成步骤数（success + failed） */
    public int completedSteps() {
        return (int) steps.stream().filter(s -> "success".equals(s.status) || "failed".equals(s.status)).count();
    }

    /** 产出验证摘要（详细逐文件结果在 checks 里）。 */
    @Data
    public static class CodeValidationSummary {
        private boolean allPassed;
        private int totalFiles;
        private int passedFiles;
        private int failedFiles;
        private String validatedAt;
    }

    @Data
    public static class StepResult {
        /** 步骤序号（从 1 开始） */
        private int index;
        /** 角色名（team-po 等） */
        private String role;
        /** 角色中文名 */
        private String roleLabel;
        /** 产物类型（prd/code/review/test/deploy 等） */
        private String artifactType;
        /** pending / running / success / failed / skipped（检查点被拒时跳过） */
        private String status;

        /** 质量门禁判定结果（门禁角色专用）：PASS / FAIL / null（非门禁步骤） */
        private String gateResult;
        /** 门禁 FAIL 时的原因（注入打回重做） */
        private String gateReason;
        /** 该步骤被门禁打回重跑的次数 */
        private int rerunCount;
        /** 失败原因 */
        private String error;
        /** 编排者给这一步的定制指令（智能规划时 LLM 生成） */
        private String instruction;

        // ---- 质量门禁标注（T19/T20 规则驱动，随 steps_json 持久化，重启恢复据此执行门禁，SE R2）----

        /** 命中的门禁规则 ID（规则快照 id；null = 非门禁步骤） */
        private Long gateRuleId;
        /** 门禁类型：llm_review（产出走 GATE:PASS/FAIL 协议）；null = 非门禁步骤 */
        private String gateType;
        /** 门禁挂载阶段（post_dev/post_test/pre_deploy），留痕与审批闸 operation 命名用 */
        private String gateStage;
        /** 规则级重做上限（null = yml eaiselp.orchestration.gate-max-retries 兜底，PRD F6.4） */
        private Integer gateMaxRetries;
        /** 失败动作：block（阻断打回）/ warn（FAIL_WARN 记录放行）；null 视为 block */
        private String gateFailAction;
        /** 人工审批闸命中的规则名（逗号分隔；非空 = 该步骤执行前等待人工审批，同 stage 多条已合并，SE R3） */
        private String approvalRuleNames;

        /** 步骤开始时间 */
        private LocalDateTime startedAt;
        /** 步骤完成时间 */
        private LocalDateTime finishedAt;

        public static StepResult pending(int index, String role, String roleLabel, String artifactType) {
            StepResult s = new StepResult();
            s.index = index;
            s.role = role;
            s.roleLabel = roleLabel;
            s.artifactType = artifactType;
            s.status = "pending";
            return s;
        }

        /** 角色中文名映射（智能规划时前端展示用）。 */
        public static final java.util.Map<String, String> ROLE_LABELS = java.util.Map.ofEntries(
                java.util.Map.entry("team-po", "产品经理(PO)"),
                java.util.Map.entry("team-ux", "体验设计(UX)"),
                java.util.Map.entry("team-ba", "业务分析(BA)"),
                java.util.Map.entry("team-se", "系统工程(SE)"),
                java.util.Map.entry("team-dba", "数据库(DBA)"),
                java.util.Map.entry("team-dev", "开发(Dev)"),
                java.util.Map.entry("team-reviewer", "代码审查"),
                java.util.Map.entry("team-security", "安全审查"),
                java.util.Map.entry("team-qa", "测试(QA)"),
                java.util.Map.entry("team-performance", "性能(Performance)"),
                java.util.Map.entry("team-ops", "运维(Ops)"),
                java.util.Map.entry("team-sre", "可靠性(SRE)"),
                java.util.Map.entry("team-pm", "项目经理(PM)"),
                java.util.Map.entry("team-orchestrator", "编排者")
        );
    }
}
