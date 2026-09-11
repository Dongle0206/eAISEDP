package com.eaiselp.runtime.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import com.eaiselp.data.mapper.PlanMapper;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.TenantSubscriptionService;
import com.eaiselp.data.service.subscription.H2BillingTables;
import com.eaiselp.runtime.EaiselpRuntimeApplication;
import com.eaiselp.runtime.hierarchy.TenantLayerService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubscriptionApplyService H2 集成单测（case-20260823-商用化 T4；SE §9.1 锚点 13/14，
 * AC-F1.5/F1.6 + D-9 custom + 编排者追加 planId 优先/岐义 40004）。
 *
 * <p>五类落库一致性 + 层开关"说开就开/说关就关" + 审计 detail 五类快照（GovernanceLogMapper
 * 直查，V6 先例）。SYSTEM 上下文（platform_admin tenant 0 模拟）：不 set TenantContext。</p>
 *
 * <p><b>回滚用例</b>见 {@link SubscriptionApplyRollbackTest}（@MockBean QuotaMapper 需独立
 * Spring 上下文，DerivationEnginePersistenceFailureTest 先例）。</p>
 */
@SpringBootTest(classes = EaiselpRuntimeApplication.class)
@ActiveProfiles("test")
class SubscriptionApplyServiceTest {

    @Autowired SubscriptionApplyService applyService;
    @Autowired TenantMapper tenantMapper;
    @Autowired PlanMapper planMapper;
    @Autowired QuotaMapper quotaMapper;
    @Autowired GovernanceLogMapper governanceLogMapper;
    @Autowired TenantSubscriptionService subscriptionService;
    @Autowired TenantLayerService tenantLayerService;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final long TENANT = 9101L;
    private static final long OTHER = 9102L;
    private static final String PERIOD = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @BeforeAll
    static void ensureTables(@Autowired JdbcTemplate jt) {
        H2BillingTables.ensure(jt);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_quota WHERE tenant_id IN (9101, 9102)");
        jdbcTemplate.update("DELETE FROM t_governance_log WHERE resource_id IN ('9101','9102')");
        jdbcTemplate.update("DELETE FROM t_tenant WHERE id IN (9101, 9102)");
        jdbcTemplate.update("DELETE FROM t_plan WHERE id >= 9100000 AND tenant_id = 0 AND create_by = 'apply-test'");
    }

    // ==================== 构造工具 ====================

    private Tenant trialTenant(long id) {
        jdbcTemplate.update("INSERT INTO t_tenant (id, tenant_code, tenant_name, edition, status, "
                        + "expire_time, strategy_enabled, program_project_enabled, is_deleted) "
                        + "VALUES (?, ?, ?, 'trial', 'active', ?, 0, 1, 0)",
                id, "T" + id, "租户" + id, LocalDateTime.now().plusDays(5));
        return tenantMapper.selectById(id);
    }

    private Plan customPlan(String name, String editionOverride, boolean strategyEnabled, BigDecimal price) {
        Plan p = new Plan();
        p.setCode("custom");
        p.setName(name);
        p.setMonthlyPrice(price);
        p.setTokenOveragePrice(new BigDecimal("8.000000"));
        p.setSlaLevel("gold");
        p.setEditionOverride(editionOverride);
        p.setEnabled(true);
        p.setTenantId(0L);
        p.setCreateBy("apply-test");
        p.setFeatures("{\"max_users\":10,\"max_cases\":20,\"token_limit\":3000000,\"case_limit\":20,"
                + "\"derivation_limit\":300,\"storage_limit_mb\":10240,\"layer_overrides\":"
                + "{\"strategy_enabled\":" + (strategyEnabled ? 1 : 0) + ",\"program_project_enabled\":1},"
                + "\"model_tiers\":[\"sonnet\"]}");
        planMapper.insert(p);
        return p;
    }

    // ==================== 锚点 13：五类落库一致性（AC-F1.5） ====================

