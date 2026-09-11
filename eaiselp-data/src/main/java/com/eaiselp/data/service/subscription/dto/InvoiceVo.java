package com.eaiselp.data.service.subscription.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 账单 VO（I1/C2 列表行；字段名按 V8 列名定稿 D-2：planPrice/usageTokens/overageTokens/
 * overageAmount/prorationAmount/totalAmount——非 SE 方案 §3.2 的 amountDue/baseFee）。
 * 恒等式：totalAmount = (prorationAmount > 0 ? prorationAmount : planPrice) + overageAmount。
 * 信息隔离（§8.4）：本 VO 任何字段禁止承载 t_derivation.cost。
 */
@Data
public class InvoiceVo {
    private Long id;
    private Long tenantId;
    /** 账期键 yyyy-MM */
    private String period;
    private String planCode;
    private BigDecimal planPrice;
    private Long usageTokens;
    private Long overageTokens;
    private BigDecimal overageAmount;
    /** 首月折算实收月费（非折算账期 0.00） */
    private BigDecimal prorationAmount;
    private BigDecimal totalAmount;
    /** draft/issued/paid */
    private String status;
    private String issuedTime;
    private String paidTime;
    /** 生成来源（定时=billing-scheduler / 手动补生成=操作者账号） */
    private String createBy;
    private String createTime;
}
