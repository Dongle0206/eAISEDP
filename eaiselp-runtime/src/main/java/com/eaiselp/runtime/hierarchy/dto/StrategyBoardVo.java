package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

import java.util.List;

/**
 * 战略看板聚合 VO（PRJ-002 T23，SE §8.2 GET /api/v1/strategies/{id}/board 响应契约）。
 *
 * <p>聚合不落库（PRD §7.11：展示层实时聚合）——KPI 取自战略行本身，
 * 关联项目群列表逐群携带成员项目数与群内进度均值 ⌊Σprogress/n⌋，
 * 顶层 avgProgress 为各群均值的再平均 ⌊Σ群均值/m⌋（AC-F8.5 双层进度均值）。</p>
 */
@Data
public class StrategyBoardVo {

    private Long id;
    private String title;
    private String description;
    /** 生命周期: draft / active / achieved / archived */
    private String status;
    /** 时间维度: 1q / 1y / 3y */
    private String horizon;
    private String owner;
    /** KPI 指标 JSON 文本（原样透传，前端 JSON.parse 渲染；Q5 裁决：当前值人工维护不自动回写） */
    private String kpi;
    /** 双层进度均值：各关联项目群进度均值的再平均（⌊Σ/m⌋；无群为 0） */
    private Integer avgProgress;
    private List<ProgramItem> programs;

    /** 看板上的项目群卡片（点击下钻 program-detail） */
    @Data
    public static class ProgramItem {
        private Long id;
        private String name;
        /** planning / active / suspended / closed */
        private String status;
        /** 成员项目数 */
        private Integer projectCount;
        /** 群内成员项目进度均值 ⌊Σprogress/n⌋（无成员项目为 0） */
        private Integer avgProgress;
    }
}