    @Test
    void 五类落库_trial应用pro_一次事务一致() {
        trialTenant(TENANT);
        // 预置当期配额行（覆写路径）
        Quota exist = new Quota();
        exist.setTenantId(TENANT);
        exist.setPeriod(PERIOD);
        exist.setTokenLimit(500_000L);
        exist.setTokenUsed(100_000L);
        exist.setCaseLimit(5);
        exist.setCaseUsed(2);
        exist.setDerivationLimit(50);
        exist.setDerivationUsed(10);
        exist.setStorageLimitMb(1024L);
        exist.setStorageUsedMb(500L);
        exist.setMonthlyCost(new BigDecimal("0.00"));
        quotaMapper.insert(exist);

        var status = applyService.applyPlan(TENANT, null, "pro");

        // ①②③ t_tenant 三列
        Tenant after = tenantMapper.selectById(TENANT);
        assertEquals("pro", after.getEdition(), "三档=code（D-9）");
        assertEquals("pro", after.getPlanCode(), "plan_code 绑定");
        assertNull(after.getExpireTime(), "expire_time 置空（付费档到期拦截不生效）");
        // ④ t_quota 四限 + monthly_cost 覆写（水位不动——Q4 降级水位不回退）
        Quota quota = quotaMapper.selectOne(new LambdaQueryWrapper<Quota>()
                .eq(Quota::getTenantId, TENANT).eq(Quota::getPeriod, PERIOD));
        assertNotNull(quota);
        assertEquals(5_000_000L, quota.getTokenLimit(), "pro token_limit 覆写");
        assertEquals(100, quota.getCaseLimit());
        assertEquals(1000, quota.getDerivationLimit());
        assertEquals(51200L, quota.getStorageLimitMb());
        assertEquals(0, new BigDecimal("999.00").compareTo(quota.getMonthlyCost()), "monthly_cost=套餐月价");
        assertEquals(100_000L, quota.getTokenUsed(), "水位保留不重置");
        // ⑤ 层开关：套餐说开就开（租户原 strategy_enabled=0 → 1）
        assertTrue(tenantLayerService.isStrategyEnabled(TENANT), "应用后 strategy 开（写后缓存失效生效）");
        // 回显 SubscriptionStatus 扩展三字段
        assertEquals("pro", status.getEdition());
        assertEquals("pro", status.getPlanCode());
        assertNotNull(status.getPlanName(), "套餐名快照（H2 seed 中文受连接编码影响，断言非空）");
        assertEquals("silver", status.getSlaLevel());
        assertFalse(status.isTrial());
    }

    @Test
    void 五类落库_配额行不存在_按模板upsert() {
        trialTenant(OTHER);
        applyService.applyPlan(OTHER, null, "starter");
        Quota quota = quotaMapper.selectOne(new LambdaQueryWrapper<Quota>()
                .eq(Quota::getTenantId, OTHER).eq(Quota::getPeriod, PERIOD));
        assertNotNull(quota, "行不存在按套餐模板 upsert（uk(tenant,period)）");
        assertEquals(1_000_000L, quota.getTokenLimit());
        assertEquals(0, new BigDecimal("299.00").compareTo(quota.getMonthlyCost()));
        assertEquals("starter", tenantMapper.selectById(OTHER).getPlanCode());
    }

    @Test
    void 层开关_说关就关() {
        trialTenant(TENANT);
        Plan off = customPlan("关战略层定制", "pro", false, new BigDecimal("100.00"));
        try {
            applyService.applyPlan(TENANT, off.getId(), null);
            assertFalse(tenantLayerService.isStrategyEnabled(TENANT), "套餐说关就关（商品边界清晰）");
            assertTrue(tenantLayerService.isProgramProjectEnabled(TENANT), "未关的层保持开");
        } finally {
            planMapper.deleteById(off.getId());
        }
    }

    // ==================== 锚点 14：审计五类快照（AC-F1.6）+ custom（D-9） ====================

    @Test
    void 审计_detail含五类快照与操作者() {
        trialTenant(TENANT);
        applyService.applyPlan(TENANT, null, "pro");

        GovernanceLog log = governanceLogMapper.selectOne(new LambdaQueryWrapper<GovernanceLog>()
                .eq(GovernanceLog::getAction, "tenant_edition_change")
                .eq(GovernanceLog::getResourceType, "tenant")
                .eq(GovernanceLog::getResourceId, String.valueOf(TENANT))
                .orderByDesc(GovernanceLog::getCreateTime)
                .last("LIMIT 1"));
        assertNotNull(log, "审计 tenant_edition_change（沿用既有 action）");
        String detail = log.getDetail();
        assertTrue(detail.contains("\"oldEdition\":\"trial\""), "edition old→new: " + detail);
        assertTrue(detail.contains("\"newEdition\":\"pro\""));
        assertTrue(detail.contains("\"newPlanCode\":\"pro\""), "plan_code 快照");
        assertTrue(detail.contains("\"tokenLimit\":5000000"), "配额四限快照");
        assertTrue(detail.contains("\"monthlyCost\":999.00"), "monthly_cost 快照");
        assertTrue(detail.contains("\"strategyEnabled\":true"), "层开关快照");
        assertTrue(detail.contains("\"modelTiers\":[\"sonnet\",\"haiku\"]"), "档位范围快照");
        assertTrue(detail.contains("\"operator\""), "操作者");
    }

