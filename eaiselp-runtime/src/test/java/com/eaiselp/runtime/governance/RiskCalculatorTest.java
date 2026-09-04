package com.eaiselp.runtime.governance;

import com.eaiselp.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskCalculator 边界单测（case-20260821 T1，SE §9.1 锚点 1——AC-F1.2/F1.3 数值侧；
 * 纯静态测试脱离 Spring/H2 可独立运行，构造值照 PRD §4.1.2 表，不自行发明）。
 */
class RiskCalculatorTest {

    // ==================== 等级边界（AC-F1.2 六边界值 + 端点） ====================

    @Test
    void 等级边界_四段闭区间逐值断言() {
        // [1,6]→low（边界 1 与 6）
        assertEquals(RiskLevel.LOW, RiskCalculator.riskLevel(1));
        assertEquals(RiskLevel.LOW, RiskCalculator.riskLevel(6));
        // [7,12]→medium（边界 7 与 12）
        assertEquals(RiskLevel.MEDIUM, RiskCalculator.riskLevel(7));
        assertEquals(RiskLevel.MEDIUM, RiskCalculator.riskLevel(12));
        // [13,19]→high（边界 13 与 19）
        assertEquals(RiskLevel.HIGH, RiskCalculator.riskLevel(13));
        assertEquals(RiskLevel.HIGH, RiskCalculator.riskLevel(19));
        // [20,25]→critical（边界 20 与 25）
        assertEquals(RiskLevel.CRITICAL, RiskCalculator.riskLevel(20));
        assertEquals(RiskLevel.CRITICAL, RiskCalculator.riskLevel(25));
    }

    @Test
    void 等级边界_列存值断言() {
        assertEquals("low", RiskCalculator.riskLevel(6).dbValue());
        assertEquals("medium", RiskCalculator.riskLevel(12).dbValue());
        assertEquals("high", RiskCalculator.riskLevel(13).dbValue());
        assertEquals("critical", RiskCalculator.riskLevel(20).dbValue());
    }

    // ==================== 风险值计算（AC-F1.2 构造值） ====================

    @Test
    void 风险值_P4I3等于12() {
        assertEquals(12, RiskCalculator.riskValue(new BigDecimal("4"), new BigDecimal("3")));
    }

    @Test
    void 已知语义_P1I5等于5判低_非缺陷锚定() {
        // PRD §4.1.2 已写入口径：低概率×高影响按公式属"低"——纯数学映射，不接受质疑工单
        int v = RiskCalculator.riskValue(BigDecimal.ONE, BigDecimal.valueOf(5));
        assertEquals(5, v);
        assertEquals(RiskLevel.LOW, RiskCalculator.riskLevel(v));
    }

    @Test
    void 风险值_合法端点P1I1与P5I5() {
        assertEquals(1, RiskCalculator.riskValue(BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(25, RiskCalculator.riskValue(new BigDecimal("5"), new BigDecimal("5")));
    }

    // ==================== P/I 边界校验（AC-F1.3，D-4 BigDecimal 整数性） ====================

    @Test
    void 概率为0_400指名() {
        BizException ex = assertThrows(BizException.class,
                () -> RiskCalculator.riskValue(BigDecimal.ZERO, BigDecimal.ONE));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("probability"), "指名字段，实际: " + ex.getMessage());
    }

    @Test
    void 概率为6_400指名() {
        BizException ex = assertThrows(BizException.class,
                () -> RiskCalculator.riskValue(new BigDecimal("6"), BigDecimal.ONE));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("probability"));
        assertTrue(ex.getMessage().contains("1~5"));
    }

    @Test
    void 概率非整数1点5_400指名_不得50000() {
        // D-4 核心：BigDecimal 承载让 1.5 正常到达 Service 被 400 指名拒绝
        BizException ex = assertThrows(BizException.class,
                () -> RiskCalculator.riskValue(new BigDecimal("1.5"), BigDecimal.ONE));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("probability"));
        assertTrue(ex.getMessage().contains("1.5"));
    }

    @Test
    void 影响同组边界_0_6_1点5_负数均400指名impact() {
        for (String bad : new String[]{"0", "6", "1.5", "-1"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> RiskCalculator.riskValue(BigDecimal.ONE, new BigDecimal(bad)));
            assertEquals(400, ex.getCode(), "impact=" + bad);
            assertTrue(ex.getMessage().contains("impact"), "impact=" + bad + "，实际: " + ex.getMessage());
        }
    }

    @Test
    void 概率负数_400() {
        BizException ex = assertThrows(BizException.class,
                () -> RiskCalculator.riskValue(new BigDecimal("-3"), BigDecimal.ONE));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("probability"));
    }

    @Test
    void 概率为null_400() {
        BizException ex = assertThrows(BizException.class,
                () -> RiskCalculator.riskValue(null, BigDecimal.ONE));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("probability"));
    }

    @Test
    void 整数值不同scale等价_4与4点0均合法() {
        assertEquals(12, RiskCalculator.riskValue(new BigDecimal("4.0"), new BigDecimal("3.0")));
    }
}
