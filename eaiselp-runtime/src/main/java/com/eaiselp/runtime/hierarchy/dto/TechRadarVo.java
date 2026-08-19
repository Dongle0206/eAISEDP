package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 技术雷达视图 VO（case-20260818 T9，字段名=api-contracts §5 契约）。
 *
 * <p>API 语义名 name/reviewedAt ↔ V5 列 tech_name/reviewed_at（C4，Service 层换名）；
 * pendingReview 为展示层派生标记（reviewedAt &lt; 今天−180 天 → true），不落库不阻塞任何
 * 操作（AC-F5.4）。四象限分组（quadrant→items）由 Service 组装 Map 承载，本 VO 保持行形态。</p>
 */
@Data
public class TechRadarVo {

    private Long id;

    /** 技术项名称（V5 列 tech_name） */
    private String name;

    /** techniques / tools / platforms / languages */
    private String quadrant;

    /** adopt / trial / assess / hold */
    private String ring;

    /** 定环理由 */
    private String reason;

    /** 评审日期（V5 列 reviewed_at） */
    private LocalDate reviewedAt;

    private String remark;

    /** 待复审角标（距今>180 天），展示层派生不阻塞操作 */
    private Boolean pendingReview;
}
