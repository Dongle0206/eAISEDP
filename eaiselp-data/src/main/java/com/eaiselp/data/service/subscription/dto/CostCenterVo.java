package com.eaiselp.data.service.subscription.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 费用中心汇总 VO（C1 出参，契约 §4）：四维用量实时水位（t_quota 当期行——仅展示非账单口径
 * B5）+ 当前套餐卡（trial/未绑定 → plan=null；SLA 档透传，承诺文案前端 governance-dict.js
 * 集中渲染 D-17）。
 */
@Data
@Builder
public class CostCenterVo {

    /** 四维水位（t_quota 当期行不存在 → quota=null） */
    private QuotaWatermark quota;

    /** 当前套餐卡（null = trial/未绑定） */
    private PlanCard plan;

    @Data
    @Builder
    public static class QuotaWatermark {
        /** 当期 yyyy-MM */
        private String period;
        private Dimension token;
        private Dimension caseDimension;
        private Dimension derivation;
        private StorageDimension storage;
    }

    /** 单维水位（token 维附 alertThreshold——接近/超配额醒目提示按 t_quota.alert_threshold） */
    @Data
    @Builder
    public static class Dimension {
        private long used;
        private long limit;
        /** 仅 token 维返回（%阈值）；其余维 null */
        private Integer alertThreshold;
    }

    @Data
    @Builder
    public static class StorageDimension {
        private long usedMb;
        private long limitMb;
    }

    /** 套餐卡（code/name/monthlyPrice/tokenOveragePrice/slaLevel，契约 C1） */
    @Data
    @Builder
    public static class PlanCard {
        private String code;
        private String name;
        private BigDecimal monthlyPrice;
        private BigDecimal tokenOveragePrice;
        /** bronze/silver/gold（文案前端集中 D-17） */
        private String slaLevel;
    }
}
