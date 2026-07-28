package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.data.entity.User;

import java.util.List;

/**
 * 用户管理聚合服务（M3-3）。
 *
 * <p>封装用户 CRUD + 角色分配的事务性操作：
 * <ul>
 *   <li>创建用户：BCrypt 加密密码 + 写 t_user + 同步 t_user_role + 回填 t_user.roles 冗余字段。</li>
 *   <li>更新用户：更新基本信息（不含密码）+ 可选同步角色。</li>
 *   <li>禁用用户：status=disabled，不物理删除（GRC 数据保留要求）。</li>
 *   <li>分配角色：先删后插 t_user_role + 回填 t_user.roles 冗余字符串。</li>
 * </ul>
 *
 * <p><b>多租户</b>：t_user 在 {@code IGNORE_TABLES}（按 (tenant_id, username) 唯一），
 * 所有方法显式接收 tenantId 参数做隔离，不依赖拦截器自动注入（防客户端伪造，ES-003 §9.3 G13）。
 */
public interface UserService {

    /** 分页查询用户（按 tenantId 隔离，可选 status 过滤）。 */
    IPage<User> page(Page<User> page, Long tenantId, String status);

    /** 用户详情（按 tenantId 隔离）。 */
    User getById(Long tenantId, Long userId);

    /** 创建用户（BCrypt 加密密码 + 同步角色）。返回创建后的 User（不含密码）。 */
    User create(Long tenantId, String username, String rawPassword, String displayName,
                String email, String phone, List<String> roleCodes);

    /** 更新用户基本信息（不含密码；roles 非空时同步角色）。 */
    User update(Long tenantId, Long userId, String displayName, String status, List<String> roleCodes);

    /** 禁用用户（status=disabled，不物理删除）。 */
    boolean disable(Long tenantId, Long userId);

    /** 分配角色（覆盖式：先删旧关联再插新关联，回填 t_user.roles 冗余字段）。 */
    boolean assignRoles(Long tenantId, Long userId, List<String> roleCodes);

    /** 按 username 查用户的所有角色 ID（去重）。 */
    List<Long> getRoleIdsByCodes(List<String> roleCodes);
}
