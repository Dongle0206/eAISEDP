package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

/**
 * DORA 效能看板聚合 VO（case-20260818 T9，字段名=api-contracts §1 契约，AC-F1.1~F1.6 断言载体）。
 *
 * <p>口径唯一权威 = PRD §4.1.2、落地规则 = SE §4.3；四指标卡 + 打回率参考值 + 空态文案。
 * 指标独立降级：任一卡数据源异常（steps_json 解析失败等）只影响该卡（parseErrorCount 表达），
 * 绝不 500 整页（PRD §6.3 兜底铁律）；空态时四卡与打回率全 null、emptyState 给引导文案。</p>
 *
 * <p>异常降级 null 的字段：emptyState 非空时四个指标卡与 gateReworkRate 均为 null；
 * sampleCount=0 的卡 value/p50/p90 为 null（有卡无值，前端按"暂无样本"渲染）。</p>
 */
@Data
public class DoraBoardVo {

    /** 统计维度：project / program / all */
    private String scope;

    /** 维度对象 ID（scope=all 时 null） */
    private Long scopeId;

    /** 统计周期（自然日）：7 / 30 / 90 */
    private Integer periodDays;

    /** 部署频率卡（DF：周期内首次流转 done 的去重 Case 数 ÷ periodDays） */
    private DeploymentFrequencyCard deploymentFrequency;

    /** 变更前置时间卡（LT：doneTs−create_time 的 P50 插值 / P90 序位） */
    private LeadTimeCard leadTime;

    /** 变更失败率卡（CFR 代理指标：门禁终判失败两源分子 ÷ 终态编排 Case 去重数） */
    private ChangeFailureRateCard changeFailureRate;

    /** 恢复时间卡（RT：埋点精确 / 无埋点≈近似，approximateCount&gt;0 前端加"≈"角标） */
    private TimeToRestoreCard timeToRestore;

    /** 门禁打回率（参考值，非四指标；终态 Case 存在过 FAIL 但未终判失败者占比） */
    private GateReworkRateCard gateReworkRate;

    /** 空态：null=有数据；"先创建项目并关联 Case"=无项目；"暂无统计数据，完成 Case 后自动生成"=有项目无数据 */
    private String emptyState;

    /** 部署频率卡（AC-F1.1：3/30=0.1 次/天）。 */
    @Data
    public static class DeploymentFrequencyCard {
        /** 次/天（N/periodDays，无数据天计 0） */
        private Double value;
        private String unit = "次/天";
        /** 分档：elite/high/medium/low（服务端按分档表计算，前端常量集中一处镜像渲染） */
        private String band;
        /** 周期内首次流转 done 的去重 Case 数 N */
        private Integer sampleCount;
    }

    /** 变更前置时间卡（AC-F1.2/F1.5：P50 线性插值 PERCENTILE.INC / P90 向上取整序位）。 */
    @Data
    public static class LeadTimeCard {
        /** P50 小时（线性插值，[24h,48h]→36.0） */
        private Double p50Hours;
        /** P90 小时（ceil(0.9N) 序位取值，保守取大，N=2→48.0） */
        private Double p90Hours;
        /** 汇总展示串（"P50 36h / P90 48h"） */
        private String display;
        private Integer sampleCount;
        /** t_case.status=done 但无 case_transit 审计的历史 Case 数 M（严禁 update_time 冒充） */
        private Integer excludedCount;
        /** >0 时前端显示"另有 M 条历史数据不可回溯，已排除" */
        private String exclusionNote;
    }

    /** 变更失败率卡（AC-F1.3：两源分子防双计，FAIL_WARN 不计）。 */
    @Data
    public static class ChangeFailureRateCard {
        /** 分子/分母（0.333）；parseErrorCount&gt;0 时该卡前端降级"该项暂不可用" */
        private Double value;
        /** "33.3%" 一位小数百分比展示 */
        private String percentDisplay;
        /** 终态编排 Case 去重数（分母，解析成功者） */
        private Integer sampleCount;
        private String proxyNote = "门禁终判失败口径（代理指标）";
        /** steps_json 解析失败 Case 数（单卡降级表达，不 500 整页） */
        private Integer parseErrorCount;
    }

    /** 恢复时间卡（AC-F1.4/F1.5：埋点精确 45min / 无埋点近似"≈"）。 */
    @Data
    public static class TimeToRestoreCard {
        /** P50 分钟（PERCENTILE.INC 线性插值，同 LT 规则） */
        private Integer p50Minutes;
        /** 均值分钟 */
        private Integer avgMinutes;
        /** 样本数（按 caseId+role 分组计） */
        private Integer sampleCount;
        /** 无 gate_result 埋点的近似样本数（>0 → 前端"≈"角标，D-2 不回填历史） */
        private Integer approximateCount;
    }

    /** 门禁打回率卡（参考值，非四指标）。 */
    @Data
    public static class GateReworkRateCard {
        private Double value;
        private String note = "门禁打回率（参考值，非四指标）";
    }
}
