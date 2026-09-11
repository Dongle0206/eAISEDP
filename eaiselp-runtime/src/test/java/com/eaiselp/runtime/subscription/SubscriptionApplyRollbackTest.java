package com.eaiselp.runtime.subscription;

import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.subscription.H2BillingTables;
import com.eaiselp.runtime.EaiselpRuntimeApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * 套餐应用回滚测试（case-20260823-商用化 T4；AC-F1.5 Then 项：任一步失败全部回滚，
 * 五类全保持旧值）。@MockBean QuotaMapper 需独立 Spring 上下文
 * （DerivationEnginePersistenceFailureTest 先例）。
 */
@SpringBootTest(classes = EaiselpRuntimeApplication.class)
@ActiveProfiles("test")
class SubscriptionApplyRollbackTest {

    @Autowired SubscriptionApplyService applyService;
    @Autowired TenantMapper tenantMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    /** mock 第五步前的配额覆写（④步 doThrow → ①②③ 的 t_tenant 更新同事务回滚） */
    @MockBean QuotaMapper quotaMapper;

    private static final long TENANT = 9201L;

    @BeforeAll
    static void ensureTables(@Autowired JdbcTemplate jt) {
        H2BillingTables.ensure(jt);
        // @MockBean QuotaMapper 替换真 mapper 后，本上下文不会为 Quota 构建 TableInfo
        // （lambda 缓存）——显式初始化（TenantControllerTest 纯 Mockito 先例同法）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Quota.class);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_quota WHERE tenant_id = 9201");
        jdbcTemplate.update("DELETE FROM t_governance_log WHERE resource_id = '9201'");
        jdbcTemplate.update("DELETE FROM t_tenant WHERE id = 9201");
    }

    @Test
    void 任一步失败_五类全回滚_无半应用状态() {
        jdbcTemplate.update("INSERT INTO t_tenant (id, tenant_code, tenant_name, edition, status, "
                        + "expire_time, strategy_enabled, program_project_enabled, is_deleted) "
                        + "VALUES (9201, 'T9201', '回滚租户', 'trial', 'active', ?, 0, 0, 0)",
                LocalDateTime.now().plusDays(5));

        // ④ QuotaMapper.update（覆写分支）doThrow → 事务回滚
        Mockito.when(quotaMapper.selectOne(any())).thenReturn(new Quota()); // 走覆写分支
        Mockito.when(quotaMapper.update(any(), any())).thenThrow(
                new RuntimeException("模拟配额行锁定失败（AC-F1.5 构造）"));

        assertThrows(BizException.class, () -> applyService.applyPlan(TENANT, null, "pro"));

        // 五类全保持旧值（无半应用状态）
        Tenant after = tenantMapper.selectById(TENANT);
        assertEquals("trial", after.getEdition(), "① edition 未变");
        assertNull(after.getPlanCode(), "② plan_code 未绑定");
        assertNotNull(after.getExpireTime(), "③ expire 未被置空");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_quota WHERE tenant_id = 9201", Integer.class), "④ 配额未写");
        assertEquals(Boolean.FALSE, after.getStrategyEnabled(), "⑤ 层开关未变（原 0）");
    }
}
