package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理 CRUD REST API（M3-3）。
 *
 * <p>提供用户列表 / 详情 / 创建 / 更新 / 禁用 / 角色分配 6 个端点。
 *
 * <p><b>路径</b>：{@code /api/v1/users}（ES-003 §9.4 P13，新增 API 强制 /v1/ 前缀）。
 *
 * <p><b>权限</b>（对齐 schema 已有权限码 user:view/create/update/delete，PermissionInterceptor 拦截）：
 * <ul>
 *   <li>读类（列表/详情）：{@code user:view}</li>
 *   <li>创建：{@code user:create}</li>
 *   <li>更新/禁用/分配角色：{@code user:update}（禁用属 update 域，不单独 user:delete，避免改 schema）</li>
 * </ul>
 * 注：任务清单写 {@code user:delete}，但 schema 的 user 域权限码是 user:view/create/update/edit/disable
 * （无 user:delete，因禁用而非物理删除）。这里禁用用 {@code user:update}（语义最贴近，避免改 schema.sql）。
 *
 * <p><b>多租户隔离</b>：tenantId 从 LoginUser（JWT claims）取，不从请求参数取（防客户端伪造，ES-003 §9.3 G13）。
 *
 * <p><b>审计</b>：创建/更新/禁用/分配角色均记录审计日志（GRC 治理：用户管理操作可追溯）。
 *
 * <p><b>密码</b>：BCrypt 加密（{@code BCryptPasswordEncoder}，与 AuthServiceImpl 一致 strength=12）；
 * API 响应从不返回 password 字段（UserService 内部已清空）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuditService auditService;

    // ======================== 读类 ========================

    /** 分页查询用户列表（可选 status 过滤）。 */
    @GetMapping
    @RequirePermission("user:view")
    public R<IPage<User>> page(@RequestParam(defaultValue = "1") long page,
                               @RequestParam(defaultValue = "20") long size,
                               @RequestParam(required = false) String status) {
        Long tenantId = currentTenantId();
        Page<User> p = new Page<>(page, size);
        return R.ok(userService.page(p, tenantId, status));
    }

    /** 用户详情。 */
    @GetMapping("/{id}")
    @RequirePermission("user:view")
    public R<User> get(@PathVariable Long id) {
        User u = userService.getById(currentTenantId(), id);
        if (u == null) return R.fail(404, "用户不存在: userId=" + id);
        u.setPassword(null);   // 双保险：响应不返回密码
        return R.ok(u);
    }

    // ======================== 写类 ========================

    /** 创建用户（username+password+displayName+roles）。 */
    @PostMapping
    @RequirePermission("user:create")
    public R<User> create(@RequestBody CreateUserRequest req) {
        if (req.getUsername() == null || req.getUsername().trim().isEmpty()) {
            return R.fail(400, "username 不能为空");
        }
        if (req.getPassword() == null || req.getPassword().length() < 6) {
            return R.fail(400, "password 不能为空且长度至少 6 位");
        }
        User u = userService.create(currentTenantId(), req.getUsername().trim(),
                req.getPassword(), req.getDisplayName(), req.getEmail(),
                req.getPhone(), req.getRoles());
        // 审计：用户创建
        auditService.log("user_create", "user", String.valueOf(u.getId()),
                "{\"username\":\"" + safeJson(u.getUsername())
                        + "\",\"roles\":\"" + safeJson(String.join(",", req.getRoles() == null ? List.of() : req.getRoles()))
                        + "\"}");
        return R.ok(u);
    }

    /** 更新用户（displayName/status/roles；roles 非空时同步角色）。 */
    @PutMapping("/{id}")
    @RequirePermission("user:update")
    public R<User> update(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
        User u = userService.update(currentTenantId(), id,
                req.getDisplayName(), req.getStatus(), req.getRoles());
        // 审计：用户更新
        auditService.log("user_update", "user", String.valueOf(id),
                "{\"status\":\"" + safeJson(req.getStatus())
                        + "\",\"rolesUpdated\":" + (req.getRoles() != null) + "}");
        u.setPassword(null);
        return R.ok(u);
    }

    /** 禁用用户（status=disabled，不物理删除）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("user:update")
    public R<Void> disable(@PathVariable Long id) {
        boolean ok = userService.disable(currentTenantId(), id);
        if (!ok) return R.fail(404, "用户不存在: userId=" + id);
        // 审计：用户禁用（GRC 关键审计点：账户状态变更）
        auditService.log("user_disable", "user", String.valueOf(id), null);
        return R.ok();
    }

    /** 分配角色（覆盖式）。 */
    @PostMapping("/{id}/roles")
    @RequirePermission("user:update")
    public R<Void> assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest req) {
        if (req.getRoleCodes() == null) {
            return R.fail(400, "roleCodes 不能为空（可为空数组表示清空角色）");
        }
        boolean ok = userService.assignRoles(currentTenantId(), id, req.getRoleCodes());
        if (!ok) return R.fail(404, "用户不存在: userId=" + id);
        // 审计：角色分配（GRC 关键审计点：权限变更）
        auditService.log("user_assign_roles", "user", String.valueOf(id),
                "{\"roles\":\"" + safeJson(String.join(",", req.getRoleCodes())) + "\"}");
        return R.ok();
    }

    // ======================== 辅助 ========================

    /**
     * 从 JWT claims 取 tenantId（防客户端伪造，ES-003 §9.3 G13）。
     * 未登录返回 0（正常情况下 JWT 拦截器已挡住，此处兜底防 NPE）。
     */
    private Long currentTenantId() {
        JwtClaims claims = LoginUser.get();
        return claims != null && claims.getTenantId() != null ? claims.getTenantId() : 0L;
    }

    /** 转义 JSON 字符串中的特殊字符（防 detail 注入）。 */
    private String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ======================== 请求体 DTO ========================

    @Data
    public static class CreateUserRequest {
        private String username;
        private String password;
        private String displayName;
        private String email;
        private String phone;
        /** 角色码数组（如 ["tenant_admin","project_manager"]）。 */
        private List<String> roles;
    }

    @Data
    public static class UpdateUserRequest {
        private String displayName;
        /** 状态：active/disabled。 */
        private String status;
        /** 角色码数组（非空时同步角色，null 表示不改角色）。 */
        private List<String> roles;
    }

    @Data
    public static class AssignRolesRequest {
        /** 角色码数组（空数组表示清空角色）。 */
        private List<String> roleCodes;
    }
}
