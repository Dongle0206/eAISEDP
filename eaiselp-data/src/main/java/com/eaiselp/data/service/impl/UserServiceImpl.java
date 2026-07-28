package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.data.entity.Role;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.entity.UserRole;
import com.eaiselp.data.mapper.RoleMapper;
import com.eaiselp.data.mapper.UserMapper;
import com.eaiselp.data.mapper.UserRoleMapper;
import com.eaiselp.data.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户管理服务实现（M3-3）。
 *
 * <p><b>BCryptPasswordEncoder</b>：本类直接 new（与 AuthServiceImpl 一致，强度 12），
 * 不抽成 bean 避免引完整 spring-security 自动配置链（与 JWT 无状态冲突）。
 *
 * <p><b>角色同步双写</b>（Q-1 同步策略，与 AuthServiceImpl 对齐）：
 * <ul>
 *   <li>{@code t_user_role} 是权威源（登录时 PermissionService 读此表聚合角色）。</li>
 *   <li>{@code t_user.roles} 冗余字符串（逗号分隔）用于历史兼容与快速展示。</li>
 *   <li>分配角色时两处同步：删旧 t_user_role + 插新 t_user_role + 回填 t_user.roles。</li>
 * </ul>
 *
 * <p><b>覆盖式角色分配</b>：assignRoles 先按 (tenant_id, user_id) 删除旧关联再插新关联，
 * 不做增量 diff（增量 upsert 在角色集合场景复杂度高，覆盖式更直观且幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    /** 与 AuthServiceImpl 一致：strength=12（约 250ms/次，防暴力枚举）。 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    @Override
    public IPage<User> page(Page<User> page, Long tenantId, String status) {
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .orderByDesc(User::getCreateTime);
        if (status != null && !status.isBlank()) {
            qw.eq(User::getStatus, status.trim());
        }
        return userMapper.selectPage(page, qw);
    }

    @Override
    public User getById(Long tenantId, Long userId) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getId, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User create(Long tenantId, String username, String rawPassword, String displayName,
                       String email, String phone, List<String> roleCodes) {
        // 1. 用户名唯一性校验（按 tenantId 隔离）
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            throw new BizException(ResultCode.BAD_REQUEST, "用户名已存在: " + username);
        }
        // 2. BCrypt 加密密码
        User u = new User();
        u.setTenantId(tenantId);
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setDisplayName(displayName);
        u.setEmail(email);
        u.setPhone(phone);
        u.setStatus("active");
        // 3. 同步角色（回填 roles 冗余字符串 + 写 t_user_role 关联表）
        String rolesStr = roleCodes == null ? "" : String.join(",", roleCodes);
        u.setRoles(rolesStr);
        userMapper.insert(u);
        // 4. 写 t_user_role 关联表（角色码 → role_id）
        if (roleCodes != null && !roleCodes.isEmpty()) {
            List<Long> roleIds = getRoleIdsByCodes(roleCodes);
            for (Long roleId : roleIds) {
                UserRole ur = new UserRole();
                ur.setTenantId(tenantId);
                ur.setUserId(u.getId());
                ur.setRoleId(roleId);
                ur.setCreateBy("system-admin");
                userRoleMapper.insert(ur);
            }
        }
        log.info("[User] 创建用户: tenantId={}, username={}, userId={}, roles={}",
                tenantId, username, u.getId(), rolesStr);
        // 返回时清密码（避免泄漏到 API 响应）
        u.setPassword(null);
        return u;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User update(Long tenantId, Long userId, String displayName, String status, List<String> roleCodes) {
        User exists = getById(tenantId, userId);
        if (exists == null) {
            throw new BizException(ResultCode.NOT_FOUND, "用户不存在: userId=" + userId);
        }
        User update = new User();
        update.setId(userId);
        update.setTenantId(tenantId);   // 显式带 tenantId 防越权改其他租户用户
        if (displayName != null) update.setDisplayName(displayName);
        if (status != null && !status.isBlank()) update.setStatus(status.trim());
        userMapper.updateById(update);
        // 角色同步（roles 非空时覆盖式更新）
        if (roleCodes != null) {
            assignRoles(tenantId, userId, roleCodes);
        }
        log.info("[User] 更新用户: tenantId={}, userId={}, status={}, roles={}",
                tenantId, userId, status, roleCodes);
        return getById(tenantId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean disable(Long tenantId, Long userId) {
        User exists = getById(tenantId, userId);
        if (exists == null) {
            return false;
        }
        // 禁用：只改 status，不动其他字段（GRC 数据保留要求）
        User update = new User();
        update.setId(userId);
        update.setTenantId(tenantId);
        update.setStatus("disabled");
        userMapper.updateById(update);
        log.info("[User] 禁用用户: tenantId={}, userId={}", tenantId, userId);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignRoles(Long tenantId, Long userId, List<String> roleCodes) {
        User exists = getById(tenantId, userId);
        if (exists == null) {
            return false;
        }
        // 1. 覆盖式：先删旧关联（按 tenant_id + user_id，避免误删其他租户）
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getUserId, userId));
        // 2. 插新关联 + 收集成功分配的 roleCode（过滤掉找不到的角色码）
        List<String> assignedCodes = new ArrayList<>();
        if (roleCodes != null && !roleCodes.isEmpty()) {
            List<Long> roleIds = getRoleIdsByCodes(roleCodes);
            // roleIds 已去重（getRoleIdsByCodes 内部 distinct），按 roleCodes 顺序映射回 code
            // 这里用简单方式：遍历 roleCodes 查对应 roleId，存在的才插
            List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                    .in(Role::getRoleCode, roleCodes));
            for (Role r : roles) {
                UserRole ur = new UserRole();
                ur.setTenantId(tenantId);
                ur.setUserId(userId);
                ur.setRoleId(r.getId());
                ur.setCreateBy("system-admin");
                userRoleMapper.insert(ur);
                assignedCodes.add(r.getRoleCode());
            }
        }
        // 3. 回填 t_user.roles 冗余字符串
        User update = new User();
        update.setId(userId);
        update.setTenantId(tenantId);
        update.setRoles(String.join(",", assignedCodes));
        userMapper.updateById(update);
        log.info("[User] 分配角色: tenantId={}, userId={}, roles={}", tenantId, userId, assignedCodes);
        return true;
    }

    @Override
    public List<Long> getRoleIdsByCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return Collections.emptyList();
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .in(Role::getRoleCode, roleCodes));
        return roles.stream().map(Role::getId).distinct().collect(Collectors.toList());
    }
}
