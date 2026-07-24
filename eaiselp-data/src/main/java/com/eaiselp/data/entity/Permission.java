package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限原子（系统级，所有租户共享，tenant_id 恒为 0）。
 * 已加入 EaiselpTenantHandler.IGNORE_TABLES 免 tenant 自动过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_permission")
public class Permission extends BaseEntity {
    private String permissionCode;   // 权限码，如 user:create（UNIQUE）
    private String permissionName;   // 中文名
    private String module;           // 模块：user/tenant/system/...
    private String resourceType;     // 资源类型
    private String action;           // 动作：view/create/edit/...
    private String description;
}
