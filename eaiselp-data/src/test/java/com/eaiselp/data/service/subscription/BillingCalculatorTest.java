package com.eaiselp.data.service.subscription;

import com.eaiselp.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BillingCalculator 单测（case-20260823-商用化 T1，SE §9.1 锚点 1；AC-F2.2/F2.3/F2.4/F2.5
 * 数值侧 + Q6 折算 + 上限镜像 + 零值）。
 *
 * <p><b>构造值照 SE §4.3 全表逐条</b>（QA 断言复用本表，不自行发明）；
 * AC-F2.2 第二构造（8-20 新建即付费）按 §0.3-1 消解表断言 <b>386.71</b>，
 * <b>不得按 PRD 原文断言整月 999.00</b>（Q6 裁决覆盖 B4，tasks.md 拆解声明 2）。</p>
 *
 * <p>纯静态断言族，脱离 Spring/H2 可独立运行（批A 第一位先行锁口径，R1/R3 缓解）。</p>
 */
class BillingCalculatorTest {

    private static final BigDecimal PRO_PRICE = new BigDecimal("999.00");
    private static final BigDecimal PRO_OVERAGE_PRICE = new BigDecimal("8.000000");

    // ==================== overageTokens（B5，AC-F2.4 邻界） ====================

    @Test
    void 超额token_用量恰等于配额_为0() {
        assertEquals(0L, BillingCalculator.overageTokens(5_000_000L, 5_000_000L),
                "恰等配额 → 0（AC-F2.4 邻界）");
    }

    @Test
    void 超额token_未超配额_为0() {
        assertEquals(0L, BillingCalculator.overageTokens(4_999_999L, 5_000_000L),
                "4,999,999 < 配额 → 0 → 应缴 = 月价 999.00");
    }

    @Test
    void 超额token_超出部分() {
        assertEquals(1_001_500L, BillingCalculator.overageTokens(6_001_500L, 5_000_000L),
                "6,001,500 − 5,000,000 = 1,001,500（AC-F2.2 构造）");
    }

    // ==================== ktokenBilled（B5 ceil，AC-F2.3） ====================

    @Test
    void ceil_恰不进位_1000() {
        assertEquals(1000L, BillingCalculator.ktokenBilled(1_000_000L),
                "1,000,000 → 1000（恰不进位，AC-F2.3）");
    }

    @Test
    void ceil_进一位_1001() {
        assertEquals(1001L, BillingCalculator.ktokenBilled(1_000_001L),
                "1,000,001 → 1001（AC-F2.3）");
    }

    @Test
    void ceil_下界_超额1token计1千() {
        assertEquals(1L, BillingCalculator.ktokenBilled(1L),
                "超额 1 token → 1 千（ceil 下界）");
    }

    @Test
    void ceil_零超量为0() {
        assertEquals(0L, BillingCalculator.ktokenBilled(0L));
        assertEquals(0L, BillingCalculator.ktokenBilled(-1L), "负入参防御 → 0");
    }

    @Test
    void ceil_AC_F2_2构造_1002() {
        assertEquals(1002L, BillingCalculator.ktokenBilled(1_001_500L),
                "ceil(1,001,500 / 1000) = 1002（AC-F2.2 构造）");
    }

    // ==================== overageFeeRaw / overageFee（B6，AC-F2.5） ====================

    @Test
    void 超量费_全精度不预舍入() {
        assertEquals(new BigDecimal("1.005"),
                BillingCalculator.overageFeeRaw(3L, new BigDecimal("0.335")),
                "3 × 0.335 = 1.005（全精度，不预舍入——(12,6) 单价 0.335 原样参与）");
    }

    @Test
    void 超量费_落库HALF_UP_2位() {
        assertEquals(new BigDecimal("1.01"),
                BillingCalculator.overageFee(3L, new BigDecimal("0.335")),
                "1.005 HALF_UP 2 位 → 1.01（AC-F2.5：overage_amount 列值）");
    }

    @Test
    void 超量费_AC_F2_2构造_8016() {
        assertEquals(new BigDecimal("8016.00"),
                BillingCalculator.overageFee(1002L, PRO_OVERAGE_PRICE),
                "1002 × 8.00 = 8,016.00（AC-F2.2 构造）");
    }

