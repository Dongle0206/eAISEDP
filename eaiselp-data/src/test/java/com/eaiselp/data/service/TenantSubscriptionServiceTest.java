package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.dto.TrialTipVo;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.impl.TenantSubscriptionServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TenantSubscriptionService 单测（case-20260820 F3，T18；SE §9.1 锚点 1~5 + U2 恢复路径锚点 29 部分）。
 *
 * <p>纯口径测试（Mockito + mock TenantMapper，无需 Spring/H2）。时间构造统一
 * {@code LocalDateTime.now().plusHours(...)} 相对法（SE §9.2：禁止绝对日期，防跨时区/跨天脆断言；
 * 构造与断言间毫秒抖动不会跨 ceil 边界——各用例距边界均 ≥1h）。</p>
 *
 * <p>口径锚点（PRD §4.3.1 唯一口径，QA 照此构造）：
 * 到期 now≥expire 含等于；豁免仅校验 trial；NULL 不过期；窗口 ≤7×24h；N=ceil((expire−now)/24h)。</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantSubscriptionServiceTest {

    @Mock TenantMapper tenantMapper;
    @Mock AuditService auditService;

    TenantSubscriptionServiceImpl service;

    private static final Long TENANT_ID = 100L;

    /**
     * 纯 Mockito 无 MyBatis 启动时，LambdaUpdateWrapper 解析 Tenant::getXxx 需要 MP 实体元数据
     * （TableInfo lambda 缓存）。显式初始化一次（幂等），否则 updateSubscription 抛
     * "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Tenant.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new TenantSubscriptionServiceImpl(tenantMapper, auditService);
    }

    private Tenant tenant(String edition, LocalDateTime expire) {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setTenantCode("T100");
        t.setTenantName("T100");
        t.setEdition(edition);
        t.setExpireTime(expire);
        return t;
    }

    // ==================== 锚点 1：到期判定（now ≥ expire 含等于） ====================

    @Test
    void 锚点1_expire已过_拦截40003含文案() {
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant("trial", LocalDateTime.now().minusSeconds(1)));
        BizException ex = assertThrows(BizException.class, () -> service.assertNotExpired(TENANT_ID));
        assertEquals(ResultCode.TRIAL_EXPIRED, ex.getCode(), "到期必须 40003");
        assertTrue(ex.getMessage().contains("试用已到期"), "message 必含「试用已到期」（AC-F3.1 断言点）");
        assertTrue(ex.getMessage().contains("升级"), "message 必含升级指引占位");
    }

    @Test
    void 锚点1_expire未到_放行() {
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant("trial", LocalDateTime.now().plusSeconds(1)));
        assertDoesNotThrow(() -> service.assertNotExpired(TENANT_ID), "expire=T+1s 未到期应放行");
    }

    // ==================== 锚点 2：正式版豁免（expire 完全忽略） ====================

    @Test
    void 锚点2_正式版三档_过期也不拦截不提示() {
        for (String edition : new String[]{"pro", "enterprise", "starter"}) {
            Tenant t = tenant(edition, LocalDateTime.now().minusDays(30)); // expire 已过 30 天
            assertDoesNotThrow(() -> service.assertNotExpired(t),
                    edition + " 版必须豁免（AC-F3.3，expire 完全忽略）");
            assertNull(service.buildTrialTip(t), edition + " 版无任何试用提示");
        }
    }

    // ==================== 锚点 3：NULL 防御（Q4） ====================

    @Test
    void 锚点3_trial且expire为NULL_放行无提示() {
        Tenant t = tenant("trial", null);
        assertDoesNotThrow(() -> service.assertNotExpired(t), "trial+NULL=未设置到期，不拦截（AC-F3.4/Q4）");
        assertNull(service.buildTrialTip(t), "trial+NULL 无提示");
    }

    // ==================== 锚点 4：四租户口径数值断言（AC-F3.2） ====================

    @Test
    void 锚点4_四租户口径_T8无tip_T7七normal_T3三warning_T1一critical() {
        // T8：(expire−now)=8×24h > 7×24h → 无提示
        assertNull(service.buildTrialTip(tenant("trial", LocalDateTime.now().plusHours(8 * 24))),
                "T8（+8×24h）应无提示");

        // T7：(expire−now)=7×24h−1h → N=ceil(167h/24h)=7，level=normal
        TrialTipVo t7 = service.buildTrialTip(tenant("trial", LocalDateTime.now().plusHours(7 * 24 - 1)));
        assertNotNull(t7, "T7（+7×24h−1h）应有提示");
        assertEquals(7, t7.getDaysLeft(), "T7 剩余天数=7（ceil 口径）");
        assertEquals(TrialTipVo.LEVEL_NORMAL, t7.getLevel(), "N=7 → normal");
        assertNotNull(t7.getExpireTime(), "expireTime 原值回显");

        // T3：(expire−now)=3×24h−1h → N=ceil(71h/24h)=3，level=warning
        TrialTipVo t3 = service.buildTrialTip(tenant("trial", LocalDateTime.now().plusHours(3 * 24 - 1)));
        assertNotNull(t3, "T3（+3×24h−1h）应有提示");
        assertEquals(3, t3.getDaysLeft(), "T3 剩余天数=3（ceil 口径）");
        assertEquals(TrialTipVo.LEVEL_WARNING, t3.getLevel(), "N=3 → warning");

        // T1：(expire−now)=7h → N=ceil(7h/24h)=1，level=critical（红色优先）
        TrialTipVo t1 = service.buildTrialTip(tenant("trial", LocalDateTime.now().plusHours(7)));
        assertNotNull(t1, "T1（+7h）应有提示");
        assertEquals(1, t1.getDaysLeft(), "T1 剩余天数=1（不满一天算 1 天）");
        assertEquals(TrialTipVo.LEVEL_CRITICAL, t1.getLevel(), "N=1 → critical（红色优先）");
    }

    // ==================== 锚点 5：7×24h 边界 ====================

    @Test
    void 锚点5_恰等于7天有提示_超60秒无提示() {
        // 恰=7×24h → N=7 有提示（≤ 窗口含边界；距边界 0 抖动——毫秒级抖动使实际略小于 7×24h，
        // ceil 仍=7，断言不脆）
        TrialTipVo exact = service.buildTrialTip(tenant("trial", LocalDateTime.now().plusHours(7 * 24)));
        assertNotNull(exact, "恰=7×24h 应有提示");
        assertEquals(7, exact.getDaysLeft(), "恰=7×24h → N=7");

        // >7×24h（+60s）→ 无提示（+1ms 不可构造，用 +60s，SE §9.1.5）
        assertNull(service.buildTrialTip(tenant("trial", LocalDateTime.now().plusHours(7 * 24).plusSeconds(60))),
                ">7×24h 应无提示");
    }

    // ==================== U2 恢复路径（锚点 29 部分；Controller 角色矩阵见 TenantControllerTest） ====================

    @Test
    void U2_edition非法_400() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateSubscription(TENANT_ID, "premium", null));
        assertEquals(400, ex.getCode(), "edition 非法必须 400（AC-F3.6）");
        assertTrue(ex.getMessage().contains("premium"), "message 指名非法值");
    }

    @Test
    void U2_两字段全null_40000() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateSubscription(TENANT_ID, null, null));
        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    void U2_expireTime格式非法_40000() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateSubscription(TENANT_ID, null, "2026/09/19 12:00:00"));
        assertEquals(ResultCode.BAD_REQUEST, ex.getCode(), "格式错误→40000");
    }

    @Test
    void U2_租户不存在_40400() {
        when(tenantMapper.selectById(999L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.updateSubscription(999L, "pro", null));
        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void U2_单字段更新成功_回显新值_审计含旧到新() {
        when(tenantMapper.selectById(TENANT_ID))
                .thenReturn(tenant("trial", LocalDateTime.now().minusDays(1))); // 旧：trial 已到期
        when(tenantMapper.update(any(), any())).thenReturn(1);

        TenantSubscriptionService.SubscriptionStatus status =
                service.updateSubscription(TENANT_ID, "pro", null);

        assertEquals("pro", status.getEdition(), "修改后回显新 edition");
        assertEquals("专业版", status.getEditionName());
        assertFalse(status.isTrial());
        assertFalse(status.isExpired(), "非 trial 恒不 expired");

        // 审计 tenant_edition_change：detail 含 oldEdition→newEdition 与操作者（AC-F3.6）。
        // 注：mock 接口上 default 方法同样被代理（不执行委托体），verify 匹配被测代码实际调用的 4 参形态
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("tenant_edition_change"), eq("tenant"), eq(String.valueOf(TENANT_ID)),
                detail.capture());
        assertTrue(detail.getValue().contains("\"oldEdition\":\"trial\""), "detail 含旧值");
        assertTrue(detail.getValue().contains("\"newEdition\":\"pro\""), "detail 含新值");
        assertTrue(detail.getValue().contains("\"operator\""), "detail 含操作者");
    }

    @Test
    void U2_恢复后_assertNotExpired放行_下次登录即生效() {
        // 旧状态：trial 已到期 → 拦截
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant("trial", LocalDateTime.now().minusDays(1)));
        assertThrows(BizException.class, () -> service.assertNotExpired(TENANT_ID));

        // platform_admin 恢复：edition 改 pro（改库即生效——服务零缓存，直查）
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant("pro", LocalDateTime.now().minusDays(1)));
        assertDoesNotThrow(() -> service.assertNotExpired(TENANT_ID), "恢复后放行（AC-F3.6）");

        // 恢复方式二：expire 延期 30 天（仍是 trial）
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant("trial", LocalDateTime.now().plusDays(30)));
        assertDoesNotThrow(() -> service.assertNotExpired(TENANT_ID), "延期后放行");
    }

    @Test
    void U1_getSubscriptionStatus_状态快照与登录口径同源() {
        when(tenantMapper.selectById(TENANT_ID))
                .thenReturn(tenant("trial", LocalDateTime.now().plusHours(3 * 24 - 1)));
        TenantSubscriptionService.SubscriptionStatus s = service.getSubscriptionStatus(TENANT_ID);
        assertTrue(s.isTrial());
        assertFalse(s.isExpired());
        assertEquals(3, s.getDaysLeft(), "daysLeft 与 buildTrialTip 同 ceil 口径");
        assertEquals("试用版", s.getEditionName());
        assertNotNull(s.getExpireTime());

        // 已到期 trial：expired=true + daysLeft=0
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant("trial", LocalDateTime.now().minusHours(1)));
        TenantSubscriptionService.SubscriptionStatus expired = service.getSubscriptionStatus(TENANT_ID);
        assertTrue(expired.isExpired());
        assertEquals(0, expired.getDaysLeft());

        // 租户不存在 → 40400
        when(tenantMapper.selectById(999L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.getSubscriptionStatus(999L));
        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void 防御_tenant为null或tenantId为null_放行() {
        assertDoesNotThrow(() -> service.assertNotExpired((Tenant) null));
        assertDoesNotThrow(() -> service.assertNotExpired((Long) null));
        assertNull(service.buildTrialTip(null));
    }
}
