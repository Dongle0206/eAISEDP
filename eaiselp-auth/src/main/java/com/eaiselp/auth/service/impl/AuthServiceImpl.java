package com.eaiselp.auth.service.impl;

import com.eaiselp.auth.dto.LoginRequest;
import com.eaiselp.auth.dto.LoginResponse;
import com.eaiselp.auth.dto.UserInfo;
import com.eaiselp.auth.service.AuthService;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.JwtUtil;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.mapper.UserMapper;
import com.eaiselp.data.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final PermissionService permissionService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** Phase 1 单租户 dogfooding：默认租户 ID。M3 多租户登录页选租户时改为动态。 */
    @Value("${eaiselp.security.default-tenant-id:1}")
    private Long defaultTenantId;

    @Override
    public LoginResponse login(LoginRequest req) {
        long start = System.currentTimeMillis();
        // 1. 按 (tenant_id, username) 查用户。t_user 在 IGNORE_TABLES，需显式带 tenant_id 条件
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, defaultTenantId)
                .eq(User::getUsername, req.getUsername()));
        // 2-3. 用户不存在或密码错 → 统一 40001（防枚举，PRD §5.1.3 安全约定）
        //   即便 user==null 也要走一次 BCrypt 校验（恒定时间，避免通过响应时长区分用户存在性）——
        //   Phase 1 简化：null 直接返回，响应时长差异在 dogfooding 内网可接受；M3 加恒定时延。
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.info("[Login] 凭据错误: username={}, tenantId={}, 耗时={}ms",
                    req.getUsername(), defaultTenantId, System.currentTimeMillis() - start);
            throw new BizException(ResultCode.BAD_CREDENTIAL, "用户名或密码错误");
        }
        // 4. 账户禁用 → 40002
        if ("disabled".equalsIgnoreCase(user.getStatus())) {
            log.warn("[Login] 账户禁用: username={}", req.getUsername());
            throw new BizException(ResultCode.ACCOUNT_DISABLED, "账户已被禁用");
        }
        // 5. 查角色 + 权限
        List<String> roleCodes = permissionService.getRoleCodesByUserId(user.getId());
        List<Long> roleIds = permissionService.getRoleIdsByUserId(user.getId());
        List<String> permissions = permissionService.getPermissionCodesByRoleIds(roleIds);
        // 6. 查租户（取 tenantCode/tenantName 填 JWT payload + UserInfo）
        Tenant tenant = tenantMapper.selectById(defaultTenantId);
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
        // 9. 返回
        return LoginResponse.builder()
                .token(token)
                .expiresIn(jwtUtil.getExpireSeconds())
                .user(buildUserInfo(user, tenantName, roleCodes, permissions))
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
