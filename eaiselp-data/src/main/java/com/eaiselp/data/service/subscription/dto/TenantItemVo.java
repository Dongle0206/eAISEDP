package com.eaiselp.data.service.subscription.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 平台侧租户列表行 VO（case-20260823-商用化 编排者追加端点 GET /api/v1/tenants；
 * PRD F1.3 租户列表页数据支撑——daysLeft 与 TenantSubscriptionService 同 ceil 口径，
 * planName 为绑定套餐名快照二次查询填充）。
 */
@Data
@Builder
public class TenantItemVo {
    private Long id;
    private String tenantCode;
    private String tenantName;
    private String edition;
    /** 绑定套餐编码（null=未绑定，trial 不绑） */
    private String planCode;
    /** 绑定套餐名快照（行缺失 null） */
    private String planName;
    /** yyyy-MM-dd HH:mm:ss 或 null */
    private String expireTime;
    /** 剩余天数 ceil 口径（仅 trial+未到期+expire 非空；非 trial null）——trial 行红标临期提示数据源 */
    private Integer daysLeft;
    private String status;
}
