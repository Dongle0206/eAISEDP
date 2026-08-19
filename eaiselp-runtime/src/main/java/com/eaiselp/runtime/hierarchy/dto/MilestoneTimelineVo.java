package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 里程碑时间线条目 VO（case-20260818 T9，字段名=api-contracts §2 契约）。
 *
 * <p>单项目时间线 / 项目群聚合时间线 / 总览分页共用；群聚合时 ownerLevel 区分层级
 * （program=群直属 / project=成员项目，AC-F2.6）；statusColor 前端枚举常量集中定义
 * （planned=blue / achieved=green / delayed=red），overdue=黄角标（展示层派生，系统不改状态）。</p>
 */
@Data
public class MilestoneTimelineVo {

    private Long id;

    /** 用户可见编号（MS-0001） */
    private String milestoneCode;

    /** program / project */
    private String ownerType;
    private Long ownerId;

    /** 层级标签（群聚合视图区分群直属/成员项目；单项目视图=ownerType 同值） */
    private String ownerLevel;

    /** 归属对象名（项目群名/项目名，群聚合视图展示） */
    private String ownerName;

    private String title;
    private String description;
    private LocalDate targetDate;
    /** 负责人 */
    private String owner;
    /** planned / achieved / delayed */
    private String status;
    /** planned=blue / achieved=green / delayed=red */
    private String statusColor;
    /** targetDate&lt;今天 且 status=planned → true（黄角标，展示层实时判定不落库，AC-F2.3） */
    private Boolean overdue;
    private LocalDate achievedDate;
    private String blocker;
    /** 群级涉及项目多选（仅展示） */
    private String subprojects;
    private LocalDateTime createTime;

    /** 前端枚举常量（服务端同口径计算，集中一处防散落）：planned=blue / achieved=green / delayed=red */
    public static final List<String> STATUS_COLORS = List.of("blue", "green", "red");

    /** 状态→展示色（前端常量集中定义的服务端镜像，异常状态回退 blue）。 */
    public static String colorOf(String status) {
        if ("achieved".equals(status)) {
            return "green";
        }
        if ("delayed".equals(status)) {
            return "red";
        }
        return "blue";
    }
}
