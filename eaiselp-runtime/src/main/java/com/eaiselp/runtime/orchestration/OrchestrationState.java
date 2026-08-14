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

    /** pending / running / done / failed */
    private String status;

    /** 当前执行步骤的角色名 */
    private String currentRole;

    /** 流水线步骤列表 */
    private List<StepResult> steps = new ArrayList<>();

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
        /** pending / running / success / failed */
        private String status;
        /** 失败原因 */
        private String error;
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
    }
}
