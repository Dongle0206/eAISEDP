package com.eaiselp.runtime.governance;

import com.eaiselp.common.exception.BizException;

import java.math.BigDecimal;

/**
 * 风险计算引擎（case-20260821 T1，SE 方案 D-3：final、私有构造、纯函数、零 Spring/ORM 依赖）。
 *
 * <p><b>唯一口径来源</b>（PRD §4.1.2，前后端与 QA 断言不得另行发明口径）：
 * <ul>
 *   <li><b>风险值</b>：{@code risk_value = probability × impact}（P、I 各 1~5 整数 → 值域 1~25）；</li>
 *   <li><b>等级映射</b>（四段闭区间）：v∈[1,6]→low；[7,12]→medium；[13,19]→high；[20,25]→critical
 *       ——边界 6/12/13/19/20 由闭区间自然命中（AC-F1.2）；</li>
 *   <li><b>已知语义（非缺陷）</b>：P=1,I=5 → v=5 → low——低概率×高影响按本公式属"低"，
 *       纯数学映射，缓解依赖复评日期与人工判断（PRD §4.1.2 写入口径，不接受
 *       "为什么不是高"类质疑工单）。</li>
 * </ul></p>
 *
 * <p><b>先校验后计算</b>（AC-F1.3）：P/I 为 1~5 整数——0/6/负数/非整数（如 1.5）在计算前
 * 抛 BizException(400) 指名字段与合法值提示。整数性判定用
 * {@code BigDecimal.stripTrailingZeros().scale() <= 0}（D-4：DTO 数值字段一律 BigDecimal 承载，
 * 1.5 可正常到达 Service 被本方法拒绝，不得落入 50000）。</p>
 */
public final class RiskCalculator {

    private RiskCalculator() {
    }

    /**
     * 校验并计算风险值：P/I 为 1~5 整数，否则 BizException(400, 字段名+合法值提示)。
     *
     * @param probability 概率（BigDecimal 承载，AC-F1.3：0/6/1.5/负数 → 400 指名）
     * @param impact      影响（同上）
     * @return 风险值 = P×I（1~25）
     */
    public static int riskValue(BigDecimal probability, BigDecimal impact) {
        int p = validateFactor5(probability, "probability");
        int i = validateFactor5(impact, "impact");
        return p * i;
    }

    /**
     * 等级映射（闭区间四段，AC-F1.2 边界口径）。
     *
     * @param riskValue 风险值 1~25
     * @return low / medium / high / critical
     */
    public static RiskLevel riskLevel(int riskValue) {
        return RiskLevel.ofValue(riskValue);
    }

    /**
     * 1~5 整数因子校验（probability/impact 共用，AC-F1.3）。
     *
     * @return 校验通过的整数值（1~5）
     * @throws BizException 400：null / 非整数（stripTrailingZeros().scale()&gt;0）/ 越界（&lt;1 或 &gt;5）
     */
    static int validateFactor5(BigDecimal value, String field) {
        int v = validateIntegral(value, field);
        if (v < 1 || v > 5) {
            throw new BizException(400, field + " 必须为 1~5 的整数（非法值: " + value.toPlainString() + "）");
        }
        return v;
    }

    /**
     * 整数性校验（D-4 核心）：{@code stripTrailingZeros().scale() <= 0} 判整——1.5/2.75 等
     * 合法 JSON 小数可正常到达此处，被 400 指名拒绝而非 Jackson 反序列化 50000。
     */
    static int validateIntegral(BigDecimal value, String field) {
        if (value == null) {
            throw new BizException(400, field + " 不能为空");
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new BizException(400, field + " 必须为整数（非法值: " + value.toPlainString() + "）");
        }
        return value.intValueExact();
    }
}
