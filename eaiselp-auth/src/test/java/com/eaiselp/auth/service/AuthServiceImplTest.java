package com.eaiselp.auth.service;

import com.eaiselp.auth.dto.LoginRequest;
import com.eaiselp.auth.dto.LoginResponse;
import com.eaiselp.auth.dto.UserInfo;
import com.eaiselp.auth.service.impl.AuthServiceImpl;
import com.eaiselp.common.dto.TrialTipVo;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.JwtUtil;
import com.eaiselp.common.security.SecurityProperties;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.mapper.UserMapper;
import com.eaiselp.data.service.PermissionService;
import com.eaiselp.data.service.impl.TenantSubscriptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * AuthServiceImpl 登录主流程单测（Wave1，auth 模块首个单测）。
 *
 * <p>不启动 Spring 上下文（{@code @ExtendWith(MockitoExtension)} + {@code @Mock}），避免启动开销。
 * 三层依赖的 mock 策略：
 * <ul>
 *   <li>{@link UserMapper} / {@link TenantMapper} / {@link PermissionService} 是<b>接口</b>，
 *       用 {@code @Mock} 注入（接口 mock 不依赖 ByteBuddy 对具体类的字节码改写）。</li>
 *   <li>{@link JwtUtil} 是<b>具体类</b>。在 JDK 26 下 Mockito inline mock maker 对具体类的字节码插桩会失败
 *       （ByteBuddy "could not instrument all classes"），故改用<b>真实</b> {@code JwtUtil} 实例
 *       （手工 {@code init()} 建好 HS256 key）。这样测试不依赖 Mockito 对具体类的支持，跨 JDK 版本稳定，
 *       同时还能顺带验证签出的 token 可被 {@link JwtUtil#parse} 正确解析回 claims。</li>
 * </ul>
 *
 * <p>被测类内 {@code passwordEncoder} 内联初始化为真实 {@code BCryptPasswordEncoder(12)}，
 * 故密码校验走真实 BCrypt，验证"防枚举恒定时延"才有意义。</p>
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC1 正确凭据登录成功（token + user 信息）</li>
 *   <li>TC2 错误密码 → BizException(40001)</li>
 *   <li>TC3 用户不存在 → BizException(40001)（与错误密码同 code，防枚举）</li>
 *   <li>TC4 账户禁用 → BizException(40002)</li>
 *   <li>TC5 防枚举恒定时延（用户不存在时也跑了 dummy BCrypt，耗时接近正常登录）</li>
 *   <li>TC6 登录成功后 lastLoginAt 被更新</li>
 * </ul>
 *
 * <p>case-20260820 F3（T19，SE §9.1 锚点 6~9）——试用到期拦截扩展：
 * <ul>
 *   <li>TC7 到期拦截：trial+过期 → 40003、message 含「试用已到期」、不签 token、lastLoginAt 未更新、
 *       审计 login_trial_blocked（锚点 6，AC-F3.1）</li>
 *   <li>TC8 防枚举顺序：错密码+到期租户 → 40001 而非 40003（锚点 7）</li>
 *   <li>TC9 临期随登录返回：四档 trialTip 数值断言 + 非 trial 无提示（锚点 8，AC-F3.2/F3.3）</li>
 *   <li>TC1~TC6 原用例不动通过（锚点 9，登录主流程零回归）</li>
 * </ul>
 *
 * <p>F3 口径链路：被测类内 {@link TenantSubscriptionServiceImpl} 用<b>真实实例</b>
 * （包 mock TenantMapper），登录链路的到期判定/临期提示走真实口径（PRD §4.3.1），
 * 而非对口径本身的 mock——口径单测见 data 模块 TenantSubscriptionServiceTest。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock UserMapper userMapper;
    @Mock TenantMapper tenantMapper;
    @Mock PermissionService permissionService;
    @Mock AuditService auditService;

    /** 被测实例（手工构造：3 个接口 mock + 1 个真实 JwtUtil + 真实订阅口径服务）。 */
    private AuthServiceImpl authService;
    /** 真实 JwtUtil（手工 init），替代 mock，规避 JDK26 对具体类的插桩失败。 */
    private JwtUtil jwtUtil;
    /** 真实订阅口径服务（包 mock TenantMapper）：登录链路内到期判定/临期提示走真实实现。 */
    private TenantSubscriptionServiceImpl subscriptionService;

    /** 真实 BCrypt（与被测类内 cost=12 一致），用于预生成测试密码 hash。 */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    private static final String RAW_PASSWORD = "Test@12345";
    private static final String HASHED_PASSWORD = ENCODER.encode(RAW_PASSWORD);

    /** 被测类内 defaultTenantId 的默认值（@Value 缺省 = 1L），通过反射显式设定。 */
    private static final Long TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        // 构造真实 JwtUtil：SecurityProperties 自带 ≥32 字节的默认 secret，手工调 init() 建 key
        SecurityProperties props = new SecurityProperties();
        jwtUtil = new JwtUtil(props);
        jwtUtil.init(); // @PostConstruct 逻辑，无 Spring 时手动触发

        // 手工构造被测实例（不依赖 @InjectMocks，便于混入真实 JwtUtil 与真实订阅口径服务）
        subscriptionService = new TenantSubscriptionServiceImpl(tenantMapper, auditService);
        authService = new AuthServiceImpl(userMapper, tenantMapper, permissionService, jwtUtil,
                subscriptionService, auditService);
        // @Value 非 final 字段，无 Spring 时不会注入，显式设值
        ReflectionTestUtils.setField(authService, "defaultTenantId", TENANT_ID);
    }

    // ==================== TC1 登录成功 ====================

    /** TC1：正确凭据 → 返回 token + user 信息（roles/permissions 正确填充），且 token 可被解析回 claims。 */
    @Test
    void TC1_正确凭据_登录成功() {
        // given：mock 用户存在 + 密码匹配 + 状态正常
        User user = buildActiveUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(permissionService.getRoleCodesByUserId(user.getId())).thenReturn(List.of("ADMIN"));
        when(permissionService.getRoleIdsByUserId(user.getId())).thenReturn(List.of(10L));
        when(permissionService.getPermissionCodesByRoleIds(List.of(10L))).thenReturn(List.of("case:read"));
        Tenant tenant = buildTenant();
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(tenant);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword(RAW_PASSWORD);

        // when
        LoginResponse resp = authService.login(req);

        // then：token + user 信息正确
        assertNotNull(resp);
        assertNotNull(resp.getToken(), "登录成功应签发 token");
        assertTrue(resp.getToken().split("\\.").length == 3, "JWT 应为 header.payload.signature 三段");
        assertEquals(86400L, resp.getExpiresIn(), "expiresIn 应取 jwtUtil.getExpireSeconds()");

        UserInfo info = resp.getUser();
        assertNotNull(info);
        assertEquals(user.getId(), info.getId());
        assertEquals("alice", info.getUsername());
        assertEquals("Alice", info.getDisplayName());
        assertEquals("alice@eaiselp.com", info.getEmail());
        assertEquals(TENANT_ID, info.getTenantId());
        assertEquals("DefaultTenant", info.getTenantName());
        assertEquals(List.of("ADMIN"), info.getRoles());
        assertEquals(List.of("ADMIN"), info.getRoleCodes(), "roles 与 roleCodes 值应相同");
        assertEquals(List.of("case:read"), info.getPermissions());
        // T19 锚点 9 回归：非 trial 租户（buildTenant 未设 edition）登录响应无试用提示
        assertNull(resp.getTrialTip(), "非 trial 租户登录响应 trialTip 应为 null（AC-F3.3）");

        // 反向验证：签出的 token 能被真实 JwtUtil 解析回正确 claims（验证 claims 字段填充正确）
        JwtClaims claims = jwtUtil.parse(resp.getToken());
        assertEquals(user.getId(), claims.getUserId());
        assertEquals("alice", claims.getUsername());
        assertEquals("Alice", claims.getDisplayName());
        assertEquals(TENANT_ID, claims.getTenantId());
        assertEquals("TENANT_DEFAULT", claims.getTenantCode());
        assertEquals(List.of("ADMIN"), claims.getRoles());
        assertNotNull(claims.getIat(), "iat 应存在");
        assertNotNull(claims.getExp(), "exp 应存在");
        assertTrue(claims.getExp() > claims.getIat(), "exp 应晚于 iat");
    }

    // ==================== TC2 错误密码 ====================

    /** TC2：错误密码 → BizException code=40001，且未进入成功路径（未更新 lastLoginAt）。 */
    @Test
    void TC2_错误密码_抛40001() {
        User user = buildActiveUser();
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("WrongPassword");

        BizException ex = assertThrows(BizException.class, () -> authService.login(req));
        assertEquals(ResultCode.BAD_CREDENTIAL, ex.getCode(), "错误密码必须返回 40001");

        // 失败路径不应更新 lastLoginAt（成功路径才会 updateById）
        verify(userMapper, never()).updateById(any());
    }

    // ==================== TC3 用户不存在 ====================

    /** TC3：用户不存在 → 同样 BizException(40001)，与错误密码不可区分（防枚举）。 */
    @Test
    void TC3_用户不存在_同样抛40001_防枚举() {
        when(userMapper.selectOne(any())).thenReturn(null);

        LoginRequest req = new LoginRequest();
        req.setUsername("ghost");
        req.setPassword(RAW_PASSWORD);

        BizException ex = assertThrows(BizException.class, () -> authService.login(req));
        assertEquals(ResultCode.BAD_CREDENTIAL, ex.getCode(),
                "用户不存在必须返回与错误密码相同的 code(40001)，否则可被枚举");

        verify(userMapper, never()).updateById(any());
    }

    // ==================== TC4 账户禁用 ====================

    /** TC4：账户禁用 → BizException(40002)，且发生在密码校验通过之后（密码正确才走到 status 检查）。 */
    @Test
    void TC4_账户禁用_抛40002() {
        User user = buildActiveUser();
        user.setStatus("disabled"); // 密码正确但账户禁用
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword(RAW_PASSWORD);

        BizException ex = assertThrows(BizException.class, () -> authService.login(req));
        assertEquals(ResultCode.ACCOUNT_DISABLED, ex.getCode(), "账户禁用必须返回 40002");

        verify(userMapper, never()).updateById(any());
    }

    // ==================== TC5 防枚举恒定时延 ====================

    /**
     * TC5：防枚举恒定时延——用户不存在路径必须执行 dummy BCrypt（cost=12），
     * 耗时应与正常登录路径接近（同一数量级）。若恒定时延加固被移除，
     * 用户不存在路径耗时将 < 5ms（不走 BCrypt），本测试将失败。
     *
     * <p>不使用 {@code assertTimeoutPreemptive}（它针对上限，本测需断言下限），直接测时。</p>
     */
    @Test
    void TC5_防枚举_用户不存在时也执行了dummyBCrypt_耗时接近正常登录() {
        // 路径 A：用户不存在（应跑 dummy BCrypt）
        when(userMapper.selectOne(any())).thenReturn(null);
        LoginRequest ghostReq = new LoginRequest();
        ghostReq.setUsername("ghost");
        ghostReq.setPassword(RAW_PASSWORD);

        long tNull = timed(() -> {
            try { authService.login(ghostReq); }
            catch (BizException ignore) { /* 预期 */ }
        });

        // 路径 B：密码错误（真实 BCrypt 校验，作为"正常登录耗时"基准）
        reset(userMapper);
        User user = buildActiveUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        LoginRequest badReq = new LoginRequest();
        badReq.setUsername("alice");
        badReq.setPassword("WrongPassword");
        long tBadPwd = timed(() -> {
            try { authService.login(badReq); }
            catch (BizException ignore) { /* 预期 */ }
        });

        // 断言 1：用户不存在路径必须有 dummy BCrypt 的开销（cost=12 实测 >50ms）
        assertTrue(tNull > 50,
                "用户不存在路径必须执行 dummy BCrypt(cost=12)，实测耗时=" + tNull + "ms 应 >50ms；" +
                "若 <5ms 说明防枚举加固被破坏（未跑 dummy hash）");

        // 断言 2：两条路径耗时同一数量级（防时间侧信道枚举）。
        // 不要求完全相等（机器抖动），但差异不应超过 10 倍——一旦移除 dummy BCrypt，
        // tNull 会比 tBadPwd 小 50 倍以上。
        double ratio = (double) Math.max(tNull, tBadPwd) / Math.min(tNull, tBadPwd);
        assertTrue(ratio < 10.0,
                "防枚举恒定时延：两路径耗时比应 <10（实测 tNull=" + tNull + "ms, tBadPwd=" + tBadPwd + "ms, ratio=" + ratio + "）");
    }

    // ==================== TC6 登录后更新 lastLoginAt ====================

    /** TC6：登录成功后 lastLoginAt 被更新（AC-F1.5）。 */
    @Test
    void TC6_登录成功_lastLoginAt被更新() {
        User user = buildActiveUser();
        assertNull(user.getLastLoginAt(), "测试前置：lastLoginAt 应为 null");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(permissionService.getRoleCodesByUserId(anyLong())).thenReturn(List.of("ADMIN"));
        when(permissionService.getRoleIdsByUserId(anyLong())).thenReturn(List.of(10L));
        when(permissionService.getPermissionCodesByRoleIds(any())).thenReturn(List.of("case:read"));
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(buildTenant());

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword(RAW_PASSWORD);

        authService.login(req);

        // 验证：updateById 被调用一次，传入的 User 携带非空 lastLoginAt
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(1)).updateById(captor.capture());
        User updated = captor.getValue();
        assertEquals(user.getId(), updated.getId(), "应按主键更新");
        assertNotNull(updated.getLastLoginAt(), "lastLoginAt 必须被设置");
    }

    // ==================== TC7 到期拦截（T19 锚点 6，AC-F3.1） ====================

    /** TC7：trial+过期 → 40003、message 含「试用已到期」、不签 token（异常即返回）、lastLoginAt 未更新、审计 login_trial_blocked。 */
    @Test
    void TC7_试用到期_抛40003_不签token_不更新lastLoginAt_审计留痕() {
        User user = buildActiveUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        // 租户：trial，expire = 昨日（AC-F3.1 Given：expire_time=昨日 12:00 同构，相对法构造）
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(buildTrialTenant("trial", LocalDateTime.now().minusDays(1)));

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword(RAW_PASSWORD);

        BizException ex = assertThrows(BizException.class, () -> authService.login(req));
        assertEquals(ResultCode.TRIAL_EXPIRED, ex.getCode(), "到期登录必须 40003");
        assertTrue(ex.getMessage().contains("试用已到期"), "message 必含「试用已到期」");
        assertTrue(ex.getMessage().contains("升级"), "message 必含升级指引");

        // 不签发 JWT（异常中断）、不更新 last_login_at（AC-F3.1 Then）
        verify(userMapper, never()).updateById(any());

        // 审计 login_trial_blocked：resource_type=tenant、resource_id=tenantId、detail 含 username/expireTime
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("login_trial_blocked"), eq("tenant"), eq(String.valueOf(TENANT_ID)),
                detail.capture(), eq("failure"), contains("试用已到期"));
        assertTrue(detail.getValue().contains("\"tenantId\":" + TENANT_ID), "detail 含 tenantId（claims=null 时可检索）");
        assertTrue(detail.getValue().contains("\"username\":\"alice\""), "detail 含 username");
        assertTrue(detail.getValue().contains("\"edition\":\"trial\""), "detail 含 edition");
        assertFalse(detail.getValue().contains("\"expireTime\":\"\""), "detail 含 expireTime 非空回显");
    }

    // ==================== TC8 防枚举顺序（T19 锚点 7） ====================

    /** TC8：错密码 + 到期租户 → 40001 而非 40003（凭据校验先于到期校验，不泄露租户状态，PRD §4.3.1）。
     *  注：不桩 tenantMapper.selectById——凭据错误在 ④.5（租户查询）之前即抛出，租户状态根本未被读取。 */
    @Test
    void TC8_错密码加到期租户_仍40001防枚举() {
        User user = buildActiveUser();
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("WrongPassword");

        BizException ex = assertThrows(BizException.class, () -> authService.login(req));
        assertEquals(ResultCode.BAD_CREDENTIAL, ex.getCode(), "错密码+到期租户必须仍报 40001（防枚举顺序）");

        verify(auditService, never()).log(eq("login_trial_blocked"), any(), any(), any(), any(), any());
        verify(userMapper, never()).updateById(any());
    }

    // ==================== TC9 临期提示随登录返回（T19 锚点 8，AC-F3.2） ====================

    /** TC9a：T7 口径（expire=T+7×24h−1h）→ 登录成功 + trialTip{daysLeft=7, level=normal}。 */
    @Test
    void TC9a_临期7天_登录成功携带normal档提示() {
        LoginResponse resp = loginWithTrialExpire(LocalDateTime.now().plusHours(7 * 24 - 1));
        TrialTipVo tip = resp.getTrialTip();
        assertNotNull(tip, "T7（+7×24h−1h）登录响应应含 trialTip");
        assertEquals(7, tip.getDaysLeft(), "T7 剩余 7 天（N=ceil）");
        assertEquals(TrialTipVo.LEVEL_NORMAL, tip.getLevel());
        assertNotNull(tip.getExpireTime());
        assertNotNull(resp.getToken(), "临期不影响登录成功（含 token）");
    }

    /** TC9b：T3 口径（expire=T+3×24h−1h）→ trialTip{daysLeft=3, level=warning}（黄色档）。 */
    @Test
    void TC9b_临期3天_登录成功携带warning档提示() {
        LoginResponse resp = loginWithTrialExpire(LocalDateTime.now().plusHours(3 * 24 - 1));
        assertEquals(3, resp.getTrialTip().getDaysLeft(), "T3 剩余 3 天");
        assertEquals(TrialTipVo.LEVEL_WARNING, resp.getTrialTip().getLevel());
    }

    /** TC9c：T1 口径（expire=T+7h）→ trialTip{daysLeft=1, level=critical}（红色档）。 */
    @Test
    void TC9c_临期7小时_登录成功携带critical档提示() {
        LoginResponse resp = loginWithTrialExpire(LocalDateTime.now().plusHours(7));
        assertEquals(1, resp.getTrialTip().getDaysLeft(), "T1 剩余 1 天（不满一天算 1 天）");
        assertEquals(TrialTipVo.LEVEL_CRITICAL, resp.getTrialTip().getLevel());
    }

    /** TC9d：T8 口径（expire=T+8×24h，超出提示窗口）与正式版 → trialTip=null。 */
    @Test
    void TC9d_超窗口与正式版_无trialTip() {
        // T8：+8×24h > 7×24h → 无提示
        LoginResponse t8 = loginWithTrialExpire(LocalDateTime.now().plusHours(8 * 24));
        assertNull(t8.getTrialTip(), "T8（+8×24h）登录响应无 trialTip");

        // 正式版：expire 已过仍无任何提示/拦截（AC-F3.3，expire 完全忽略）
        User user = buildActiveUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(permissionService.getRoleCodesByUserId(anyLong())).thenReturn(List.of("ADMIN"));
        when(permissionService.getRoleIdsByUserId(anyLong())).thenReturn(List.of(10L));
        when(permissionService.getPermissionCodesByRoleIds(any())).thenReturn(List.of("case:read"));
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(buildTrialTenant("pro", LocalDateTime.now().minusDays(30)));

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword(RAW_PASSWORD);
        LoginResponse resp = authService.login(req);

        assertNotNull(resp.getToken(), "正式版过期 30 天仍正常登录（豁免）");
        assertNull(resp.getTrialTip(), "正式版无试用提示");
    }

    /** TC9 辅助：构造 trial 租户 + 指定 expire，走完整登录成功链路（真实订阅口径服务）。 */
    private LoginResponse loginWithTrialExpire(LocalDateTime expire) {
        User user = buildActiveUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(permissionService.getRoleCodesByUserId(anyLong())).thenReturn(List.of("ADMIN"));
        when(permissionService.getRoleIdsByUserId(anyLong())).thenReturn(List.of(10L));
        when(permissionService.getPermissionCodesByRoleIds(any())).thenReturn(List.of("case:read"));
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(buildTrialTenant("trial", expire));

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword(RAW_PASSWORD);
        return authService.login(req);
    }

    // ==================== fixtures ====================

    private User buildActiveUser() {
        User u = new User();
        u.setId(1001L);
        u.setTenantId(TENANT_ID);
        u.setUsername("alice");
        u.setPassword(HASHED_PASSWORD); // 真实 BCrypt(cost=12) hash
        u.setDisplayName("Alice");
        u.setEmail("alice@eaiselp.com");
        u.setStatus("active");
        return u;
    }

    private Tenant buildTenant() {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setTenantCode("TENANT_DEFAULT");
        t.setTenantName("DefaultTenant");
        return t;
    }

    /** F3 用：指定 edition/expireTime 的租户（buildTenant 未设 edition，走豁免分支）。 */
    private Tenant buildTrialTenant(String edition, LocalDateTime expireTime) {
        Tenant t = buildTenant();
        t.setEdition(edition);
        t.setExpireTime(expireTime);
        return t;
    }

    /** 测量一段代码的执行耗时（毫秒）。 */
    private static long timed(Runnable r) {
        long start = System.currentTimeMillis();
        r.run();
        return System.currentTimeMillis() - start;
    }
}
