package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计日志实体（M3-2，GRC 治理要求：操作可追溯 who/when/what/before/after）。
 *
 * <p><b>不继承 BaseEntity</b>：t_governance_log 表结构特殊，只有 id/tenant_id/create_time，
 * 没有 update_time/update_by/create_by/is_deleted（审计日志是 append-only 只追加型表，
 * 不更新、不逻辑删除、保留全部历史）。直接独立实体避免字段不匹配。
 *
 * <p><b>tenant_id</b>：表带 tenant_id 列以便按租户隔离查询，但已加入
 * {@code EaiselpTenantHandler.IGNORE_TABLES}（不走拦截器自动注入）——审计日志的 tenant_id
 * 由 {@code AuditService} 显式从 LoginUser 上下文取值后写入（明细更清晰可控）。
 *
 * <p>{@code detail} 字段对应 DB JSON 列，存操作前后快照/扩展上下文。
 * MyBatis-Plus 默认用 String handler，这里以 String 承载 JSON 文本（由 AuditService 序列化）。
 */
@Data
@TableName("t_governance_log")
public class GovernanceLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户 ID（显式记录，0=系统级）。 */
    private Long tenantId;

    /** 操作人用户 ID（系统操作可为 null）。 */
    private Long userId;

    /** 操作人用户名（冗余便于查询，避免 join）。 */
    private String username;

    /** 操作动作（如 login_success / case_create / case_transit）。 */
    private String action;

    /** 资源类型（如 case / checkpoint / derivation / user）。 */
    private String resourceType;

    /** 资源标识（如 caseId / userId）。 */
    private String resourceId;

    /** 详情 JSON 文本（before/after 快照 + 扩展上下文）。 */
    private String detail;

    /** 操作来源 IP（从 RequestContextHolder 取）。 */
    private String ipAddress;

    /** 结果：success / failure。 */
    private String result;

    /** 失败时的错误信息（result=failure 时填）。 */
    private String errorMsg;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP，这里同步字段以便回显）。 */
    private LocalDateTime createTime;
}
