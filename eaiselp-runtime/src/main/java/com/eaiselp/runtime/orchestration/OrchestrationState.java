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

    /** 流水线步骤列表 */
    private List<StepResult> steps = new ArrayList<>();

    /** 产出验证结果（编排完成后自动运行 CodeValidationService） */
    private CodeValidationSummary validation;

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
        /** 失败原因 */
        private String error;
        /** 编排者给这一步的定制指令（智能规划时 LLM 生成） */
        private String instruction;
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
