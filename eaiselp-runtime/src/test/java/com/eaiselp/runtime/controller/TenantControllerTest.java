package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.mapper.RoleMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.mapper.UserMapper;
import com.eaiselp.data.mapper.UserRoleMapper;
import com.eaiselp.data.service.TenantSubscriptionService;
import com.eaiselp.data.service.impl.TenantSubscriptionServiceImpl;
import com.eaiselp.runtime.hierarchy.TenantProvisionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TenantController 订阅端点单测（case-20260820 F3，T22；SE §9.1 锚点 28/29，AC-F3.5/F3.6）。
 *
 * <p>纯 Mockito（同 UserControllerTest 先例）。订阅判定链路用<b>真实</b>
 * {@link TenantSubscriptionServiceImpl}（包 mock TenantMapper）——U1/U2 的口径行为
 * （daysLeft/expired 同源、edition 非法 400、40400）走真实实现，而非 mock 出来的契约。</p>
 *
 * <p>角色来自 JWT claims（LoginUser ThreadLocal 模拟拦截器注入态）——"不可伪造"指客户端
 * 不能通过请求参数冒充角色，claims 由服务端签发。</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantControllerTest {

    @Mock TenantMapper tenantMapper;
    @Mock UserMapper userMapper;
    @Mock UserRoleMapper userRoleMapper;
    @Mock RoleMapper roleMapper;
    @Mock QuotaMapper quotaMapper;
    @Mock AuditService auditService;
    @Mock TenantProvisionService tenantProvisionService;

    private TenantController controller;

    private static final Long TENANT_ID = 1L;

    /** 纯 Mockito 环境下为真实 Service 的 LambdaUpdateWrapper 初始化 Tenant 实体元数据（幂等）。 */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Tenant.class);
    }

    @BeforeEach
    void setUp() {
        TenantSubscriptionServiceImpl subscriptionService =
                new TenantSubscriptionServiceImpl(tenantMapper, auditService);
        controller = new TenantController(tenantMapper, userMapper, userRoleMapper, roleMapper,
                quotaMapper, auditService, tenantProvisionService, subscriptionService);
    }

    @AfterEach
    void clearThreadLocal() {
        LoginUser.set(null);
    }

    private void loginAs(Long tenantId, String... roles) {
        JwtClaims claims = JwtClaims.builder()
                .userId(1L).username("op").tenantId(tenantId).roles(List.of(roles)).build();
        LoginUser.set(claims);
    }

    private Tenant trialTenant(LocalDateTime expire) {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setTenantCode("T1");
        t.setTenantName("T1");
        t.setEdition("trial");
        t.setExpireTime(expire);
        return t;
    }

    // ==================== U1 角色矩阵（锚点 28，AC-F3.5） ====================

    @Test
    void U1_tenant_admin_200_状态与登录口径同源() {
        loginAs(TENANT_ID, "tenant_admin");
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(trialTenant(LocalDateTime.now().plusHours(3 * 24 - 1)));

        var r = controller.getSubscription();

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals("trial", r.getData().getEdition());
        assertEquals("试用版", r.getData().getEditionName());
        assertTrue(r.getData().isTrial());
        assertFalse(r.getData().isExpired());
        assertEquals(3, r.getData().getDaysLeft(), "daysLeft 与登录提示同 ceil 口径（AC-F3.5）");
        assertNotNull(r.getData().getExpireTime());
    }

    @Test
    void U1_platform_admin_200() {
        loginAs(TENANT_ID, "platform_admin");
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(trialTenant(LocalDateTime.now().minusHours(1)));

        var r = controller.getSubscription();

        assertEquals(0, r.getCode());
        assertTrue(r.getData().isExpired(), "trial 已到期 → expired=true");
        assertEquals(0, r.getData().getDaysLeft());
    }

    @Test
    void U1_engineer_40301() {
        loginAs(TENANT_ID, "engineer");

        var r = controller.getSubscription();

        assertEquals(ResultCode.FORBIDDEN, r.getCode(), "engineer 查订阅状态 → 40301（AC-F3.5）");
        assertNull(r.getData());
        verifyNoInteractions(tenantMapper);
    }

    @Test
    void U1_未登录_40101() {
        LoginUser.set(null);
        var r = controller.getSubscription();
        assertEquals(ResultCode.UNAUTHORIZED, r.getCode());
    }

    @Test
    void U1_租户不存在_40400() {
        loginAs(TENANT_ID, "tenant_admin");
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> controller.getSubscription());
        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    // ==================== U2 恢复路径（锚点 29，AC-F3.6） ====================

    @Test
    void U2_tenant_admin_40301() {
        loginAs(TENANT_ID, "tenant_admin");

        TenantController.SubscriptionUpdateRequest req = new TenantController.SubscriptionUpdateRequest();
        req.setEdition("pro");

        var r = controller.updateSubscription(TENANT_ID, req);

        assertEquals(ResultCode.FORBIDDEN, r.getCode(), "tenant_admin 调恢复接口 → 40301（AC-F3.6）");
        verifyNoInteractions(tenantMapper);
    }

    @Test
    void U2_edition非法_400() {
        loginAs(TENANT_ID, "platform_admin");

        TenantController.SubscriptionUpdateRequest req = new TenantController.SubscriptionUpdateRequest();
        req.setEdition("premium");

        BizException ex = assertThrows(BizException.class, () -> controller.updateSubscription(TENANT_ID, req));
        assertEquals(400, ex.getCode(), "edition 非法 → 400（AC-F3.6）");
        assertTrue(ex.getMessage().contains("premium"));
    }

    @Test
    void U2_platform_admin_修改成功_审计含旧到新() {
        loginAs(TENANT_ID, "platform_admin");
        // 旧：trial 已到期（AC-F3.1 状态被拦）
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(trialTenant(LocalDateTime.now().minusDays(1)));
        when(tenantMapper.update(any(), any())).thenReturn(1);

        TenantController.SubscriptionUpdateRequest req = new TenantController.SubscriptionUpdateRequest();
        req.setEdition("pro"); // 单字段更新（expireTime null=不变）

        var r = controller.updateSubscription(TENANT_ID, req);

        assertEquals(0, r.getCode());
        assertEquals("pro", r.getData().getEdition(), "修改后回显新值（契约 AC-U2）");
        assertFalse(r.getData().isTrial());

        // 审计 tenant_edition_change：detail 含 oldEdition→newEdition 与操作者（AC-F3.6）
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("tenant_edition_change"), eq("tenant"), eq(String.valueOf(TENANT_ID)),
                detail.capture());
        assertTrue(detail.getValue().contains("\"oldEdition\":\"trial\""));
        assertTrue(detail.getValue().contains("\"newEdition\":\"pro\""));
        assertTrue(detail.getValue().contains("\"operator\":\"op\""));
    }

    @Test
    void U2_恢复后_同租户到期校验放行_下次登录即生效() {
        loginAs(TENANT_ID, "platform_admin");
        // 第 1 次 selectById（updateSubscription 内）：trial 已到期（AC-F3.1 状态被拦）
        Tenant expired = trialTenant(LocalDateTime.now().minusDays(1));
        // 第 2 次 selectById（恢复后的下一次登录查询）：expire 已延期 30 天
        Tenant recovered = trialTenant(LocalDateTime.now().plusDays(30));
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(expired).thenReturn(recovered);
        when(tenantMapper.update(any(), any())).thenReturn(1);

        TenantController.SubscriptionUpdateRequest req = new TenantController.SubscriptionUpdateRequest();
        req.setExpireTime(LocalDateTime.now().plusDays(30)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        var r = controller.updateSubscription(TENANT_ID, req);
        assertEquals(0, r.getCode(), "expire 延期 30 天成功");
        assertTrue(r.getData().isTrial());
        assertFalse(r.getData().isExpired(), "延期后未到期");

        // 服务零缓存直查：恢复后的下一次判定（selectById 第 2 次返回新值）→ 放行（AC-F3.6"立即恢复"）
        TenantSubscriptionService service = new TenantSubscriptionServiceImpl(tenantMapper, auditService);
        assertDoesNotThrow(() -> service.assertNotExpired(TENANT_ID));
        verify(tenantMapper).update(any(), any());
    }
}
