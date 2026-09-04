package com.eaiselp.runtime.governance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商业案例视图 VO（case-20260821 T4，字段名=api-contracts §3 契约）。
 *
 * <p><b>N/A 语义</b>（契约 B3 注）：paybackYears/roiPercent 为 <b>null = N/A</b>——
 * 两种 N/A 来源不同（payback：net≤0 不可投；roi：onetime=0 除零防御）；
 * onetime=0 且 net&gt;0 → paybackYears=<b>0.0 非 null</b>（零成本，AC-F2.2/F2.3）。
 * roiPercent 为百分数值（20.00 = 20.00%，带 % 展示由前端拼）——字段名来自 V7 列
 * roi_percent（差异定稿）。</p>
 */
@Data
public class BusinessCaseVo {

    private Long id;

    /** 案例名 */
    private String caseName;

    /** 案例描述 */
    private String description;

    /** 关联战略 id 列表（JSON 解析还原，可空） */
    private List<Long> relatedStrategyIds;

    /** 关联战略列表（仅详情接口填充解析态；列表为 null） */
    private List<RelatedStrategyVo> relatedStrategies;

    /** 一次性成本（元） */
    private BigDecimal onetimeCost;

    /** 年运营成本（元） */
    private BigDecimal annualOpCost;

    /** 量化收益/年（元） */
    private BigDecimal annualBenefit;

    /** 年净收益（计算列回显，可负） */
    private BigDecimal netBenefit;

    /** 回收期（计算列回显；null=N/A；0.0=零成本） */
    private BigDecimal paybackYears;

    /** ROI 百分数值（计算列回显；null=N/A；负值合法） */
    private BigDecimal roiPercent;

    /** RICE 评分（计算列回显，[0.01,100.00]） */
    private BigDecimal riceScore;

    /** RICE 触达 1~10 */
    private Integer reach;

    /** RICE 影响 1~10 */
    private Integer impact;

    /** RICE 信心（0.1~1.0 十档） */
    private BigDecimal confidence;

    /** RICE 投入 1~10 */
    private Integer effort;

    /** draft/approved/rejected/executing/done */
    private String status;

    /** 拒绝原因（仅 rejected 态非空） */
    private String rejectedReason;

    /** 决策记录（随流转可更新） */
    private String decisionNote;

    private String createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
