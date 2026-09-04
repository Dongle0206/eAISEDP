package com.eaiselp.runtime.governance;

import com.eaiselp.common.exception.BizException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 商业案例计算引擎（case-20260821 T1，SE 方案 D-3：final、私有构造、纯函数、零 Spring/ORM 依赖；
 * 全 BigDecimal 运算 + HALF_UP）。
 *
 * <p><b>唯一口径来源</b>（PRD §4.4.1，QA 断言照构造值表，不得自行发明）：
 * <ul>
 *   <li><b>net_benefit</b> = annual_benefit − annual_op_cost（可负，中间量落库）；</li>
 *   <li><b>payback_years</b> = onetime_cost ÷ net_benefit，保留 1 位 HALF_UP；
 *       <b>两种 N/A 语义分离</b>——net≤0（含 0，防除零，不可投）→ null；
 *       onetime=0 且 net&gt;0（零成本）→ <b>0.0 非 null</b>（AC-F2.2）；</li>
 *   <li><b>roi_percent</b>（3 年口径）= (net×3 − onetime) ÷ onetime × 100，保留 2 位 HALF_UP，
 *       负值合法展示；onetime=0 → null（除零防御，与 payback 的 net≤0 N/A 语义来源不同，AC-F2.3）；</li>
 *   <li><b>rice_score</b> = reach × impact × confidence ÷ effort，保留 2 位 HALF_UP
 *       （effort≥1 恒正无除零路径；值域 [0.01,100.00]，AC-F2.4）；</li>
 *   <li><b>confidence 离散</b>：0.1 步进恰 10 档（0.1~1.0）；0.05/0.15/0.85（合法小数但非步进）、
 *       0、1.1 → 400（AC-F2.4）。</li>
 * </ul></p>
 *
 * <p><b>溢出防御</b>（D-11）：BigDecimal 全程无溢出，配合 V7 列宽预算
 * （roi DECIMAL(20,2)/payback DECIMAL(16,1)）——onetime=0.01、net≈1e12 时 roi%≈3e16 不抛异常。</p>
 */
public final class BizCaseCalculator {

    private BizCaseCalculator() {
    }

    /** 年净收益 = 收益 − 运营（可负，D-2 中间量落库：组合汇总 Σ 直接用列）。 */
    public static BigDecimal netBenefit(BigDecimal annualBenefit, BigDecimal annualOpCost) {
        return annualBenefit.subtract(annualOpCost);
    }

    /**
     * 投资回收期（AC-F2.2 四例逐条对应）。
     *
     * @return net≤0 → null（N/A 不可投，含 0 防除零）；onetime=0 且 net&gt;0 → 0.0（零成本）；
     *         否则 onetime÷net 保留 1 位 HALF_UP（100÷40=2.5；100÷30=3.3）
     */
    public static BigDecimal paybackYears(BigDecimal onetimeCost, BigDecimal netBenefit) {
        if (netBenefit.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (onetimeCost.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return onetimeCost.divide(netBenefit, 1, RoundingMode.HALF_UP);
    }

    /**
     * ROI 3 年口径（AC-F2.3）。
     *
     * @return onetime=0 → null（N/A 除零防御）；否则 (net×3−onetime)÷onetime×100 保留 2 位
     *         HALF_UP，负值合法（40×3−100)/100=20.00；(10×3−100)/100=−70.00）
     */
    public static BigDecimal roi(BigDecimal onetimeCost, BigDecimal netBenefit) {
        if (onetimeCost.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        // 先乘 100 后除、全程只做一次 HALF_UP 舍入（先除后乘会二次舍入引入偏差）
        return netBenefit.multiply(BigDecimal.valueOf(3))
                .subtract(onetimeCost)
                .multiply(BigDecimal.valueOf(100))
                .divide(onetimeCost, 2, RoundingMode.HALF_UP);
    }

    /**
     * RICE 评分（AC-F2.4）：reach×impact×confidence÷effort 保留 2 位 HALF_UP。
     * 调用前 reach/impact/effort 已过 {@link #validateFactor10}、confidence 已过
     * {@link #validateConfidence}（effort≥1 恒正，无除零路径）。
     */
    public static BigDecimal riceScore(int reach, int impact, BigDecimal confidence, int effort) {
        return BigDecimal.valueOf(reach)
                .multiply(BigDecimal.valueOf(impact))
                .multiply(confidence)
                .divide(BigDecimal.valueOf(effort), 2, RoundingMode.HALF_UP);
    }

    /**
     * confidence 离散校验（AC-F2.4）：合法值恰为 0.1~1.0 共 10 档（0.1 步进）。
     * 判定 {@code value×10 ∈ {1..10} 整数}——0.05/0.15/0.85（×10 非整数）、0（&lt;1）、
     * 1.1（&gt;10）均 400 指名。
     */
    public static void validateConfidence(BigDecimal confidence) {
        if (confidence == null) {
            throw new BizException(400, "confidence 不能为空");
        }
        BigDecimal tens = confidence.multiply(BigDecimal.TEN);
        if (tens.stripTrailingZeros().scale() > 0
                || tens.compareTo(BigDecimal.ONE) < 0 || tens.compareTo(BigDecimal.TEN) > 0) {
            throw new BizException(400, "confidence 必须为 0.1 步进离散值 0.1~1.0（非法值: "
                    + confidence.toPlainString() + "）");
        }
    }

    /**
     * RICE 因子 1~10 整数校验（reach/impact/effort 共用，AC-F2.4：0/11/1.5 → 400 指名）。
     *
     * @return 校验通过的整数值（1~10）
     */
    public static int validateFactor10(BigDecimal value, String field) {
        int v = RiskCalculator.validateIntegral(value, field);
        if (v < 1 || v > 10) {
            throw new BizException(400, field + " 必须为 1~10 的整数（非法值: " + value.toPlainString() + "）");
        }
        return v;
    }

    /**
     * 金额非负校验（AC-F2.5：任一为负 → 400；=0 合法触发边界态）。
     */
    public static void validateAmount(BigDecimal value, String field) {
        if (value == null) {
            throw new BizException(400, field + " 不能为空");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(400, field + " 不能为负数（非法值: " + value.toPlainString() + "）");
        }
        // S1（安全评审中危）：金额上限与精度镜像校验——DECIMAL(14,2) 越界严格模式 500、
        // 非严格模式静默截断且与计算列失联，一律前置 400
        if (value.scale() > 2 || value.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw new BizException(400, field + " 超出 DECIMAL(14,2) 范围（0~999999999999.99，两位小数）");
        }
    }
}
