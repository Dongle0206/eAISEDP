package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.entity.Permission;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Role;
import com.eaiselp.data.service.PermissionService;
import com.eaiselp.adapter.routing.entity.ModelRouting;
import com.eaiselp.adapter.routing.service.ModelRoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统管理 REST API（角色/权限/模型路由/配额的查询接口）。
 *
 * <p>这些管理类数据此前只有后端 Service 层，没有暴露 REST API。
 * 本 Controller 补齐前端页面所需的查询接口（只读为主，写入后续迭代）。</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SystemManageController {

    private final PermissionService permissionService;
    private final ModelRoutingService modelRoutingService;
    private final com.eaiselp.data.mapper.QuotaMapper quotaMapper;
    private final com.eaiselp.data.mapper.RoleMapper roleMapper;
    private final com.eaiselp.data.mapper.PermissionMapper permissionMapper;

    // ===== 角色管理 =====

    /** 查询所有角色（按角色码排序）。 */
    @GetMapping("/roles")
    @RequirePermission("role:view")
    public R<List<Role>> listRoles() {
        return R.ok(roleMapper.selectList(
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getRoleCode)));
    }

    // ===== 权限点 =====

    /** 查询所有权限点（按权限码排序）。 */
    @GetMapping("/permissions")
    @RequirePermission("role:view")
    public R<List<Permission>> listPermissions() {
        return R.ok(permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getPermissionCode)));
    }

    // ===== 模型路由 =====

    /** 查询所有模型路由配置（按 tier + priority 排序）。 */
    @GetMapping("/model-routing")
    @RequirePermission("artifact:view")
    public R<List<ModelRouting>> listModelRouting() {
        return R.ok(modelRoutingService.findAll());
    }

    // ===== 配额 =====

    /** 查询配额列表（按 period 倒序）。 */
    @GetMapping("/quotas")
    @RequirePermission("quota:view")
    public R<List<Quota>> listQuotas() {
        return R.ok(quotaMapper.selectList(
                new LambdaQueryWrapper<Quota>().orderByDesc(Quota::getPeriod)));
    }
}
