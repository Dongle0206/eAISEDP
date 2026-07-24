package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/**
 * 租户实体。
 * 注意：t_tenant 表本身没有 tenant_id 列（它是租户定义表），
 * 但继承了 BaseEntity 的 tenantId 字段。用 @TableField(exist=false)
 * 声明该字段在表中不存在，避免 MyBatis-Plus 把 tenant_id 加到 SQL。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tenant")
public class Tenant extends BaseEntity {
    private String tenantCode;
    private String tenantName;
    private String deployMode;
    private String edition;
    private String status;
    private LocalDateTime expireTime;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private String systemRepoUrl;
    private String systemBranch;

    /** 覆盖父类 tenantId：t_tenant 表无此列 */
    @TableField(exist = false)
    private Long tenantId;
}
