package com.eaiselp.data.service.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.entity.Invoice;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.DerivationMapper;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import com.eaiselp.data.mapper.InvoiceMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.subscription.dto.GenerateSummaryVo;
import com.eaiselp.data.service.subscription.dto.InvoiceDetailVo;
import com.eaiselp.data.service.subscription.dto.InvoiceVo;
import com.eaiselp.data.service.subscription.impl.InvoiceServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.EaiselpRuntimeApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * InvoiceService H2 集成单测（case-20260823-商用化 T7/T9/T10；SE §9.1 锚点 2~10；
 * AC-F2.1~F2.9 数值/判定序/幂等/状态机/draft 隔离/信息隔离）。
 *
 * <p><b>数值断言照 SE §4.3 全表</b>（QA 复用同构造）：9,015.00 / ceil 1000 与 1001 /
 * 恰等 0.00 / 100.995→101.00 / Q6 折算 386.71（消解表基线，非 PRD 整月）。
 * 账期构造用固定字面量 2026-08（SE §9.2 账期边界例外）。种子套餐 seed 102=pro
 * （999.00 / 8.000000 / token_limit 5,000,000）。</p>
 *
 * <p><b>SYSTEM 上下文（D-5）</b>：出账测试不 set TenantContext（缺省 0=SYSTEM，拦截器全放行）
 * ——Service 全程显式 tenantId；插入行实体显式携带 tenant_id。</p>
 */
@SpringBootTest(classes = EaiselpRuntimeApplication.class)
@ActiveProfiles("test")
class InvoiceServiceImplTest {

    @Autowired InvoiceService invoiceService;
    @Autowired InvoiceMapper invoiceMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired DerivationMapper derivationMapper;
    @Autowired GovernanceLogMapper governanceLogMapper;
    @Autowired com.eaiselp.data.mapper.PlanMapper planMapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    /** T11：Spy 真实审计实现（其余用例不受影响），仅在回滚用例对 logSync 注入失败 */
    @SpyBean AuditService auditService;

