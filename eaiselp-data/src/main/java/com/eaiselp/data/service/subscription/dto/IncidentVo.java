package com.eaiselp.data.service.subscription.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 事故 VO（N1~N4 出参；AC-F3.2/F3.3）。recovered 布尔由服务端按 recoveryTime 判定
 * （recovery_time 非空=已恢复，展示口径无状态机）；slaBreach 为 V8 增列（D-4，纯台账）。
 */
@Data
@Builder
public class IncidentVo {
    private Long id;
    private String title;
    /** yyyy-MM-dd HH:mm:ss */
    private String occurredTime;
    /** null=未恢复 */
    private String recoveryTime;
    /** 服务端判定：recoveryTime 非空 */
    private boolean recovered;
    private String impactDesc;
    private String rootCause;
    /** 0=未违约 / 1=违约（登记时人工判定） */
    private Integer slaBreach;
    private String createBy;
    private String createTime;
}
