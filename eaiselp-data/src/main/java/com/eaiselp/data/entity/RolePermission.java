package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色-权限关联（N:N）。轻量实体：不继承 BaseEntity（无 tenant_id/updateTime/is_deleted）。
 * 删除即物理删除（关联无逻辑删除诉求）。已加入 EaiselpTenantHandler.IGNORE_TABLES。
 */
@Data
@TableName("t_role_permission")
public class RolePermission implements Serializable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long roleId;
    private Long permissionId;
    private LocalDateTime createTime;
}
