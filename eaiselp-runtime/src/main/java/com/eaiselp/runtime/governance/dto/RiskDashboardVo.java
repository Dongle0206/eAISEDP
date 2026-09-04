package com.eaiselp.runtime.governance.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 风险看板聚合 VO（case-20260821 T12，契约 R7；纯只读聚合，无新表）。
 *
 * <p><b>口径</b>（AC-F1.12~F1.14，均排除 closed）：cells 恰 25 格（X=影响/Y=概率由
 * (probability, impact) 元组自描述，前端轴写死）；levelDistribution 四档计数由
 * (P,I)→等级推导；highRisks = level∈{high,critical} 未 closed 清单
 * （riskValue DESC, id DESC，overdue 透传）。</p>
 */
@Data
public class RiskDashboardVo {

    /** 5×5 热力图格子（恰 25 格，P1~I5 全组合，未命中格 count=0） */
    private List<Cell> cells;

    /** 等级分布：{low, medium, high, critical} 四档计数（未 closed 口径） */
    private Map<String, Long> levelDistribution;

    /** 高风险清单（level∈{high,critical} 未 closed，riskValue DESC, id DESC） */
    private List<HighRisk> highRisks;

    /** 热力图格子（契约 R7 cells 记录结构；count=该 (P,I) 组合未 closed 风险数）。 */
    @Data
    public static class Cell {

        /** 概率 1~5（Y 轴） */
        private Integer probability;

        /** 影响 1~5（X 轴） */
        private Integer impact;

        /** 该格风险值 = probability×impact（1~25） */
        private Integer riskValue;

        /** 该格等级（四段闭区间映射，格子底色四档色阶依据） */
        private String riskLevel;

        /** 该格未 closed 风险计数（AC-F1.12：closed 不计入看板） */
        private Long count;
    }

    /** 高风险清单条目（契约 R7 highRisks 记录结构）。 */
    @Data
    public static class HighRisk {

        private Long id;

        /** 风险名 */
        private String riskName;

        private Integer probability;

        private Integer impact;

        private Integer riskValue;

        /** high / critical */
        private String riskLevel;

        /** 风险责任人 */
        private String owner;

        /** open / mitigating（closed 不入清单） */
        private String status;

        /** 复评日期（可空） */
        private LocalDate reviewDate;

        /** 逾期标识（服务端判定透传，D-10） */
        private Boolean overdue;
    }
}
