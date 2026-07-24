package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色定义（模板角色 tenant_id=0 系统级；custom 角色 tenant_id=租户ID）。
 * 已加入 EaiselpTenantHandler.IGNORE_TABLES 免 tenant 自动过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_role")
public class Role extends BaseEntity {
    private String roleCode;         // platform_admin/tenant_admin/...
    private String roleName;         // 中文名
    private String roleType;         // system_template / custom
    private String dataScope;        // all / tenant / self
    private Integer isBuiltIn;       // 1=系统预置不可删, 0=可删
    private String description;
}
