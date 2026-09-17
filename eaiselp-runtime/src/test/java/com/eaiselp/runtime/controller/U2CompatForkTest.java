package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.TenantSubscriptionService;
import com.eaiselp.data.service.impl.TenantSubscriptionServiceImpl;
import com.eaiselp.runtime.subscription.SubscriptionApplyService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * U2 分叉点专项（case-20260823-商用化 T5 收口补位；AC-F1.8 + tasks.md「给 QA 的断言标注」#10）。
 *
 * <p><b>覆盖盘上缺口</b>：TenantController.updateSubscription 的 D-8 分叉（planId/planCode
 * 任一非空 → 套餐应用模式；均空 → 既有单字段路径）在 TenantControllerTest 存量 7 例
 * （case-20260820 基线）与新 SubscriptionApplyServiceTest（Service 层）之间无 Controller
 * 层专项——本类补齐：</p>
 * <ul>
 *   <li>互斥 400：planCode/planId 与 edition/expireTime 同传（applyPlan 与 updateSubscription
 *       双零调用——拒绝先于任何落库）；</li>
 *   <li>四者全空 → 既有"至少提供一个"400（老语义承载，非新分支）；</li>
 *   <li>planCode/planId 模式正确委派 applyPlan（planId 优先，planCode 忽略）；</li>
 *   <li><b>裸通道不动 plan_code（D-13 前半）</b>：不传 planCode 时 applyPlan 零调用——
 *       applyPlan 是 t_tenant.plan_code 唯一写点（SubscriptionApplyService ①②③ 步），
 *       Controller 层"不委派"即"不动列"的可测投影。</li>
 * </ul>
 *
 * <p>纯 Mockito（TenantControllerTest 先例）；subscriptionApplyService 为
 * {@code @Autowired(required=false)} 字段注入（构造器签名保持存量稳定），测试用
 * ReflectionTestUtils 注入 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
class U2CompatForkTest {

    @Mock TenantMapper tenantMapper;
    @Mock AuditService auditService;
    @Mock SubscriptionApplyService applyService;

    private TenantController controller;

    private static final Long TENANT_ID = 1L;

    /** 纯 Mockito 环境下为真实 TenantSubscriptionServiceImpl 的 LambdaUpdateWrapper 初始化元数据（幂等）。 */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Tenant.class);
    }

    @BeforeEach
    void setUp() {
        controller = new TenantController(tenantMapper, null, null, null, null,
                auditService, null, new TenantSubscriptionServiceImpl(tenantMapper, auditService));
        ReflectionTestUtils.setField(controller, "subscriptionApplyService", applyService);
    }

    @AfterEach
    void clearThreadLocal() {
        // clear() 同时清 LoginUser 与 TenantContext 双 ThreadLocal（case-20260824 T13）
        LoginUser.clear();
    }

    private void loginAsPlatformAdmin() {
        LoginUser.set(JwtClaims.builder()
                .userId(1L).username("pa").tenantId(0L).roles(List.of("platform_admin")).build());
    }

    private TenantController.SubscriptionUpdateRequest req(String edition, String expireTime,
                                                            String planCode, Long planId) {
        TenantController.SubscriptionUpdateRequest r = new TenantController.SubscriptionUpdateRequest();
        r.setEdition(edition);
        r.setExpireTime(expireTime);
        r.setPlanCode(planCode);
        r.setPlanId(planId);
        return r;
    }

    // ==================== D-8 互斥：400 且双零调用 ====================

    @Test
    void planCode与edition同传_400互斥_双零调用() {
        loginAsPlatformAdmin();
        var r = controller.updateSubscription(TENANT_ID, req("pro", null, "pro", null));

        assertEquals(400, r.getCode(), "planCode+edition 同传 → 400 互斥（D-8，QA 标注 10）");
        assertNull(r.getData());
        verifyNoInteractions(applyService);
        verify(tenantMapper, never()).update(any(), any());
    }

    @Test
    void planId与expireTime同传_400互斥_空串也是传值() {
        loginAsPlatformAdmin();
        var r = controller.updateSubscription(TENANT_ID, req(null, "", null, 102L));

        assertEquals(400, r.getCode(), "planId+expireTime（空串）同传 → 400 互斥——显式优于隐式");
        verifyNoInteractions(applyService);
        verify(tenantMapper, never()).update(any(), any());
    }

    // ==================== 四者全空：既有"至少提供一个"400 承载 ====================

    @Test
    void 四者全空_既有语义400_不走套餐应用() {
        loginAsPlatformAdmin();
        BizException ex = assertThrows(BizException.class,
                () -> controller.updateSubscription(TENANT_ID, req(null, null, null, null)));

        assertEquals(com.eaiselp.common.result.ResultCode.BAD_REQUEST, ex.getCode(),
                "全空 → 既有 updateSubscription 路径 40000（AC-F1.8 行为零变化）");
        assertTrue(ex.getMessage().contains("至少提供一个"));
        verifyNoInteractions(applyService);
    }

    // ==================== 套餐应用模式：正确委派 ====================

    @Test
    void planCode非空_委派applyPlan_planId为null() {
        loginAsPlatformAdmin();
        TenantSubscriptionService.SubscriptionStatus ok = new TenantSubscriptionService.SubscriptionStatus(
                "pro", "专业版", null, null, false, false, "pro", "专业版", "silver");
        when(applyService.applyPlan(TENANT_ID, null, "pro")).thenReturn(ok);

        var r = controller.updateSubscription(TENANT_ID, req(null, null, "pro", null));

        assertEquals(0, r.getCode());
        assertSame(ok, r.getData());
        verify(applyService).applyPlan(TENANT_ID, null, "pro");
        verifyNoInteractions(tenantMapper);
    }

    @Test
    void planId优先_planCode忽略_精确委派() {
        loginAsPlatformAdmin();
        TenantSubscriptionService.SubscriptionStatus ok = new TenantSubscriptionService.SubscriptionStatus(
                "starter", "入门版", null, null, false, false, "custom-x", "定制A", "bronze");
        when(applyService.applyPlan(TENANT_ID, 105L, "custom-x")).thenReturn(ok);

        var r = controller.updateSubscription(TENANT_ID, req(null, null, "custom-x", 105L));

        assertEquals(0, r.getCode(), "planId 优先——存在时 planCode 忽略（编排者追加裁决）");
        verify(applyService).applyPlan(TENANT_ID, 105L, "custom-x");
    }

    // ==================== 裸通道（D-13 前半）：不动 plan_code ====================

    @Test
    void 裸通道_edition单字段_applyPlan零调用_plan_code不动() {
        loginAsPlatformAdmin();
        Tenant expired = new Tenant();
        expired.setId(TENANT_ID);
        expired.setTenantCode("T1");
        expired.setTenantName("T1");
        expired.setEdition("trial");
        expired.setPlanCode("pro"); // 已绑定套餐的 trial（构造 D-13 场景）
        expired.setExpireTime(LocalDateTime.now().minusDays(1));
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(expired);
        when(tenantMapper.update(any(), any())).thenReturn(1);

        var r = controller.updateSubscription(TENANT_ID, req("pro", null, null, null));

        assertEquals(0, r.getCode(), "裸通道行为与 case-20260820 基线一致（edition 单字段更新成功）");
        assertEquals("pro", r.getData().getEdition());
        verifyNoInteractions(applyService);
        verify(tenantMapper).update(any(), any());
    }
}
