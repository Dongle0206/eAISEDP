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

    /** #24 租户自配 LLM：厂商（glm/deepseek），默认 glm */
    private String llmProvider;
    /** #24 租户自配 LLM：API Key（派生时优先用租户 Key，平台不垫付 token 费） */
    private String llmApiKey;

    /**
     * L3 战略层开关（PRJ-002 F10，V4 r2 列 strategy_enabled TINYINT，Boolean↔TINYINT 映射）。
     *
     * <p>DEFAULT 1 = 存量租户升级后默认全开（AC-F10.4）；null 视为启用（旧库未加列/读取失败时
     * 的兜底语义由 TenantLayerService 统一实现）。关闭 = 菜单隐藏 + API 业务码 43001 + 数据保留可逆
     * （AC-F10.3），不做任何数据清理。</p>
     */
    private Boolean strategyEnabled;

    /**
     * L2 项目群+项目层开关（PRJ-002 F10/Q8 一体开关，V4 r2 列 program_project_enabled TINYINT）。
     *
     * <p>语义同 {@link #strategyEnabled}，关闭时业务码 43002；拆分粒度待客户反馈（Q8 演进：
     * 拆分时只需加列 + 改 guard 映射，向前兼容）。</p>
     */
    private Boolean programProjectEnabled;

    /** 覆盖父类 tenantId：t_tenant 表无此列 */
    @TableField(exist = false)
    private Long tenantId;
}
