package com.eaiselp.data.service.subscription;

import com.eaiselp.common.exception.BizException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 计费计算引擎（case-20260823-商用化 T1，SE 方案 D-3：final、私有构造、纯函数、
 * 零 Spring/ORM 依赖——RiskCalculator/BizCaseCalculator 先例；批A 第一位先行锁全部数值口径）。
 *
 * <p><b>唯一口径来源</b>（PRD §4.4.1 计费口径 B1~B6 + 编排者裁决 Q6 覆盖 B4，SE §4.3 全表；
 * 前后端与 QA 断言不得另行发明口径）：
 * <ul>
 *   <li><b>超量 token</b>（B5）：{@code overage = max(0, tokenUsed − tokenLimit)}——
 *       用量恰等于配额 → 0（AC-F2.4 邻界：5,000,000 − 5,000,000 = 0 → 应缴 = 月价 999.00）；</li>
 *   <li><b>计费千 token 单位</b>（B5 ceil）：{@code ktoken = (overage + 999) / 1000}（整数运算即
 *       向上取整，无浮点）——1,000,000 → 1000（<b>恰不进位</b>）、1,000,001 → 1001（AC-F2.3）、
 *       超额 1 token → 1（ceil 下界）；</li>
 *   <li><b>超量费</b>（B6 全精度）：{@code raw = ktoken × tokenOveragePrice}（BigDecimal 乘法
 *       不预舍入——0.335 × 3 = 1.005 中间不舍入）；落库值 = HALF_UP 2 位（1.005 → <b>1.01</b>，
 *       AC-F2.5）；</li>
 *   <li><b>首月折算</b>（裁决 Q6 覆盖 PRD B4，SE §0.3-1 消解）：
 *       {@code proration = monthlyPrice × remainingDays ÷ daysInMonth}（<b>先乘后除</b>保精度，
 *       HALF_UP 2 位；剩余天数<b>含创建当天</b>）——999.00 × 12 ÷ 31 = <b>386.71</b>
 *       （AC-F2.2 第二构造按消解表断言 386.71，非 PRD 原文整月 999.00）；</li>
 *   <li><b>应缴恒等式</b>（V8 差异定稿 D-2/D-4）：
 *       {@code total = (proration > 0 ? proration : planPrice) + overageAmount} 恒成立——
 *       base 恒 2 位小数，十进制加法结合律下 HALF_UP(base + overage全精度) ≡ base + HALF_UP(overage)
 *       （BigDecimal 十进制无二进制误差）。构造例：999.00 + 8,016.00 = <b>9,015.00</b>（AC-F2.2）、
 *       99.99 + 1.005 → <b>101.00</b>（进位，AC-F2.5）；</li>
 *   <li><b>上限镜像</b>（D-4/裁决 #2，L3 S1 教训）：任何落库金额 &gt; DECIMAL(14,2) 域上限
 *       999,999,999,999.99 → {@code BizException(400)} 指名"超出 DECIMAL(14,2) 域"（数学事实上限；
 *       V8 注释 99,999,999,999.99 少一位 9 属笔误，tasks.md 差异定稿 D-6），非 DB 溢出 500；</li>
 *   <li><b>零值</b>：超量 0 → 0.00、免费档 0 + 0 → 0.00，<b>scale 恒 2</b>（与 B2"无行"语义区分）。</li>
 * </ul></p>
 *
 * <p><b>Calculator 只管算，不管出不出账</b>——出账资格判定序（B2 trial/B3 赠送/D-13 无套餐/
 * Q6 折算判定）在 InvoiceServiceImpl（SE 方案 §4.2）。</p>
 */
public final class BillingCalculator {

    /** DECIMAL(14,2) 域上限（数学事实：999,999,999,999.99；D-6 定稿按此断言非 V8 注释笔误值）。 */
    public static final BigDecimal DECIMAL_14_2_MAX = new BigDecimal("999999999999.99");

    /** DECIMAL(12,6) 域上限（超量单价，999,999.999999）。 */
    public static final BigDecimal DECIMAL_12_6_MAX = new BigDecimal("999999.999999");

    private BillingCalculator() {
    }

    /**
     * 超额 token = max(0, tokenUsed − tokenLimit)（B5）。
     *
     * @param tokenUsed  本期成功派生 token 合计（input+output）
     * @param tokenLimit 本期 token 配额基准（期末套餐 features.token_limit，B1）
     */
    public static long overageTokens(long tokenUsed, long tokenLimit) {
        return Math.max(0L, tokenUsed - tokenLimit);
    }

