package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目群 VO（PRJ-002 T24，SE §8.2 GET /api/v1/programs 分页/详情共用契约）。
 *
 * <p>列表形态：projects 为 null，仅携带实时聚合的 projectCount/avgProgress；
 * 详情形态：额外填充 projects 成员项目列表（章程全文 + 项目列表 + 进度均值，AC-F2.2）。</p>
 */
@Data
public class ProgramVo {

    private Long id;
    /** 关联战略（可空 = 场景B 从 L2 接入，P13 灵活接入） */
    private Long strategyId;
    private String name;
    /** 项目群章程（下行约束：从战略目标继承的目标/边界） */
    private String charter;
    /** planning / active / suspended / closed */
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private String pgmManager;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 成员项目数（实时聚合） */
    private Integer projectCount;
    /** 成员项目进度算术平均向下取整 ⌊Σprogress/n⌋（无成员为 0，AC-F8.5） */
    private Integer avgProgress;
    /** 成员项目列表（仅详情形态填充；列表形态为 null） */
    private List<ProjectItem> projects;

    /** 群内成员项目条目（进度三列只读展示，AC-F3.2） */
    @Data
    public static class ProjectItem {
        private Long id;
        private String name;
        /** planning / in_progress / delivered / closed */
        private String status;
        private Integer priority;
        private Integer progress;
        private Integer caseTotal;
        private Integer caseDone;
    }
}
