package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 项目群实体（L2 层，t_program，PRJ-002 T04）。
 *
 * <p>字段与 V4 r2 列一一对应；strategyId 可空 = P13 场景B 从 L2 接入（不强关联战略）。
 * 租户级表走拦截器，不进 IGNORE_TABLES。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_program")
public class Program extends BaseEntity {

    /** 关联战略（L3→L2 联动；可空 = P13 场景B 从 L2 接入） */
    private Long strategyId;

    /** 项目群名称（必填） */
    private String name;

    /** 项目群章程（下行约束：从战略目标继承的目标/边界） */
    private String charter;

    /** 状态: planning / active / suspended / closed */
    private String status;

    /** 开始日期（DATE 列） */
    private LocalDate startDate;

    /** 结束日期（DATE 列） */
    private LocalDate endDate;

    /** 项目群经理 */
    private String pgmManager;
}
