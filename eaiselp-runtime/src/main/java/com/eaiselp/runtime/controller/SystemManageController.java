package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.entity.Permission;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Role;
import com.eaiselp.data.entity.RolePermission;
import com.eaiselp.data.service.PermissionService;
import com.eaiselp.adapter.routing.entity.ModelRouting;
import com.eaiselp.adapter.routing.service.ModelRoutingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统管理 REST API（角色/权限/模型路由/配额的查询 + 写操作）。
 *
 * <p>写操作补齐管理闭环（#5/#6/#7）：
 * 模型路由可在线切换（换模型不改数据库）、配额可调整、自定义角色可创建/编辑/删除。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SystemManageController {

    private final PermissionService permissionService;
    private final ModelRoutingService modelRoutingService;
    private final com.eaiselp.data.mapper.QuotaMapper quotaMapper;
    private final com.eaiselp.data.mapper.RoleMapper roleMapper;
    private final com.eaiselp.data.mapper.PermissionMapper permissionMapper;
    private final com.eaiselp.data.mapper.RolePermissionMapper rolePermissionMapper;
    private final com.eaiselp.adapter.routing.mapper.ModelRoutingMapper modelRoutingMapper;
    private final com.eaiselp.data.mapper.DerivationMapper derivationMapper;

    // ===== 角色管理 =====

    /** 查询所有角色（按角色码排序）。 */
    @GetMapping("/roles")
    @RequirePermission("role:view")
    public R<List<Role>> listRoles() {
        return R.ok(roleMapper.selectList(
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getRoleCode)));
    }

    /** 创建自定义角色（is_built_in=0，可删除）。 */
    @PostMapping("/roles")
    @RequirePermission("role:view")
    public R<Role> createRole(@RequestBody Role role) {
        if (role.getRoleCode() == null || role.getRoleCode().isBlank()
                || role.getRoleName() == null || role.getRoleName().isBlank()) {
            return R.fail(400, "roleCode 和 roleName 不能为空");
        }
        // 查重
        Long cnt = roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, role.getRoleCode())).longValue();
        if (cnt > 0) return R.fail(409, "角色码已存在: " + role.getRoleCode());
        role.setId(null);
        role.setRoleType("custom");
        role.setIsBuiltIn(0);
        if (role.getDataScope() == null) role.setDataScope("tenant");
        roleMapper.insert(role);
        return R.ok(role);
    }

    /** 更新角色（预置角色只允许改名称/描述，不允许改角色码）。 */
    @PutMapping("/roles/{id}")
    @RequirePermission("role:view")
    public R<Role> updateRole(@PathVariable Long id, @RequestBody Role req) {
        Role db = roleMapper.selectById(id);
        if (db == null) return R.fail(404, "角色不存在: " + id);
        db.setRoleName(req.getRoleName() != null ? req.getRoleName() : db.getRoleName());
        db.setDescription(req.getDescription() != null ? req.getDescription() : db.getDescription());
        if (db.getIsBuiltIn() == null || db.getIsBuiltIn() == 0) {
            // 自定义角色允许改数据范围
            db.setDataScope(req.getDataScope() != null ? req.getDataScope() : db.getDataScope());
        }
        roleMapper.updateById(db);
        return R.ok(db);
    }

    /** 删除自定义角色（预置角色 is_built_in=1 不可删）。 */
    @DeleteMapping("/roles/{id}")
    @RequirePermission("role:view")
    public R<Void> deleteRole(@PathVariable Long id) {
        Role db = roleMapper.selectById(id);
        if (db == null) return R.fail(404, "角色不存在: " + id);
        if (db.getIsBuiltIn() != null && db.getIsBuiltIn() == 1) {
            return R.fail(403, "系统预置角色不可删除");
        }
        // 清理角色-权限关联
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, id));
        roleMapper.deleteById(id);
        return R.ok();
    }

    /** 设置角色权限（覆盖式：全量替换角色-权限关联）。 */
    @PutMapping("/roles/{id}/permissions")
    @RequirePermission("role:view")
    public R<Void> setRolePermissions(@PathVariable Long id, @RequestBody PermissionIdsRequest req) {
        Role db = roleMapper.selectById(id);
        if (db == null) return R.fail(404, "角色不存在: " + id);
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, id));
        if (req.getPermissionIds() != null) {
            for (Long pid : req.getPermissionIds()) {
                RolePermission rp = new RolePermission();
                rp.setRoleId(id);
                rp.setPermissionId(pid);
                rolePermissionMapper.insert(rp);
            }
        }
        return R.ok();
    }

    /** 查询角色已有的权限 ID 列表。 */
    @GetMapping("/roles/{id}/permissions")
    @RequirePermission("role:view")
    public R<List<Long>> getRolePermissions(@PathVariable Long id) {
        List<Long> pids = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id))
                .stream().map(RolePermission::getPermissionId).toList();
        return R.ok(pids);
    }

    @Data
    public static class PermissionIdsRequest {
        private List<Long> permissionIds;
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

    /** 更新模型路由（#6：在线切换模型/调优先级/启停——换模型不改数据库）。 */
    @PutMapping("/model-routing/{id}")
    @RequirePermission("artifact:view")
    public R<ModelRouting> updateRouting(@PathVariable Long id, @RequestBody ModelRouting req) {
        ModelRouting db = modelRoutingMapper.selectById(id);
        if (db == null) return R.fail(404, "路由不存在: " + id);
        // 允许更新：模型名/优先级/启停/base_url/api_key_env
        if (req.getModel() != null && !req.getModel().isBlank()) db.setModel(req.getModel());
        if (req.getPriority() != null) db.setPriority(req.getPriority());
        if (req.getEnabled() != null) db.setEnabled(req.getEnabled());
        if (req.getBaseUrl() != null) db.setBaseUrl(req.getBaseUrl());
        if (req.getApiKeyEnv() != null) db.setApiKeyEnv(req.getApiKeyEnv());
        modelRoutingMapper.updateById(db);
        log.info("[ModelRouting] 更新路由 id={} tier={} → model={}, enabled={}",
                id, db.getTier(), db.getModel(), db.getEnabled());
        return R.ok(db);
    }

    /** 新增模型路由（同档位多 provider 备选）。 */
    @PostMapping("/model-routing")
    @RequirePermission("artifact:view")
    public R<ModelRouting> createRouting(@RequestBody ModelRouting req) {
        if (req.getTier() == null || req.getModel() == null || req.getProvider() == null) {
            return R.fail(400, "tier/provider/model 不能为空");
        }
        req.setId(null);
        if (req.getPriority() == null) req.setPriority(100);
        if (req.getEnabled() == null) req.setEnabled(1);
        modelRoutingMapper.insert(req);
        return R.ok(req);
    }

    // ===== 配额 =====

    /** 查询配额列表（按 period 倒序）。 */
    @GetMapping("/quotas")
    @RequirePermission("quota:view")
    public R<List<Quota>> listQuotas() {
        return R.ok(quotaMapper.selectList(
                new LambdaQueryWrapper<Quota>().orderByDesc(Quota::getPeriod)));
    }

    /** 更新配额（#7：在线调整租户月度额度）。 */
    @PutMapping("/quotas/{id}")
    @RequirePermission("quota:view")
    public R<Quota> updateQuota(@PathVariable Long id, @RequestBody Quota req) {
        Quota db = quotaMapper.selectById(id);
        if (db == null) return R.fail(404, "配额不存在: " + id);
        if (req.getDerivationLimit() != null) db.setDerivationLimit(req.getDerivationLimit());
        if (req.getTokenLimit() != null) db.setTokenLimit(req.getTokenLimit());
        quotaMapper.updateById(db);
        log.info("[Quota] 更新配额 id={} tenant={} → derivationLimit={}, tokenLimit={}",
                id, db.getTenantId(), db.getDerivationLimit(), db.getTokenLimit());
        return R.ok(db);
    }

    // ===== 统计报表（#26） =====

    /** 月度使用报表：派生次数/token/成功率 按月聚合。 */
    @GetMapping("/report/monthly")
    @RequirePermission("artifact:view")
    public R<java.util.List<java.util.Map<String, Object>>> monthlyReport() {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.eaiselp.data.entity.Derivation>()
                .select("DATE_FORMAT(create_time,'%Y-%m') AS month",
                        "COUNT(*) AS derivationCount",
                        "IFNULL(SUM(input_tokens),0)+IFNULL(SUM(output_tokens),0) AS tokenTotal",
                        "SUM(CASE WHEN status='success' THEN 1 ELSE 0 END) AS successCount")
                .groupBy("DATE_FORMAT(create_time,'%Y-%m')")
                .orderByDesc("month");
        return R.ok(derivationMapper.selectMaps(wrapper));
    }

    /** 按角色统计（当前租户）。 */
    @GetMapping("/report/by-role")
    @RequirePermission("artifact:view")
    public R<java.util.List<java.util.Map<String, Object>>> reportByRole() {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.eaiselp.data.entity.Derivation>()
                .select("role", "COUNT(*) AS count",
                        "IFNULL(SUM(input_tokens),0)+IFNULL(SUM(output_tokens),0) AS tokenTotal",
                        "AVG(duration_ms) AS avgDurationMs")
                .groupBy("role")
                .orderByDesc("count");
        return R.ok(derivationMapper.selectMaps(wrapper));
    }
}
