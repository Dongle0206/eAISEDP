package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/**
 * AI 服务账号（M4 预留）。M2 Phase 1 只建表不 seed（PRD §6.2.5）。
 * Entity 先建好，M4 启用时直接用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_service_account")
public class ServiceAccount extends BaseEntity {
    private String accountCode;      // team-po / derivation-engine
    private String accountName;
    private String accountType;      // role_agent / system_service
    private String apiKey;           // M4 用
    private String allowedRoles;     // JSON 字符串（MySQL JSON 列，实体用 String 接）
    private String status;           // active / disabled
    private LocalDateTime expireTime;
}