    @Test
    void custom应用_edition取override_非trial_出账正常_D9() {
        trialTenant(TENANT);
        Plan custom = customPlan("定制企业档", "enterprise", true, new BigDecimal("2000.00"));
        try {
            var status = applyService.applyPlan(TENANT, custom.getId(), null);
            assertEquals("enterprise", status.getEdition(), "custom → edition=edition_override（D-9）");
            assertFalse(status.isTrial(), "非 trial 且出账正常（PRD 4.2.1）");
            Tenant after = tenantMapper.selectById(TENANT);
            assertEquals("enterprise", after.getEdition());
            assertEquals("custom", after.getPlanCode(), "绑定 custom 编码（出账按 custom 行计价）");
        } finally {
            planMapper.deleteById(custom.getId());
        }
    }

    // ==================== 错误分支 ====================

    @Test
    void 套餐不存在_40400() {
        trialTenant(TENANT);
        BizException ex = assertThrows(BizException.class,
                () -> applyService.applyPlan(TENANT, null, "no-such-plan"));
        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void 租户不存在_40400() {
        BizException ex = assertThrows(BizException.class,
                () -> applyService.applyPlan(4104104L, null, "pro"));
        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void 套餐已下架_400_不可应用_AC_F1_4() {
        trialTenant(TENANT);
        jdbcTemplate.update("UPDATE t_plan SET enabled = 0 WHERE code = 'pro'");
        try {
            BizException ex = assertThrows(BizException.class,
                    () -> applyService.applyPlan(TENANT, null, "pro"));
            assertEquals(400, ex.getCode(), "下架不可被新租户应用（AC-F1.4）");
        } finally {
            jdbcTemplate.update("UPDATE t_plan SET enabled = 1 WHERE code = 'pro'");
        }
    }

    // ==================== 编排者追加：planId 优先 / planCode 岐义 40004 ====================

    @Test
    void planCode岐义_多份enabled_custom_40004指明用planId() {
        trialTenant(TENANT);
        Plan a = customPlan("定制A", "pro", true, new BigDecimal("100.00"));
        Plan b = customPlan("定制B", "enterprise", true, new BigDecimal("200.00"));
        try {
            BizException ex = assertThrows(BizException.class,
                    () -> applyService.applyPlan(TENANT, null, "custom"));
            assertEquals(ResultCode.MODEL_TIER_FORBIDDEN, ex.getCode(),
                    "编排者追加裁决：岐义 40004");
            assertTrue(ex.getMessage().contains("planId"), "错误信息指明用 planId 精确指定: " + ex.getMessage());
        } finally {
            planMapper.deleteById(a.getId());
            planMapper.deleteById(b.getId());
        }
    }

    @Test
    void planId优先_planCode忽略_精确应用() {
        trialTenant(TENANT);
        Plan a = customPlan("定制A", "pro", true, new BigDecimal("100.00"));
        Plan b = customPlan("定制B", "enterprise", true, new BigDecimal("200.00"));
        try {
            // 同传 planId+planCode：planId 优先（编排者追加裁决第 3 点）
            var status = applyService.applyPlan(TENANT, b.getId(), "custom");
            assertEquals("enterprise", status.getEdition(), "按 planId=b（enterprise override）精确应用");
            assertEquals("custom", tenantMapper.selectById(TENANT).getPlanCode());
        } finally {
            planMapper.deleteById(a.getId());
            planMapper.deleteById(b.getId());
        }
    }

    @Test
    void planId不存在_40400() {
        trialTenant(TENANT);
        BizException ex = assertThrows(BizException.class,
                () -> applyService.applyPlan(TENANT, 987654321L, null));
        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    // ==================== U1 fillPlanSnapshot（出参扩展） ====================

    @Test
    void U1填充_未绑定trial_三字段null_绑定后回显() {
        Tenant trial = trialTenant(OTHER);
        var status = subscriptionService.getSubscriptionStatus(OTHER);
        applyService.fillPlanSnapshot(OTHER, status);
        assertNull(status.getPlanCode(), "trial 未绑定 → plan 三字段 null");

        applyService.applyPlan(OTHER, null, "pro");
        var bound = subscriptionService.getSubscriptionStatus(OTHER);
        applyService.fillPlanSnapshot(OTHER, bound);
        assertEquals("pro", bound.getPlanCode());
        assertNotNull(bound.getPlanName(), "套餐名快照回显（H2 seed 中文显示受连接编码影响，断言非空）");
        assertEquals("silver", bound.getSlaLevel());
    }
}