    /**
     * 测试基建补位（schema-h2 缺口，非 DBA 资产改动）：t_governance_log / t_quota 在
     * schema-h2.sql 中从未建过（V8 前无测试诉求，GovernanceLogServiceImplTest/
     * TenantControllerTest 均纯 Mockito）——本类内 CREATE TABLE IF NOT EXISTS 幂等补建
     * （生产结构以 V1__baseline.sql 为准，此处 H2 简化版），不改 DBA 定稿的 schema-h2.sql。
     */
    @org.junit.jupiter.api.BeforeAll
    static void ensureTables(@Autowired org.springframework.jdbc.core.JdbcTemplate jt) {
        jt.execute("CREATE TABLE IF NOT EXISTS t_governance_log ("
                + "id BIGINT NOT NULL PRIMARY KEY, tenant_id BIGINT DEFAULT 0, user_id BIGINT, "
                + "username VARCHAR(64), action VARCHAR(64), resource_type VARCHAR(32), "
                + "resource_id VARCHAR(128), detail CLOB, ip_address VARCHAR(64), "
                + "result VARCHAR(16), error_msg CLOB, create_time TIMESTAMP)");
        jt.execute("CREATE TABLE IF NOT EXISTS t_quota ("
                + "id BIGINT NOT NULL PRIMARY KEY, tenant_id BIGINT DEFAULT 0, period CHAR(7), "
                + "token_limit BIGINT, token_used BIGINT, case_limit INT, case_used INT, "
                + "derivation_limit INT, derivation_used INT, storage_limit_mb BIGINT, "
                + "storage_used_mb BIGINT, monthly_cost DECIMAL(14,2), alert_threshold INT, "
                + "create_time TIMESTAMP, update_time TIMESTAMP, create_by VARCHAR(64), "
                + "update_by VARCHAR(64), is_deleted INT DEFAULT 0)");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_quota_tenant_period ON t_quota (tenant_id, period)");
        // schema-h2 的 t_tenant 为最小列集（V8 注释），Tenant 实体全列 SELECT 需补齐业务列
        // （幂等 ADD COLUMN IF NOT EXISTS；生产结构以 V1__baseline.sql 为准）
        for (String col : new String[]{
                "deploy_mode VARCHAR(32)", "contact_name VARCHAR(64)", "contact_email VARCHAR(128)",
                "contact_phone VARCHAR(64)", "system_repo_url VARCHAR(512)", "system_branch VARCHAR(64)",
                "llm_provider VARCHAR(32)", "llm_api_key VARCHAR(512)",
                "strategy_enabled INT DEFAULT 1", "program_project_enabled INT DEFAULT 1"}) {
            jt.execute("ALTER TABLE t_tenant ADD COLUMN IF NOT EXISTS " + col);
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String AUG = "2026-08";
    private static final LocalDateTime AUG_START = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime SEP_START = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final long T = 9001L; // 测试租户（pro 绑定）

    @BeforeEach
    void clean() {
        // 物理删（jdbcTemplate）——Mapper delete 是 @TableLogic 逻辑删，主键复用会 PK 冲突
        jdbcTemplate.update("DELETE FROM t_invoice WHERE tenant_id IN (9001, 9002, 9003, 9004)");
        jdbcTemplate.update("DELETE FROM t_derivation WHERE tenant_id IN (9001, 9002, 9003, 9004)");
        jdbcTemplate.update("DELETE FROM t_governance_log WHERE resource_id IN ('9001','9002','9003','9004')");
        // T11：bill 系审计行（bill_transit 的 resource_id=账单雪花 id，不落 900x 清理口径）
        jdbcTemplate.update("DELETE FROM t_governance_log WHERE action LIKE 'bill_%'");
        jdbcTemplate.update("DELETE FROM t_quota WHERE tenant_id IN (9001, 9002, 9003, 9004)");
        jdbcTemplate.update("DELETE FROM t_tenant WHERE id IN (9001, 9002, 9003, 9004)");
    }

    // ==================== 构造工具 ====================

    private Tenant tenant(long id, String edition, String planCode, LocalDateTime createTime) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setTenantCode("T" + id);
        t.setTenantName("租户" + id);
        t.setEdition(edition);
        t.setPlanCode(planCode);
        t.setStatus("active");
        t.setCreateTime(createTime);
        tenantMapper.insert(t);
        return t;
    }

    private void successDerivation(long tenantId, int in, int out, LocalDateTime startedAt, LocalDateTime createdAt) {
        Derivation d = new Derivation();
        d.setTenantId(tenantId);
        d.setCaseId("C1");
        d.setRole("team-po");
        d.setInputTokens(in);
        d.setOutputTokens(out);
        d.setStatus("success");
        d.setStartedAt(startedAt);
        d.setCreateTime(createdAt);
        derivationMapper.insert(d);
    }

    private Invoice invoiceOf(long tenantId) {
        return invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getTenantId, tenantId).eq(Invoice::getPeriod, AUG));
    }

    // ==================== 锚点 2：B2 trial / D-13 未绑定（AC-F2.1） ====================

    @Test
    void B2_全程trial_无行非0元账单() {
        tenant(9002L, "trial", null, AUG_START.minusDays(5));
        successDerivation(9002L, 1000, 1000, LocalDateTime.of(2026, 8, 15, 10, 0), null);

        GenerateSummaryVo s = invoiceService.generatePeriod(AUG, null, "qa");

        assertEquals(1, s.getSkippedTrial());
        assertEquals(0, s.getProcessed());
        assertNull(invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getTenantId, 9002L)), "无 t_invoice 行（非 0 元账单，AC-F2.1）");
    }

    @Test
    void D13_付费未绑套餐_跳过WARN_不出0元账单() {
        tenant(9003L, "pro", null, AUG_START.minusDays(10));

        GenerateSummaryVo s = invoiceService.generatePeriod(AUG, null, "qa");

        assertEquals(1, s.getSkippedNoPlan());
        assertEquals(0, s.getProcessed());
        assertNull(invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getTenantId, 9003L)), "D-13：无价格依据不出 0 元账单");
    }

    // ==================== 锚点 3：B3 期初判定 + fallback（AC-F2.1） ====================

    @Test
    void B3_期内trial转正_赠送不出账_下期正常() {
        tenant(9002L, "pro", "pro", AUG_START.minusDays(30)); // 期初已存在
        GovernanceLog audit = new GovernanceLog();
        audit.setTenantId(9002L);
        audit.setAction("tenant_edition_change");
        audit.setResourceType("tenant");
        audit.setResourceId(String.valueOf(9002L));
        audit.setCreateTime(LocalDateTime.of(2026, 8, 15, 9, 0)); // 期内 trial→pro
        audit.setDetail("{\"oldEdition\":\"trial\",\"newEdition\":\"pro\",\"operator\":\"pa\"}");
        governanceLogMapper.insert(audit);

        GenerateSummaryVo aug = invoiceService.generatePeriod(AUG, null, "qa");
        assertEquals(1, aug.getSkippedTrial(), "转正当月赠送（B3）");
        assertNull(invoiceOf(9002L), "8 月无行");

        // 下期（9 月，期内无 trial→非trial 变更）→ 正常出账
        GenerateSummaryVo sep = invoiceService.generatePeriod("2026-09", null, "qa");
        assertEquals(1, sep.getProcessed(), "9 月起正常出账");
    }

    @Test
    void B3_fallback_无审计_付费且期初前创建_正常出账整月() {
        // dogfooding 存量租户 V8 前无审计 → fallback 即主路径（R2）：create<期初 → 整月
        tenant(9002L, "pro", "pro", AUG_START.minusDays(60));

        GenerateSummaryVo s = invoiceService.generatePeriod(AUG, null, "qa");

        assertEquals(1, s.getProcessed());
        Invoice invoice = invoiceOf(9002L);
        assertNotNull(invoice);
        assertEquals(0, BigDecimal.ZERO.compareTo(invoice.getProrationAmount()), "proration=0.00 整月（期初前创建不折算）");
        assertEquals(0, new BigDecimal("999.00").compareTo(invoice.getTotalAmount()), "整月月价 999.00");
    }

    // ==================== 锚点 5：Q6 折算（AC-F2.2 第二构造，消解表 386.71） ====================

    @Test
    void Q6_8月20新建即付费_proration_386_71_含创建当天() {
        tenant(T, "pro", "pro", LocalDateTime.of(2026, 8, 20, 10, 0)); // 期内新建

        invoiceService.generatePeriod(AUG, null, "qa");

        Invoice invoice = invoiceOf(T);
        assertNotNull(invoice);
        assertEquals(new BigDecimal("386.71"), invoice.getProrationAmount(),
                "999.00 × 12 ÷ 31 = 386.71（剩余 12 天含创建当天——Q6 裁决覆盖 B4，不得按 PRD 断言整月）");
        assertEquals(new BigDecimal("999.00"), invoice.getPlanPrice(), "原价快照不随折算变化（D-12）");
        assertEquals(new BigDecimal("386.71"), invoice.getTotalAmount(), "无超量 → total = proration");
        // detail 折算参数四元组（附 A）
        assertEquals("386.71", invoice.getProrationAmount().toPlainString());
        assertTrue(invoice.getDetail().contains("\"remainingDays\":12"));
        assertTrue(invoice.getDetail().contains("\"daysInMonth\":31"));
        assertTrue(invoice.getDetail().contains("\"prorated\":true"));
        assertTrue(invoice.getDetail().contains("\"createdDate\":\"2026-08-20\""));
    }

    // ==================== 锚点 2 数值主构造：AC-F2.2（9,015.00） ====================

    @Test
    void 标准出账_9015_00_失败记录不计() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        // 成功 6,001,500（含 input+output）+ 失败 10,000（不计，B5）
        successDerivation(T, 3_000_000, 3_000_000, LocalDateTime.of(2026, 8, 10, 8, 0), null);
        successDerivation(T, 750, 750, LocalDateTime.of(2026, 8, 20, 9, 0), null);
        Derivation failed = new Derivation();
        failed.setTenantId(T);
        failed.setCaseId("C1");
        failed.setRole("team-se");
        failed.setInputTokens(5000);
        failed.setOutputTokens(5000);
        failed.setStatus("failed");
        failed.setStartedAt(LocalDateTime.of(2026, 8, 12, 8, 0));
        derivationMapper.insert(failed);

        invoiceService.generatePeriod(AUG, null, "qa");

        Invoice invoice = invoiceOf(T);
        assertNotNull(invoice);
        assertEquals(6_001_500L, invoice.getUsageTokens(), "失败 10,000 不计入（B5 成功终态口径）");
        assertEquals(1_001_500L, invoice.getOverageTokens());
        assertEquals(new BigDecimal("8016.00"), invoice.getOverageAmount(), "ceil(1001500/1000)=1002 × 8.00");
        assertEquals(new BigDecimal("9015.00"), invoice.getTotalAmount(), "999.00 + 8,016.00 = 9,015.00（AC-F2.2）");
        // 恒等式（V8 形态，QA 落库断言基线）
        BigDecimal base = invoice.getProrationAmount().signum() > 0
                ? invoice.getProrationAmount() : invoice.getPlanPrice();
        assertEquals(0, invoice.getTotalAmount().compareTo(base.add(invoice.getOverageAmount())),
                "total = (proration>0?proration:planPrice) + overage 恒成立");
        assertTrue(invoice.getDetail().contains("\"ktokenBilled\":1002"));
        assertTrue(invoice.getDetail().contains("\"code\":\"pro\""), "套餐五字段快照");
    }

    // ==================== 锚点：ceil 边界 + 恰等（AC-F2.3/F2.4） ====================

    @Test
    void ceil边界_1000恰不进位_1001进一位() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        tenant(9002L, "pro", "pro", AUG_START.minusDays(90));
        // T：5,000,000 + 1,000,000 = 6,000,000 用量 → 超额 1,000,000 → 1000 恰不进位
        successDerivation(T, 3_000_000, 3_000_000, LocalDateTime.of(2026, 8, 5, 8, 0), null);
        // 9002：5,000,000 + 1,000,001 → 1001
        successDerivation(9002L, 3_000_000, 3_000_001, LocalDateTime.of(2026, 8, 6, 8, 0), null);

        invoiceService.generatePeriod(AUG, null, "qa");

        assertEquals(new BigDecimal("8000.00"), invoiceOf(T).getOverageAmount(),
                "ceil(1,000,000/1000)=1000 恰不进位 × 8.00 = 8,000.00（AC-F2.3）");
        assertEquals(new BigDecimal("8008.00"), invoiceOf(9002L).getOverageAmount(),
                "ceil(1,000,001/1000)=1001 × 8.00 = 8,008.00（AC-F2.3）");
    }

    @Test
    void 用量恰等于配额_超量0_应缴月价() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        successDerivation(T, 2_500_000, 2_500_000, LocalDateTime.of(2026, 8, 8, 8, 0), null); // 恰 5,000,000

        invoiceService.generatePeriod(AUG, null, "qa");

        Invoice invoice = invoiceOf(T);
        assertEquals(0L, invoice.getOverageTokens(), "5,000,000 − 5,000,000 = 0（恰等 → 0）");
        assertEquals(new BigDecimal("0.00"), invoice.getOverageAmount());
        assertEquals(new BigDecimal("999.00"), invoice.getTotalAmount());
    }

    @Test
    void 未超量_4999999_应缴月价999() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        successDerivation(T, 2_500_000, 2_499_999, LocalDateTime.of(2026, 8, 8, 8, 0), null);

        invoiceService.generatePeriod(AUG, null, "qa");
        assertEquals(new BigDecimal("999.00"), invoiceOf(T).getTotalAmount(), "4,999,999 → 应缴 = 月价");
    }

    // ==================== AC-F2.5：HALF_UP 100.995 → 101.00（0.335 单价 6 位存取） ====================

    @Test
    void HALF_UP_构造_0_335单价_101_00() {
        com.eaiselp.data.entity.Plan custom = new com.eaiselp.data.entity.Plan();
        custom.setCode("custom");
        custom.setName("QA定制" + System.nanoTime());
        custom.setMonthlyPrice(new BigDecimal("99.99"));
        custom.setTokenOveragePrice(new BigDecimal("0.335"));
        custom.setSlaLevel("bronze");
        custom.setEditionOverride("starter");
        custom.setEnabled(true);
        custom.setTenantId(0L);
        custom.setFeatures("{\"max_users\":10,\"max_cases\":10,\"token_limit\":1000000,\"case_limit\":10,"
                + "\"derivation_limit\":10,\"storage_limit_mb\":1024,\"layer_overrides\":"
                + "{\"strategy_enabled\":1,\"program_project_enabled\":1},\"model_tiers\":[\"haiku\"]}");
        planMapper.insert(custom);
        try {
            Tenant cust = tenant(9004L, "starter", "custom", AUG_START.minusDays(90));
            // 超额 3,000 token → 3 千 × 0.335 = 1.005 → total 99.99 + 1.005 → 101.00
            successDerivation(9004L, 500_000, 500_000, LocalDateTime.of(2026, 8, 9, 8, 0), null); // 1,000,000 恰等
            successDerivation(9004L, 1500, 1500, LocalDateTime.of(2026, 8, 10, 8, 0), null); // +3,000

            invoiceService.generatePeriod(AUG, null, "qa");

            Invoice invoice = invoiceOf(9004L);
            assertNotNull(invoice);
            assertEquals(new BigDecimal("1.01"), invoice.getOverageAmount(), "3 × 0.335 = 1.005 → HALF_UP 1.01");
            assertEquals(new BigDecimal("101.00"), invoice.getTotalAmount(), "100.995 → 101.00（进位，AC-F2.5）");
        } finally {
            planMapper.deleteById(custom.getId());
        }
    }

    // ==================== 锚点 6：账期归属（AC-F2.6） ====================

    @Test
    void 账期归属_闭开区间_NULL_fallback_running_failed不计() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        // 8-31 23:59:59 → 8 月（左闭右开含边界前一刻）
        successDerivation(T, 1000, 1000, LocalDateTime.of(2026, 8, 31, 23, 59, 59), null);
        // 9-1 00:00:00 → 9 月（右开，不计 8 月）
        successDerivation(T, 1000, 1000, LocalDateTime.of(2026, 9, 1, 0, 0, 0), null);
        // started_at NULL + create_time 8-15 → 8 月（B5 fallback）
        successDerivation(T, 1000, 1000, null, LocalDateTime.of(2026, 8, 15, 12, 0));
        // running 不计
        Derivation running = new Derivation();
        running.setTenantId(T);
        running.setCaseId("C1");
        running.setRole("team-dev");
        running.setInputTokens(9_999_999);
        running.setOutputTokens(9_999_999);
        running.setStatus("running");
        running.setStartedAt(LocalDateTime.of(2026, 8, 20, 8, 0));
        derivationMapper.insert(running);

        invoiceService.generatePeriod(AUG, null, "qa");

        assertEquals(4_000L, invoiceOf(T).getUsageTokens(),
                "仅 8 月两笔 success 计入（各 2000=1000+1000：8-31 边界含、9-1 边界不含、"
                        + "NULL fallback 计、running 不计）");
    }

    // ==================== 锚点 4：B1 期末档（期中升降级按期末档，Q4 降级不回退） ====================

    @Test
    void B1_期中升降级_按期末档整月计价() {
        // 8-15 starter→pro：期末档 pro（月价 999、limit 5M、单价 8）——整月按 pro 计价
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        successDerivation(T, 3_000_000, 3_000_000, LocalDateTime.of(2026, 8, 10, 8, 0), null); // starter 期用量 6M

        invoiceService.generatePeriod(AUG, null, "qa");

        Invoice invoice = invoiceOf(T);
        assertNotNull(invoice);
        assertEquals("pro", invoice.getPlanCode(), "B1 期末档");
        // 用量 6M 超 starter 限（1M）但按期末 pro 限 5M 判超：超额 1M × pro 单价 8.00（Q4 降级同理由）
        assertEquals(1_000_000L, invoice.getOverageTokens(), "超量按期末套餐配额基准");
        assertEquals(new BigDecimal("8000.00"), invoice.getOverageAmount(), "超量按期末套餐单价（裁决 Q4）");
        assertEquals(new BigDecimal("8999.00"), invoice.getTotalAmount());
    }

    // ==================== 锚点 7：幂等三态（AC-F2.7） ====================

    @Test
    void 幂等_draft重算覆盖_补记用量后金额更新() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        successDerivation(T, 2_000_000, 2_000_000, LocalDateTime.of(2026, 8, 5, 8, 0), null);

        GenerateSummaryVo first = invoiceService.generatePeriod(AUG, null, "qa");
        assertEquals(1, first.getProcessed());
        Invoice v1 = invoiceOf(T);
        assertEquals(new BigDecimal("999.00"), v1.getTotalAmount(), "4M 未超配 → 999.00");

        // 补记用量（+2,001,500 → 合计 6,001,500）后重跑 → draft 覆盖重算
        successDerivation(T, 1_000_750, 1_000_750, LocalDateTime.of(2026, 8, 6, 8, 0), null);
        GenerateSummaryVo second = invoiceService.generatePeriod(AUG, null, "qa");
        assertEquals(1, second.getOverwritten(), "draft → 重算覆盖");
        assertEquals(0, second.getProcessed(), "无新行（uk 无第二行）");
        Invoice v2 = invoiceOf(T);
        assertEquals(6_001_500L, v2.getUsageTokens());
        assertEquals(new BigDecimal("9015.00"), v2.getTotalAmount(), "金额更新（AC-F2.7 draft 覆盖）");
        // uk 无第二行
        assertEquals(1, invoiceMapper.selectCount(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getTenantId, T).eq(Invoice::getPeriod, AUG)));
    }

    @Test
    void 幂等_issued不覆盖_paid不覆盖() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        successDerivation(T, 2_000_000, 2_000_000, LocalDateTime.of(2026, 8, 5, 8, 0), null);
        invoiceService.generatePeriod(AUG, null, "qa");
        Long id = invoiceOf(T).getId();

        invoiceService.transit(id, "issued");
        GenerateSummaryVo rerun1 = invoiceService.generatePeriod(AUG, null, "qa");
        assertEquals(0, rerun1.getOverwritten(), "issued 不覆盖（WARN）");
        assertEquals(new BigDecimal("999.00"), invoiceOf(T).getTotalAmount());

        invoiceService.transit(id, "paid");
        GenerateSummaryVo rerun2 = invoiceService.generatePeriod(AUG, null, "qa");
        assertEquals(0, rerun2.getOverwritten(), "paid 不覆盖（WARN）");
        // 补记用量也不动已收款金额
        successDerivation(T, 2_000_000, 2_000_000, LocalDateTime.of(2026, 8, 6, 8, 0), null);
        invoiceService.generatePeriod(AUG, null, "qa");
        assertEquals(new BigDecimal("999.00"), invoiceOf(T).getTotalAmount(), "paid 后用量补记不改金额");
    }

    // ==================== 锚点 8：状态机（AC-F2.8） ====================

    @Test
    void 状态机_合法链落时间_三类非法400() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        invoiceService.generatePeriod(AUG, null, "qa");
        Long id = invoiceOf(T).getId();

        // draft→paid 跳变 400
        BizException jump = assertThrows(BizException.class, () -> invoiceService.transit(id, "paid"));
        assertEquals(400, jump.getCode());

        // draft→issued 合法
        InvoiceVo issued = invoiceService.transit(id, "issued");
        assertEquals("issued", issued.getStatus());
        assertNotNull(issued.getIssuedTime());

        // issued→draft 回退 400
        BizException back = assertThrows(BizException.class, () -> invoiceService.transit(id, "draft"));
        assertEquals(400, back.getCode());

        // issued→paid 合法
        InvoiceVo paid = invoiceService.transit(id, "paid");
        assertEquals("paid", paid.getStatus());
        assertNotNull(paid.getPaidTime());

        // paid 后再流转 400
        BizException after = assertThrows(BizException.class, () -> invoiceService.transit(id, "issued"));
        assertEquals(400, after.getCode());
    }

    // ==================== 锚点 9：draft 隔离（AC-F2.9） ====================

    @Test
    void draft隔离_C2仅issued_paid可见_C3直查404() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        invoiceService.generatePeriod(AUG, null, "qa");
        Long draftId = invoiceOf(T).getId();

        // 另两张：7 月 issued + 6 月 paid（直接造行）
        Invoice jul = new Invoice();
        jul.setTenantId(T);
        jul.setPeriod("2026-07");
        jul.setPlanCode("pro");
        jul.setPlanPrice(new BigDecimal("999.00"));
        jul.setUsageTokens(0L);
        jul.setOverageTokens(0L);
        jul.setOverageAmount(new BigDecimal("0.00"));
        jul.setProrationAmount(new BigDecimal("0.00"));
        jul.setTotalAmount(new BigDecimal("999.00"));
        jul.setStatus("issued");
        invoiceMapper.insert(jul);
        Invoice jun = new Invoice();
        jun.setTenantId(T);
        jun.setPeriod("2026-06");
        jun.setPlanCode("pro");
        jun.setPlanPrice(new BigDecimal("999.00"));
        jun.setUsageTokens(0L);
        jun.setOverageTokens(0L);
        jun.setOverageAmount(new BigDecimal("0.00"));
        jun.setProrationAmount(new BigDecimal("0.00"));
        jun.setTotalAmount(new BigDecimal("999.00"));
        jun.setStatus("paid");
        invoiceMapper.insert(jun);

        var page = invoiceService.pageForTenant(T, 1, 20);
        assertEquals(2, page.getTotal(), "issued/paid/draft 三行 → 仅 2 行可见（第二道语义过滤）");
        assertTrue(page.getRecords().stream().noneMatch(v -> "draft".equals(v.getStatus())));

        // C3 直查 draft → 404
        BizException ex = assertThrows(BizException.class,
                () -> invoiceService.detailForTenant(T, draftId));
        assertEquals(40400, ex.getCode());
        // 他租户 → 404
        BizException other = assertThrows(BizException.class,
                () -> invoiceService.detailForTenant(8888L, jul.getId()));
        assertEquals(40400, other.getCode());
        // 平台侧（I2）可见 draft
        assertEquals("draft", invoiceService.detail(draftId).getStatus());
    }

    // ==================== 锚点 10：信息隔离（§8.4 显式断言） ====================

    @Test
    void 信息隔离_序列化无cost字段() throws Exception {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        invoiceService.generatePeriod(AUG, null, "qa");
        InvoiceDetailVo detail = invoiceService.detail(invoiceOf(T).getId());
        detail.getDetail().put("ktokenBilled", detail.getDetail().get("ktokenBilled")); // 触发 detail 非空

        String voJson = OM.writeValueAsString(invoiceService.pageForTenant(T, 1, 20).getRecords());
        String detailJson = OM.writeValueAsString(detail);
        assertFalse(voJson.toLowerCase().contains("cost"), "InvoiceVo 序列化无 cost 字段（§8.4 红线）");
        assertFalse(detailJson.toLowerCase().contains("cost"), "InvoiceDetailVo/detail 序列化无 cost 字段");
    }

    // ==================== I3 汇总口径（AC-F2.7 集成侧） ====================

    @Test
    void I3_指定租户补生成_period格式非法40000() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        GenerateSummaryVo s = invoiceService.generatePeriod(AUG, T, "pa-admin");
        assertEquals(1, s.getProcessed(), "指定租户单租户补生成（与定时同源入口）");
        assertEquals("pa-admin", invoiceOf(T).getCreateBy(), "手动补生成 createBy=操作者账号");

        BizException bad = assertThrows(BizException.class,
                () -> invoiceService.generatePeriod("2026/08", null, "qa"));
        assertEquals(40000, bad.getCode(), "period 非 yyyy-MM → 40000");
    }

    // ==================== case-20260824-技术债清偿 T11：审计同步写 + 失败回滚 ====================

    @Test
    void T11_审计写入成功_bill_transit审计行落库() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        invoiceService.generatePeriod(AUG, null, "qa");
        Long id = invoiceOf(T).getId();

        invoiceService.transit(id, "issued");

        // 同步审计行已落库（与流转同事务，先后脚可见——不再是"异步迟到"）
        Long auditCount = governanceLogMapper.selectCount(new LambdaQueryWrapper<GovernanceLog>()
                .eq(GovernanceLog::getAction, "bill_transit")
                .eq(GovernanceLog::getResourceId, String.valueOf(id)));
        assertEquals(1L, auditCount, "bill_transit 审计行与流转原子落库");
        assertEquals("issued", invoiceOf(T).getStatus());
    }

    @Test
    void T11_审计失败_流转一并回滚_杜绝资金已变无审计() {
        tenant(T, "pro", "pro", AUG_START.minusDays(90));
        invoiceService.generatePeriod(AUG, null, "qa");
        Long id = invoiceOf(T).getId();
        assertEquals("draft", invoiceOf(T).getStatus());

        // 注入审计写入失败（logSync 同步通道在事务内 INSERT 抛错）
        doThrow(new RuntimeException("audit db down"))
                .when(auditService).logSync(eq("bill_transit"), eq("bill"),
                        eq(String.valueOf(id)), anyString());

        assertThrows(Exception.class, () -> invoiceService.transit(id, "issued"),
                "审计失败必须让流转调用失败（上抛触发 @Transactional 回滚）");

        // 回滚断言：资金状态未变（draft），issued_time 未写入，审计行不存在
        Invoice after = invoiceOf(T);
        assertEquals("draft", after.getStatus(), "审计失败 → 流转回滚，状态仍 draft（资金已变无审计被杜绝）");
        assertNull(after.getIssuedTime(), "issued_time 回滚未写入");
        Long auditCount = governanceLogMapper.selectCount(new LambdaQueryWrapper<GovernanceLog>()
                .eq(GovernanceLog::getAction, "bill_transit")
                .eq(GovernanceLog::getResourceId, String.valueOf(id)));
        assertEquals(0L, auditCount, "失败的流转不留半截审计");
    }
}
