package com.eaiselp.data.service;

import java.util.Collection;
import java.util.List;

/** 权限聚合服务：按 userId 查角色 + 权限；按角色码集合校验某权限。 */
public interface PermissionService {
    /** 查用户的所有角色码（去重）。 */
    List<String> getRoleCodesByUserId(Long userId);
    /** 查用户的所有角色 ID（去重，用于聚合权限）。 */
    List<Long> getRoleIdsByUserId(Long userId);
    /** 按角色 ID 集合查权限码（去重并集）。 */
    List<String> getPermissionCodesByRoleIds(Collection<Long> roleIds);
    /** 校验用户的角色集合是否拥有指定权限码（任一角色持有即 true）。 */
    boolean hasAnyPermission(Collection<Long> roleIds, String permissionCode);
    /** 便捷：按 userId 直接校验权限。 */
    boolean hasPermission(Long userId, String permissionCode);
}