    /**
     * 计费千 token 单位数 = overage ≤ 0 ? 0 : (overage + 999) / 1000（整数运算即 ceil，B5）。
     *
     * <p>边界：1,000,000 → 1000（恰不进位）；1,000,001 → 1001；超额 1 token → 1（AC-F2.3）。</p>
     */
    public static long ktokenBilled(long overageTokens) {
        if (overageTokens <= 0) {
            return 0L;
        }
        return (overageTokens + 999) / 1000;
    }

    /**
     * 超量费（全精度）= ktokenUnits × tokenOveragePrice（BigDecimal 乘法，不预舍入——B6 中间不舍入）。
     */
    public static BigDecimal overageFeeRaw(long ktokenUnits, BigDecimal tokenOveragePrice) {
        return BigDecimal.valueOf(ktokenUnits).multiply(requirePrice(tokenOveragePrice));
    }

    /**
     * 超量费落库值 = overageFeeRaw HALF_UP 2 位（D-4 恒等式右元；3 × 0.335 = 1.005 → 1.01）。
     * 超域（&gt; DECIMAL(14,2) 上限）抛 BizException(400) 指名（上限镜像）。
     */
    public static BigDecimal overageFee(long ktokenUnits, BigDecimal tokenOveragePrice) {
        BigDecimal value = overageFeeRaw(ktokenUnits, tokenOveragePrice).setScale(2, RoundingMode.HALF_UP);
        assertWithinDecimal14x2(value, "overage_amount");
        return value;
    }

    /**
     * 首月折算基础费 = monthlyPrice × remainingDays ÷ daysInMonth（先乘后除保精度，HALF_UP 2 位）。
     *
     * <p>Q6 锚点：999.00 × 12 ÷ 31 = 386.71（剩余天数含创建当天，AC-F2.2 第二构造消解基线）；
     * 1 日创建 → 剩余 = 整月 → 折算 = 原价；31 日创建 → 999 × 1 ÷ 31 = 32.23。</p>
     *
     * @param daysInMonth    当月天数（30/31/28/29）
     * @param remainingDays  剩余天数（含创建当天 = 当月天数 − create 日号 + 1）
     */
    public static BigDecimal proratedBaseFee(BigDecimal monthlyPrice, int daysInMonth, int remainingDays) {
        if (daysInMonth <= 0 || remainingDays <= 0 || remainingDays > daysInMonth) {
            throw new BizException(400, "折算参数非法: daysInMonth=" + daysInMonth
                    + ", remainingDays=" + remainingDays + "（应满足 0 < remainingDays ≤ daysInMonth）");
        }
        return requirePrice(monthlyPrice)
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
    }

    /**
     * 应缴 total_amount（V8 差异定稿 D-2：base = prorationAmount &gt; 0 ? prorationAmount : planPrice）
     * = HALF_UP(base + overageFeeRaw 全精度, 2)。
     *
     * <p>恒等式（QA 落库断言基线）：total ≡ base + overageFee 落库值（base 恒 2 位，十进制结合律）。
     * 超域抛 BizException(400) 指名"超出 DECIMAL(14,2) 域"（上限镜像，非 DB 溢出 500）。</p>
     */
    public static BigDecimal totalAmount(BigDecimal prorationAmount, BigDecimal planPrice,
                                         BigDecimal overageFeeRaw) {
        BigDecimal base = baseAmount(prorationAmount, planPrice);
        BigDecimal total = base.add(overageFeeRaw).setScale(2, RoundingMode.HALF_UP);
        assertWithinDecimal14x2(total, "total_amount");
        return total;
    }

    /**
     * 计费基础费（V8 语义 D-2/D-12：proration &gt; 0 取折算价，否则取套餐原价快照；
     * prorationAmount 为 null 或 ≤ 0 视为非折算账期 = 0.00）。
     */
    public static BigDecimal baseAmount(BigDecimal prorationAmount, BigDecimal planPrice) {
        if (prorationAmount != null && prorationAmount.signum() > 0) {
            return prorationAmount;
        }
        return requirePrice(planPrice);
    }

    /**
     * DECIMAL(14,2) 域上限镜像校验（D-4）：超限 BizException(400) 指名字段，非 DB 溢出 500。
     */
    public static void assertWithinDecimal14x2(BigDecimal amount, String field) {
        if (amount != null && amount.compareTo(DECIMAL_14_2_MAX) > 0) {
            throw new BizException(400, field + " 超出 DECIMAL(14,2) 域上限 999,999,999,999.99: "
                    + amount.toPlainString());
        }
    }

    /** 单价非空防御（null 价格进乘法前拦截，指名而非 NPE→500）。 */
    private static BigDecimal requirePrice(BigDecimal price) {
        if (price == null) {
            throw new BizException(400, "计费单价/月价不能为空（BillingCalculator 入参防御）");
        }
        return price;
    }
}
