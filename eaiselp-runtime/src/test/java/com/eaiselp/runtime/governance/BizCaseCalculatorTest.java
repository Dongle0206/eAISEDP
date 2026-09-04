package com.eaiselp.runtime.governance;

import com.eaiselp.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BizCaseCalculator 边界单测（case-20260821 T1，SE §9.1 锚点 2——AC-F2.2/F2.3/F2.4/F2.5
 * 数值侧；纯静态测试脱离 Spring/H2 可独立运行，构造值照 PRD §4.4.1 表）。
 */
class BizCaseCalculatorTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // ==================== 回收期（AC-F2.2 四例 + 负净收益） ====================

    @Test
    void 回收期_100除40等于2点5() {
        assertEquals(0, BizCaseCalculator.paybackYears(HUNDRED, new BigDecimal("40"))
                .compareTo(new BigDecimal("2.5")));
    }

    @Test
    void 回收期_100除30等于3点3_HALF_UP一位() {
        // 100/30 = 3.333… → HALF_UP 1 位 = 3.3
        assertEquals(0, BizCaseCalculator.paybackYears(HUNDRED, new BigDecimal("30"))
                .compareTo(new BigDecimal("3.3")));
    }

    @Test
    void 回收期_净0为null_不可投语义() {
        assertNull(BizCaseCalculator.paybackYears(HUNDRED, BigDecimal.ZERO), "net=0 → N/A（防除零）");
    }

    @Test
    void 回收期_净负10为null_不可投语义() {
        assertNull(BizCaseCalculator.paybackYears(HUNDRED, new BigDecimal("-10")),
                "net=−10 → N/A（不可投）");
    }

    @Test
    void 回收期_零成本净30为0点0_非null() {
        BigDecimal v = BizCaseCalculator.paybackYears(BigDecimal.ZERO, new BigDecimal("30"));
        assertNotNull(v, "onetime=0 且 net>0 → 0.0（零成本），与 N/A 语义分离（AC-F2.2）");
        assertEquals(0, v.compareTo(BigDecimal.ZERO));
        assertEquals(1, v.scale(), "0.0 保留 1 位小数（列 DECIMAL(16,1) 口径）");
    }

    // ==================== ROI 3 年口径（AC-F2.3 三例） ====================

    @Test
    void ROI_40净100成本等于20点00() {
        assertEquals(0, BizCaseCalculator.roi(HUNDRED, new BigDecimal("40"))
                .compareTo(new BigDecimal("20.00")));
    }

    @Test
    void ROI_负值合法_10净100成本等于负70点00() {
        assertEquals(0, BizCaseCalculator.roi(HUNDRED, new BigDecimal("10"))
                .compareTo(new BigDecimal("-70.00")));
    }

    @Test
    void ROI_零成本为null_除零防御() {
        assertNull(BizCaseCalculator.roi(BigDecimal.ZERO, new BigDecimal("30")),
                "onetime=0 → N/A（除零防御，与 payback 的 net≤0 N/A 语义来源不同）");
    }

    @Test
    void ROI_舍入单次_不因先除后乘二次舍入() {
        // 先乘 100 后除、单次 HALF_UP：net=20, onetime=3 → (60−3)×100/3 = 5700/3 = 1900.00（整除无歧义）；
        // 零收益例：net=10, onetime=30 → (30−30)×100/30 = 0.00
        assertEquals(0, BizCaseCalculator.roi(new BigDecimal("30"), new BigDecimal("10"))
                .compareTo(new BigDecimal("0.00")));
        assertEquals(0, BizCaseCalculator.roi(new BigDecimal("3"), new BigDecimal("20"))
                .compareTo(new BigDecimal("1900.00")));
    }

    // ==================== 年净收益（中间量，可负） ====================

    @Test
    void 年净收益_60减20等于40_可负() {
        assertEquals(0, BizCaseCalculator.netBenefit(new BigDecimal("60"), new BigDecimal("20"))
                .compareTo(new BigDecimal("40")));
        assertEquals(0, BizCaseCalculator.netBenefit(new BigDecimal("10"), new BigDecimal("20"))
                .compareTo(new BigDecimal("-10")));
    }

    // ==================== RICE（AC-F2.4 三构造值） ====================

    @Test
    void RICE_满分10乘10乘1点0除1等于100点00() {
        assertEquals(0, BizCaseCalculator.riceScore(10, 10, new BigDecimal("1.0"), 1)
                .compareTo(new BigDecimal("100.00")));
    }

    @Test
    void RICE_5乘3乘0点8除6等于2点00() {
        assertEquals(0, BizCaseCalculator.riceScore(5, 3, new BigDecimal("0.8"), 6)
                .compareTo(new BigDecimal("2.00")));
    }

    @Test
    void RICE_最小值1乘1乘0点1除10等于0点01() {
        assertEquals(0, BizCaseCalculator.riceScore(1, 1, new BigDecimal("0.1"), 10)
                .compareTo(new BigDecimal("0.01")));
    }

    // ==================== confidence 离散（AC-F2.4） ====================

    @Test
    void confidence_0点1到1点0十档全过() {
        for (int i = 1; i <= 10; i++) {
            BigDecimal c = BigDecimal.valueOf(i, 1);   // 0.1, 0.2, …, 1.0
            assertDoesNotThrow(() -> BizCaseCalculator.validateConfidence(c), "c=" + c);
        }
    }

    @Test
    void confidence_合法小数但非步进_0点05_0点15_0点85均400() {
        for (String bad : new String[]{"0.05", "0.15", "0.85"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> BizCaseCalculator.validateConfidence(new BigDecimal(bad)));
            assertEquals(400, ex.getCode(), "c=" + bad);
            assertTrue(ex.getMessage().contains("confidence"), "c=" + bad);
            assertTrue(ex.getMessage().contains(bad), "指名非法值 " + bad);
        }
    }

    @Test
    void confidence_0与1点1均400() {
        for (String bad : new String[]{"0", "1.1"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> BizCaseCalculator.validateConfidence(new BigDecimal(bad)));
            assertEquals(400, ex.getCode(), "c=" + bad);
        }
    }

    @Test
    void confidence_null为400() {
        assertEquals(400, assertThrows(BizException.class,
                () -> BizCaseCalculator.validateConfidence(null)).getCode());
    }

    @Test
    void confidence_数值等价的两位小数0点80合法() {
        // 0.80 数值上等于 0.8（步进值）——离散校验按数值不按 scale
        assertDoesNotThrow(() -> BizCaseCalculator.validateConfidence(new BigDecimal("0.80")));
    }

    // ==================== RICE 因子边界（AC-F2.4：0/11/1.5） ====================

    @Test
    void RICE因子_0_11_1点5均400指名() {
        for (String bad : new String[]{"0", "11", "1.5"}) {
            for (String field : new String[]{"reach", "impact", "effort"}) {
                BizException ex = assertThrows(BizException.class,
                        () -> BizCaseCalculator.validateFactor10(new BigDecimal(bad), field));
                assertEquals(400, ex.getCode(), field + "=" + bad);
                assertTrue(ex.getMessage().contains(field), field + "=" + bad);
            }
        }
    }

    // ==================== 金额校验（AC-F2.5） ====================

    @Test
    void 金额负值400_三字段同口径() {
        for (String field : new String[]{"onetimeCost", "annualOpCost", "annualBenefit"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> BizCaseCalculator.validateAmount(new BigDecimal("-1"), field));
            assertEquals(400, ex.getCode(), field);
            assertTrue(ex.getMessage().contains(field));
            assertTrue(ex.getMessage().contains("负"));
        }
    }

    @Test
    void 金额全0合法() {
        assertDoesNotThrow(() -> BizCaseCalculator.validateAmount(BigDecimal.ZERO, "onetimeCost"));
        assertDoesNotThrow(() -> BizCaseCalculator.validateAmount(BigDecimal.ZERO, "annualOpCost"));
        assertDoesNotThrow(() -> BizCaseCalculator.validateAmount(BigDecimal.ZERO, "annualBenefit"));
    }

    @Test
    void 金额null为400() {
        assertEquals(400, assertThrows(BizException.class,
                () -> BizCaseCalculator.validateAmount(null, "onetimeCost")).getCode());
    }

    // ==================== 溢出防御（D-11） ====================

    @Test
    void 极值_onetime0点01净1e12不抛异常() {
        BigDecimal net = new BigDecimal("1000000000000");          // 1e12
        assertDoesNotThrow(() -> {
            BigDecimal roi = BizCaseCalculator.roi(new BigDecimal("0.01"), net);
            assertNotNull(roi);                                      // ≈3e16，DECIMAL(20,2) 列宽预算内
        });
        // 全 0 边界态：net=0 → payback null；onetime=0 → roi null（AC-F2.5 Then）
        assertNull(BizCaseCalculator.paybackYears(BigDecimal.ZERO, BigDecimal.ZERO));
        assertNull(BizCaseCalculator.roi(BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
