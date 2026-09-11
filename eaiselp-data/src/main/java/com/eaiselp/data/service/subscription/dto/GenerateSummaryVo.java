package com.eaiselp.data.service.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 出账汇总 VO（I3 出参；定时任务 summary 日志同构）。幂等重跑断言基线（AC-F2.7）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateSummaryVo {
    /** 新生成账单数（INSERT） */
    private long processed;
    /** B2/B3 跳过数（trial 全程 / 期初 trial 期内转正赠送） */
    private long skippedTrial;
    /** D-13 跳过数（plan_code 空 / 套餐行不可见——无出账依据，不出 0 元账单） */
    private long skippedNoPlan;
    /** draft 重算覆盖数（补记用量后金额更新） */
    private long overwritten;
    /** issued/paid 不覆盖保护数 */
    private long skippedLocked;
    /** 单租户失败清单（不中断全量，D-7） */
    @Builder.Default
    private List<FailedItem> failed = new ArrayList<>();
    /** 账期键（回显） */
    private String period;

    /** 失败项：tenantId + reason（如"超出 DECIMAL(14,2) 域"） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedItem {
        private Long tenantId;
        private String reason;
    }
}
