package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户-角色关联（N:N）。轻量实体：含 tenant_id（隔离用），不含 updateTime/is_deleted。
 * Phase 1 按 user_id 显式查询（加入 IGNORE_TABLES 免 tenant 自动过滤，避免跨场景歧义）。
 */
@Data
@TableName("t_user_role")
public class UserRole implements Serializable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long roleId;
    private LocalDateTime createTime;
    private String createBy;
}
