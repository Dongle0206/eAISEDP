package com.eaiselp.auth.service.impl;

import com.eaiselp.auth.dto.LoginRequest;
import com.eaiselp.auth.dto.LoginResponse;
import com.eaiselp.auth.dto.UserInfo;
import com.eaiselp.auth.service.AuthService;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.JwtUtil;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.mapper.UserMapper;
import com.eaiselp.data.service.PermissionService;
import com.eaiselp.data.service.TenantSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final PermissionService permissionService;
    private final JwtUtil jwtUtil;
    /** case-20260820 F3（T19）：试用到期判定（eaiselp-data 共享口径，PRD §4.3.1 唯一口径） */
    private final TenantSubscriptionService subscriptionService;
    /** case-20260820 F3（T19）：login_trial_blocked 拦截审计 */
    private final AuditService auditService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    /** 防枚举：用户不存在时用这个 dummy hash 跑一次 BCrypt，保证响应时长恒定 */
    private static final String DUMMY_HASH = "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final DateTimeFormatter EXPIRE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Phase 1 单租户 dogfooding：默认租户 ID。M3 多租户登录页选租户时改为动态。 */
    @Value("${eaiselp.security.default-tenant-id:1}")
    private Long defaultTenantId;

    @Override
    public LoginResponse login(LoginRequest req) {
        long start = System.currentTimeMillis();
        // 1. 按用户名查用户（#23 多租户：先全局按用户名查，取用户实际 tenantId；
        //    t_user 在 IGNORE_TABLES 不走租户拦截器，username 全局唯一）
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername())
                .last("LIMIT 1"));
        if (user == null) {
            // 兜底：老逻辑按默认租户再查一次（防全局查因历史脏数据 miss）
            user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getTenantId, defaultTenantId)
                    .eq(User::getUsername, req.getUsername()));
        }
        // 2-3. 用户不存在或密码错 → 统一 40001（防枚举，DFX 安全加固）
        //   用户不存在时也跑一次 dummy BCrypt 校验，保证响应时长恒定（防时间侧信道枚举）
        if (user == null) {
            passwordEncoder.matches(req.getPassword(), DUMMY_HASH); // 恒定时延
            log.info("[Login] 凭据错误: username={}, tenantId={}, 耗时={}ms",
                    req.getUsername(), defaultTenantId, System.currentTimeMillis() - start);
            throw new BizException(ResultCode.BAD_CREDENTIAL, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.info("[Login] 凭据错误: username={}, tenantId={}, 耗时={}ms",
                    req.getUsername(), defaultTenantId, System.currentTimeMillis() - start);
            throw new BizException(ResultCode.BAD_CREDENTIAL, "用户名或密码错误");
        }
        // 4. 账户禁用 → 40002
        if ("disabled".equalsIgnoreCase(user.getStatus())) {
            log.warn("[Login] 账户禁用: username={}", req.getUsername());
            throw new BizException(ResultCode.ACCOUNT_DISABLED, "账户已被禁用");
        }
        // 4.5 [T19 F3] 试用到期拦截（SE §4.2：账户禁用校验后、签发 JWT 前插入）。
        // 原⑥租户查询提前合并到此——全链路仍只查一次 t_tenant（登录耗时增幅可忽略，PRD §6.5）。
        // 防枚举顺序保持：本校验在凭据校验通过之后（错密码+到期租户 → 仍 40001，AC-F3.1 后附口径）。
        Tenant tenant = tenantMapper.selectById(user.getTenantId() != null ? user.getTenantId() : defaultTenantId);
        try {
            subscriptionService.assertNotExpired(tenant);
        } catch (BizException e) {
            // 审计 login_trial_blocked（AC-F3.1 断言点）：登录时无 JWT，AuditService 的 claims=null
            // 路径 tenantId 列兜底 0，故 tenantId 必须同时进 resource_id 与 detail 保证可检索。
            Long blockedTenantId = tenant != null && tenant.getId() != null ? tenant.getId() : user.getTenantId();
            String tenantIdStr = String.valueOf(blockedTenantId != null ? blockedTenantId : defaultTenantId);
            auditService.log("login_trial_blocked", "tenant", tenantIdStr,
                    "{\"tenantId\":" + tenantIdStr
                            + ",\"username\":\"" + safeJson(req.getUsername())
                            + "\",\"expireTime\":\"" + (tenant != null && tenant.getExpireTime() != null
                            ? tenant.getExpireTime().format(EXPIRE_FORMATTER) : "")
                            + "\",\"edition\":\"" + (tenant != null && tenant.getEdition() != null
                            ? tenant.getEdition() : "") + "\"}",
                    "failure", e.getMessage());
            log.warn("[Login] 试用到期拦截: username={}, tenantId={}, 耗时={}ms",
                    req.getUsername(), tenantIdStr, System.currentTimeMillis() - start);
            throw e; // ④.5 抛出即中断：不签发 JWT、不更新 last_login_at（AC-F3.1 Then）
        }
        // 5. 查角色 + 权限
        List<String> roleCodes = permissionService.getRoleCodesByUserId(user.getId());
        List<Long> roleIds = permissionService.getRoleIdsByUserId(user.getId());
        List<String> permissions = permissionService.getPermissionCodesByRoleIds(roleIds);
        // 6.（已提前合并至 4.5）tenantCode/tenantName 填 JWT payload + UserInfo——用用户实际租户
        String tenantCode = tenant != null ? tenant.getTenantCode() : null;
        String tenantName = tenant != null ? tenant.getTenantName() : null;
        // 7. 签发 JWT（不含 permissions，Q-5）
        JwtClaims claims = JwtClaims.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .tenantId(user.getTenantId())
                .tenantCode(tenantCode)
                .roles(roleCodes)
                .build();
        String token = jwtUtil.generate(claims);
        // 8. 更新 last_login_at（AC-F1.5）
        User update = new User();
        update.setId(user.getId());
        update.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(update);
        log.info("[Login] 登录成功: username={}, tenantId={}, roles={}, 耗时={}ms",
                user.getUsername(), user.getTenantId(), roleCodes, System.currentTimeMillis() - start);
        // 9. 返回（T19：trialTip 临期提示随成功响应返回，非 trial/无临期为 null 字段不出现，AC-F3.2）
        return LoginResponse.builder()
                .token(token)
                .expiresIn(jwtUtil.getExpireSeconds())
                .user(buildUserInfo(user, tenantName, roleCodes, permissions))
                .trialTip(subscriptionService.buildTrialTip(tenant))
                .build();
    }

    @Override
    public UserInfo currentUser(Long userId, Long tenantId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        Tenant tenant = tenantMapper.selectById(tenantId);
        List<String> roleCodes = permissionService.getRoleCodesByUserId(userId);
        List<Long> roleIds = permissionService.getRoleIdsByUserId(userId);
        List<String> permissions = permissionService.getPermissionCodesByRoleIds(roleIds);
        return buildUserInfo(user, tenant != null ? tenant.getTenantName() : null, roleCodes, permissions);
    }

    @Override
    public void logout(Long userId) {
        // M2 无状态：不维护黑名单（M3 做）。仅记录日志。
        log.info("[Logout] userId={}", userId);
    }

    /** 转义 JSON 字符串中的特殊字符，防止审计 detail 注入（同 AuthController.safeJson）。 */
    private String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private UserInfo buildUserInfo(User u, String tenantName, List<String> roles, List<String> permissions) {
        return UserInfo.builder()
                .id(u.getId())
                .username(u.getUsername())
                .displayName(u.getDisplayName())
                .email(u.getEmail())
                .tenantId(u.getTenantId())
                .tenantName(tenantName)
                .roles(roles)
                .roleCodes(roles)
                .permissions(permissions)
                .avatar(u.getAvatar())
                .build();
    }
}
