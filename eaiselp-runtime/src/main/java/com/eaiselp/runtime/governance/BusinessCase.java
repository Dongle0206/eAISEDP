package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 商业案例实体（t_business_case，V7 F2.1 新表，租户级知识资产，不限层，战略投资决策记录，
 * case-20260821 T4）。
 *
 * <p><b>计算列落库且可空</b>（裁决 Q1）：netBenefit/paybackYears/roiPercent/riceScore 由
 * {@link BizCaseCalculator} 在 create/update 写库前重算覆盖——入参 DTO 无此四字段，
 * 客户端伪造值连绑定入口都没有（AC-F2.4 防伪造链）。N/A 统一以 NULL 表示：
 * payback 的 net≤0（不可投）与 roi 的 onetime=0（除零防御）两种语义来源不同（AC-F2.2/F2.3）。
 * 字段名 roiPercent 来自 V7 列 roi_percent（tasks.md 拆解声明 3 差异定稿，SE 方案旧名 roi
 * 视为被 DBA 终稿覆盖）。</p>
 *
 * <p><b>数值输入字段 BigDecimal 承载</b>（D-4，同 {@link Risk} 纠偏说明）：reach/impact/
 * effort 校验后归一整数值写 INT 列；三金额 ≥0 校验；confidence 0.1 步进离散校验。</p>
 *
 * <p><b>关联承载</b>：relatedStrategyIds 以 JSON 数组 String 承载（存 t_strategy.id——
 * 其无 code 列，裁决 Q4；与 V5 ADR related_principle_codes 存 code 有意不同：被引实体
 * 无稳定业务键）；战略逻辑删后展示层"已删除"占位，计算与流转不受影响（AC-F2.8）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_business_case")
public class BusinessCase extends BaseEntity {

    /** 案例名（必填 ≤200；uk(tenant, case_name)——同名再建被拒 AC-F2.1） */
    private String caseName;

    /** 案例描述（自由文本） */
    private String description;

    /** 关联战略 id 数组 JSON String（[1,5]，可空多选；存在性校验在 Service） */
    private String relatedStrategyIds;

    /** 一次性成本（单位元，裁决 Q2；≥0 校验，=0 合法触发边界态 AC-F2.5） */
    private BigDecimal onetimeCost;

    /** 年运营成本（元，≥0） */
    private BigDecimal annualOpCost;

    /** 量化收益/年（元，≥0） */
    private BigDecimal annualBenefit;

    /** 年净收益=annualBenefit−annualOpCost（计算列，可负；汇总 Σ 直接用列，D-2） */
    private BigDecimal netBenefit;

    /** 回收期（计算列，1 位 HALF_UP；NULL=N/A——net≤0 含 0；onetime=0 且净&gt;0 → 0.0） */
    private BigDecimal paybackYears;

    /** ROI 3 年口径百分数值（计算列，2 位 HALF_UP，20.00=20.00%；NULL=N/A——onetime=0 除零防御） */
    private BigDecimal roiPercent;

    /** RICE 触达 1~10（BigDecimal 承载整数性+区间校验在 Service，D-4；DB INT） */
    private BigDecimal reach;

    /** RICE 影响 1~10（同上；与 t_risk.impact 同名不同义，表内自洽） */
    private BigDecimal impact;

    /** RICE 信心（0.1 步进离散恰 10 档 0.1~1.0，0.05/0.15/0.85 → 400，AC-F2.4） */
    private BigDecimal confidence;

    /** RICE 投入 1~10（≥1 恒正，riceScore 无除零路径；DB INT） */
    private BigDecimal effort;

    /** RICE 评分（计算列，2 位 HALF_UP，值域 [0.01,100.00]；组合视图降序排序直接用列） */
    private BigDecimal riceScore;

    /** 状态: draft/approved/rejected/executing/done（状态机在 {@link BizCaseStatus}） */
    private String status;

    /** 拒绝原因（rejected 必填——流转校验缺失 400 AC-F2.6；其余状态为 NULL） */
    private String rejectedReason;

    /** 决策记录（自由文本随流转可更新；approved/executing 输入只读期仍可经 B6 更新，AC-F2.7） */
    private String decisionNote;
}
