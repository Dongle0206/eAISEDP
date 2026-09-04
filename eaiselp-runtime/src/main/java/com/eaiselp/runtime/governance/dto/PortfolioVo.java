package com.eaiselp.runtime.governance.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 投资组合聚合 VO（case-20260821 T13，契约 B8；纯只读聚合，无新表）。
 *
 * <p><b>双口径并存</b>（AC-F2.11/F2.12 同用例双断言，QA 注意区分）：
 * summary 为<b>投资口径</b>（仅 status∈{approved,executing,done}——draft 未决策、
 * rejected 已否决不计钱，裁决 Q8；空集 COALESCE 0）；statusDistribution 为<b>全量五态</b>
 * 口径（分布看流程漏斗、汇总看钱，有意不同）。totalThreeYearNetBenefit = 3×Σnet
 * （与 ROI 3 年口径一致）。</p>
 */
@Data
public class PortfolioVo {

    /** 全量案例分页（含 rejected/done；riceScore DESC, id DESC，AC-F2.10） */
    private IPage<BusinessCaseVo> cases;

    /** 投资口径汇总（仅 approved/executing/done 计入） */
    private Summary summary;

    /** 状态分布（全量五态：draft/approved/rejected/executing/done） */
    private Map<String, Long> statusDistribution;

    /** 投资口径汇总四项（契约 B8 summary 结构；D-2 直接对落库列 Σ）。 */
    @Data
    public static class Summary {

        /** 总一次性投入 = Σ onetime_cost（投资口径） */
        private BigDecimal totalOnetimeCost;

        /** 总年运营成本 = Σ annual_op_cost */
        private BigDecimal totalAnnualOpCost;

        /** 总年化净收益 = Σ net_benefit（可为负，正常展示） */
        private BigDecimal totalAnnualNetBenefit;

        /** 总 3 年净收益 = 3×Σ net_benefit（与 ROI 3 年口径一致） */
        private BigDecimal totalThreeYearNetBenefit;
    }

    /** 状态分布聚合行（Mapper GROUP BY 承载，Service 汇入全量五态 Map）。 */
    @Data
    public static class StatusCount {

        /** draft/approved/rejected/executing/done */
        private String status;

        /** 该状态案例数 */
        private Long cnt;
    }
}