    @Test
    void 超量费_零值_scale恒2() {
        BigDecimal zero = BillingCalculator.overageFee(0L, PRO_OVERAGE_PRICE);
        assertEquals(0, zero.compareTo(BigDecimal.ZERO));
        assertEquals(2, zero.scale(), "零值 scale 恒 2（0.00，与 B2 无行语义区分）");
    }

    @Test
    void 超量费_超DECIMAL域_400指名非500() {
        // 2×10^11 千 token × 8.00 = 1.6e12 > 999,999,999,999.99 → 400 指名（D-6 上限镜像）
        BizException ex = assertThrows(BizException.class,
                () -> BillingCalculator.overageFee(200_000_000_000L, PRO_OVERAGE_PRICE));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("DECIMAL(14,2)"), "指名超出 DECIMAL(14,2) 域");
    }

    // ==================== proratedBaseFee（Q6 折算，SE §4.3 全边界） ====================

    @Test
    void 折算_Q6锚点_999乘12除31_386_71() {
        assertEquals(new BigDecimal("386.71"),
                BillingCalculator.proratedBaseFee(PRO_PRICE, 31, 12),
                "999.00 × 12 ÷ 31 = 386.7096… → 386.71（剩余 12 天含创建当天，先乘后除 HALF_UP，AC-F2.2 第二构造消解基线）");
    }

    @Test
    void 折算_1日创建_剩余整月_等于原价() {
        assertEquals(new BigDecimal("999.00"),
                BillingCalculator.proratedBaseFee(PRO_PRICE, 31, 31),
                "1 日创建 → 剩余 = 整月 → 折算 = 原价");
    }

    @Test
    void 折算_31日创建_32_23() {
        assertEquals(new BigDecimal("32.23"),
                BillingCalculator.proratedBaseFee(PRO_PRICE, 31, 1),
                "999 × 1 ÷ 31 = 32.2258… → 32.23");
    }

    @Test
    void 折算_平年2月28天专项例() {
        assertEquals(new BigDecimal("999.00"),
                BillingCalculator.proratedBaseFee(PRO_PRICE, 28, 28), "28 天月整月");
        assertEquals(new BigDecimal("35.68"),
                BillingCalculator.proratedBaseFee(PRO_PRICE, 28, 1),
                "999 ÷ 28 = 35.678… → 35.68（平年 2 月专项）");
        assertEquals(new BigDecimal("499.50"),
                BillingCalculator.proratedBaseFee(PRO_PRICE, 28, 14), "28 天月半月：999×14÷28 = 499.50");
    }

    @Test
    void 折算_30天月() {
        assertEquals(new BigDecimal("999.00"),
                BillingCalculator.proratedBaseFee(PRO_PRICE, 30, 30));
        assertEquals(new BigDecimal("33.30"),
                BillingCalculator.proratedBaseFee(PRO_PRICE, 30, 1), "999 × 1 ÷ 30 = 33.3 → 33.30");
    }

    @Test
    void 折算_参数非法_400() {
        BizException ex = assertThrows(BizException.class,
                () -> BillingCalculator.proratedBaseFee(PRO_PRICE, 31, 32));
        assertEquals(400, ex.getCode(), "remainingDays > daysInMonth → 400");
        assertThrows(BizException.class,
                () -> BillingCalculator.proratedBaseFee(PRO_PRICE, 31, 0));
    }

    // ==================== totalAmount + 恒等式（V8 D-2/D-4） ====================

    @Test
    void 应缴_AC_F2_2构造_9015_00() {
        BigDecimal raw = BillingCalculator.overageFeeRaw(1002L, PRO_OVERAGE_PRICE);
        assertEquals(new BigDecimal("9015.00"),
                BillingCalculator.totalAmount(BigDecimal.ZERO, PRO_PRICE, raw),
                "999.00 + 8,016.00 = 9,015.00（AC-F2.2 主构造）");
    }

    @Test
    void 应缴_AC_F2_5构造_101_00进位() {
        BigDecimal raw = BillingCalculator.overageFeeRaw(3L, new BigDecimal("0.335"));
        assertEquals(new BigDecimal("101.00"),
                BillingCalculator.totalAmount(BigDecimal.ZERO, new BigDecimal("99.99"), raw),
                "99.99 + 1.005 = 100.995 → HALF_UP → 101.00（进位，AC-F2.5）");
    }

    @Test
    void 应缴_Q6折算与超量叠加_折算只作用基础费() {
        BigDecimal proration = BillingCalculator.proratedBaseFee(PRO_PRICE, 31, 12); // 386.71
        BigDecimal raw = BillingCalculator.overageFeeRaw(1002L, PRO_OVERAGE_PRICE);  // 8016.00
        assertEquals(new BigDecimal("8402.71"),
                BillingCalculator.totalAmount(proration, PRO_PRICE, raw),
                "386.71 + 8,016.00 = 8,402.71（超量费不折算）");
    }

    @Test
    void 应缴_未超量_恰等于月价() {
        assertEquals(new BigDecimal("999.00"),
                BillingCalculator.totalAmount(BigDecimal.ZERO, PRO_PRICE, BigDecimal.ZERO),
                "0 超量 → 应缴 = 月价 999.00（AC-F2.4）");
    }

    @Test
    void 应缴_免费档零值_0_00_scale2() {
        BigDecimal total = BillingCalculator.totalAmount(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(0, total.compareTo(BigDecimal.ZERO));
        assertEquals(2, total.scale(), "免费档 0+0 → 0.00（合法账单，scale 恒 2）");
    }

    @Test
    void 应缴_超DECIMAL域_400指名非500() {
        // base 999,999,999,999.99 + 1.00 超域 → 400 指名（QA 构造 1e12 断言口径，tasks.md QA 标注 9）
        BizException ex = assertThrows(BizException.class,
                () -> BillingCalculator.totalAmount(BigDecimal.ZERO,
                        new BigDecimal("999999999999.99"), new BigDecimal("1.00")));
        assertEquals(400, ex.getCode(), "超出 DECIMAL(14,2) 域 → 400 指名，非 500（D-6）");
        assertTrue(ex.getMessage().contains("DECIMAL(14,2)"));
    }

    @Test
    void 恒等式_total等于base加overage落库值_全构造() {
        // SE D-4 数学定稿：base 恒 2 位 → HALF_UP(base+raw) ≡ base+HALF_UP(raw)
        String[][] cases = {
                {"999.00", "8.000000", "1002"},   // 9015.00
                {"99.99", "0.335", "3"},          // 101.00
                {"999.00", "0.335", "3"},         // 1000.01
                {"0.00", "12.000000", "5"},       // 0.06
                {"999.00", "0.333333", "7"},      // 999 + 2.333331 → 1001.33
        };
        for (String[] c : cases) {
            BigDecimal base = new BigDecimal(c[0]);
            BigDecimal price = new BigDecimal(c[1]);
            long ktoken = Long.parseLong(c[2]);
            BigDecimal raw = BillingCalculator.overageFeeRaw(ktoken, price);
            BigDecimal overageStored = BillingCalculator.overageFee(ktoken, price);
            BigDecimal total = BillingCalculator.totalAmount(BigDecimal.ZERO, base, raw);
            assertEquals(0, total.compareTo(base.add(overageStored)),
                    "恒等式失败: base=" + base + ", raw=" + raw);
            assertEquals(2, total.scale());
        }
    }

    @Test
    void baseAmount_折算非0取折算_否则原价() {
        BigDecimal proration = new BigDecimal("386.71");
        assertEquals(0, BillingCalculator.baseAmount(proration, PRO_PRICE).compareTo(proration),
                "proration > 0 → base = 折算价");
        assertEquals(0, BillingCalculator.baseAmount(BigDecimal.ZERO, PRO_PRICE).compareTo(PRO_PRICE),
                "proration = 0 → base = 原价快照");
        assertEquals(0, BillingCalculator.baseAmount(null, PRO_PRICE).compareTo(PRO_PRICE),
                "proration null（非折算账期）→ base = 原价快照");
    }
}
