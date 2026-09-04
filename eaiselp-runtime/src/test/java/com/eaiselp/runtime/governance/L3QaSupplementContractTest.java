package com.eaiselp.runtime.governance;

import com.eaiselp.common.exception.BizException;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L3 收口 QA 补充契约测试（case-20260821 验收补位，team-qa 增量——不改 main，仅增测试）。
 *
 * <p>补位两处（QA 盘点既有 6 测试类后识别的覆盖缺口）：</p>
 * <ul>
 *   <li><b>TC_Q1 S1 金额上限镜像校验</b>（Security 评审 S1 已修但无自动化锚定）：
 *       {@code BizCaseCalculator.validateAmount} 的 DECIMAL(14,2) 边界——上限
 *       999999999999.99 合法、1e12 越界 400、scale&gt;2（如 0.001）400、负两位小数 400
 *       （AC-F2.5 金额校验 + V7 列宽契约）；</li>
 *   <li><b>TC_Q2 聚合 SQL 口径契约</b>：Service 层测试 mock 掉 Mapper 后，
 *       "closed 不入看板"（AC-F1.12）与"投资口径 status IN / 分布全量"（AC-F2.11/F2.12
 *       双口径）的口径只剩 @Select 注解文本承载——本用例反射读取注解 SQL 锁死关键片段，
 *       防 SQL 被改而 mock 测试仍绿的假 PASS。</li>
 * </ul>
 */
class L3QaSupplementContractTest {

    // ==================== TC_Q1：S1 金额上限与精度（AC-F2.5 + V7 DECIMAL(14,2)） ====================

    @Test
    void TC_Q1a_S1金额上限_恰好最大值合法_越界400() {
        // DECIMAL(14,2) 上限恰为 999999999999.99（12 位整数 + 2 位小数）
        assertDoesNotThrow(() ->
                BizCaseCalculator.validateAmount(new BigDecimal("999999999999.99"), "onetimeCost"));
        // 1e12 = 1000000000000 越界（严格模式 500 / 非严格静默截断，均须前置 400）
        for (String over : new String[]{"1000000000000", "999999999999.995", "99999999999999.99"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> BizCaseCalculator.validateAmount(new BigDecimal(over), "onetimeCost"));
            assertEquals(400, ex.getCode(), "over=" + over);
            assertTrue(ex.getMessage().contains("DECIMAL"), "提示镜像列宽，实际: " + ex.getMessage());
        }
    }

    @Test
    void TC_Q1b_S1金额精度_超过两位小数400_负两位小数仍走负值分支() {
        // scale > 2（0.001）→ 400（与上限同一防御：非严格模式静默舍入会使库值与计算列失联）
        BizException ex = assertThrows(BizException.class,
                () -> BizCaseCalculator.validateAmount(new BigDecimal("0.001"), "annualBenefit"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("DECIMAL"), "精度越界同用列宽提示，实际: " + ex.getMessage());

        // 负值优先级不受上限分支影响：-0.01 仍报"不能为负数"
        BizException neg = assertThrows(BizException.class,
                () -> BizCaseCalculator.validateAmount(new BigDecimal("-0.01"), "annualOpCost"));
        assertEquals(400, neg.getCode());
        assertTrue(neg.getMessage().contains("负"), "负值分支文案，实际: " + neg.getMessage());

        // scale 恰 2 的边界值合法（0.01 最小步进）
        assertDoesNotThrow(() ->
                BizCaseCalculator.validateAmount(new BigDecimal("0.01"), "onetimeCost"));
    }

    @Test
    void TC_Q1c_S1上限经Service入口生效_三金额字段同口径() {
        // Service 侧 create 前 validateAmount 逐一过三金额（BusinessCaseServiceImpl#validateForWrite），
        // 此处以 1e12 构造确认入口拒绝（Mockito 入口测试在 BusinessCaseServiceImplTest，本用例锁计算器单点）
        for (String field : new String[]{"onetimeCost", "annualOpCost", "annualBenefit"}) {
            assertEquals(400, assertThrows(BizException.class,
                    () -> BizCaseCalculator.validateAmount(new BigDecimal("1000000000000"), field)).getCode(),
                    field);
        }
    }

    // ==================== TC_Q2：聚合 SQL 口径契约（@Select 注解文本反射断言） ====================

    private static String selectSql(Class<?> mapper, String method) throws Exception {
        for (Method m : mapper.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                Select sel = m.getAnnotation(Select.class);
                assertNotNull(sel, mapper.getSimpleName() + "#" + method + " 缺 @Select");
                return String.join(" ", sel.value());
            }
        }
        fail(mapper.getSimpleName() + " 未找到方法 " + method);
        return null;
    }

    @Test
    void TC_Q2a_热力图SQL排除closed与逻辑删() throws Exception {
        String sql = selectSql(RiskMapper.class, "selectHeatCells").toLowerCase();
        assertTrue(sql.contains("status <> 'closed'"), "closed 不入看板（AC-F1.12），实际: " + sql);
        assertTrue(sql.contains("is_deleted = 0"), "手写 SQL 显式过滤逻辑删（@TableLogic 不生效），实际: " + sql);
        assertTrue(sql.contains("group by t.probability, t.impact"), "按 (P,I) 分组（轴口径），实际: " + sql);
    }

    @Test
    void TC_Q2b_投资口径SQL_状态过滤钉死_汇总三列COALESCE() throws Exception {
        String sql = selectSql(BusinessCaseMapper.class, "selectInvestmentSummary").toLowerCase();
        assertTrue(sql.contains("status in ('approved', 'executing', 'done')"),
                "投资口径=approved/executing/done（AC-F2.11，draft/rejected 不计钱），实际: " + sql);
        assertTrue(sql.contains("is_deleted = 0"), "显式过滤逻辑删，实际: " + sql);
        for (String col : new String[]{"sum(t.onetime_cost)", "sum(t.annual_op_cost)", "sum(t.net_benefit)"}) {
            assertTrue(sql.contains(col), "Σ 直接用落库列 " + col + "（D-2），实际: " + sql);
        }
        assertTrue(sql.contains("coalesce"), "空集 COALESCE 0（空态防御），实际: " + sql);
    }

    @Test
    void TC_Q2c_状态分布SQL_全量口径与投资口径有意不同() throws Exception {
        String sql = selectSql(BusinessCaseMapper.class, "selectStatusDistribution").toLowerCase();
        assertFalse(sql.contains("status in ("), "分布=全量五态（AC-F2.12，不得带状态过滤），实际: " + sql);
        assertTrue(sql.contains("group by t.status"), "按 status 分组，实际: " + sql);
        assertTrue(sql.contains("is_deleted = 0"), "显式过滤逻辑删，实际: " + sql);
    }
}
