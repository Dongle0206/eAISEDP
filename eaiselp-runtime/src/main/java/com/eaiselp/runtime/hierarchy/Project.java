package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目实体（L2 层，t_project，PRJ-002 T04）。
 *
 * <p>字段与 V4 r2 列一一对应；programId 可空 = 独立项目（P13 不强制挂群）。</p>
 *
 * <p><b>进度三列防手工覆盖（AC-F3.2 双保险之一）</b>：progress/caseTotal/caseDone 标记
 * {@code updateStrategy = NEVER}——实体驱动的 updateById 永不携带这三列，只能由
 * ProjectProgressService 的全量重算（LambdaUpdateWrapper 显式 set，不受字段策略约束，
 * SE §5.3）写入；Controller 层忽略请求字段是第二道保险。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_project")
public class Project extends BaseEntity {

    /** 所属项目群（L2 内层级；可空 = 独立项目） */
    private Long programId;

    /** 项目名称（必填） */
    private String name;

    /** 项目描述/约束（非空时下行注入 Case 编排，见 F7） */
    private String description;

    /** 状态: planning / in_progress / delivered / closed */
    private String status;

    /** 优先级 1(高)-9(低)，默认 5 */
    private Integer priority;

    /** 进度百分比 0-100（Case 完成自动上行汇总，页面只读；updateStrategy=NEVER 防手工覆盖，AC-F3.2） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer progress;

    /** Case 总数（自动统计，F8 汇总算法；updateStrategy=NEVER） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer caseTotal;

    /** 已完成 Case 数（自动统计，F8 汇总算法；updateStrategy=NEVER） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer caseDone;
}
