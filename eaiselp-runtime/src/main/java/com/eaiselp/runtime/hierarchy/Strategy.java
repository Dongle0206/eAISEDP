package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 战略目标实体（L3 层，t_strategy，PRJ-002 T04）。
 *
 * <p>字段与 V4 r2 列一一对应；tenant_id/审计字段/逻辑删继承 BaseEntity，
 * 租户级表走 MyBatis-Plus 租户拦截器（不进 IGNORE_TABLES，SE §11 R4）。
 * 主键 ASSIGN_ID 雪花（BaseEntity 内 @TableId）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_strategy")
public class Strategy extends BaseEntity {

    /** 战略目标标题（≤200，必填） */
    private String title;

    /** 目标描述（背景/价值/衡量标准） */
    private String description;

    /** 时间维度: 1q / 1y / 3y */
    private String horizon;

    /** 生命周期: draft / active / achieved / archived */
    private String status;

    /** KPI 指标 JSON 文本（名称→目标值/当前值/单位）；String 承载 JSON（同 GovernanceLog.detail 惯例） */
    private String kpi;

    /** 战略负责人 */
    private String owner;
}
