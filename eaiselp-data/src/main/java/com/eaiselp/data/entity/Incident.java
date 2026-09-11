package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 服务事故台账实体（case-20260823-商用化 V8，F3.2；t_incident 租户级，轻量无状态机）。
 *
 * <p>恢复态为展示口径（recovery_time 非空=已恢复），无状态机（PRD §4.8 裁决）；
 * 编辑校验 recovery_time ≥ occurred_time（倒挂 400，防脏数据）。
 * sla_breach 为登记时人工违约判定台账标记（纯记录，不触发任何计算/赔付，§7-4/7-5）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_incident")
public class Incident extends BaseEntity {

    /** 事故标题（必填 ≤200） */
    private String title;

    /** 发生时间（必填；列表默认倒序键） */
    private LocalDateTime occurredTime;

    /** 恢复时间（可空——空=未恢复，非空=已恢复为展示口径，AC-F3.2 补记语义） */
    private LocalDateTime recoveryTime;

    /** 影响描述（必填 ≤1000） */
    private String impactDesc;

    /** 根因（可空，恢复后补记——AC-F3.2） */
    private String rootCause;

    /** SLA 违约标记：0=未违约(默认)/1=违约（登记时人工判定，纯台账 D-4 差异定稿） */
    private Integer slaBreach;
}
