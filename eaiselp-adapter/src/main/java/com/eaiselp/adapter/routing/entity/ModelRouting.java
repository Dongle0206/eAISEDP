package com.eaiselp.adapter.routing.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型路由实体（P8 解耦层，model-registry skill 落地）。
 *
 * <p>角色定义只写能力档位 tier（reasoning/structured/mechanical/code），本表把 tier 映射到具体
 * provider + model。换模型/换厂商只改本表（UPDATE/INSERT），不改角色定义、不改 Java 代码。
 *
 * <p>系统级全局配置表，{@code t_model_routing} 无 tenant_id 列（模型档位是平台级配置），
 * 已加入 {@code EaiselpTenantHandler.IGNORE_TABLES}。因此继承 BaseEntity 的 tenantId 字段用
 * {@code @TableField(exist=false)} 声明该列不存在，避免 MyBatis-Plus 把 tenant_id 加到 SQL
 * （处理方式同 {@code com.eaiselp.data.entity.Tenant}）。
 *
 * <p>ModelRouting 放 adapter 模块内部（非 data），遵循 ES-003 §9.2 / P3：避免 adapter→data 反向依赖。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_model_routing")
public class ModelRouting extends BaseEntity {

    /** 能力档位：reasoning / structured / mechanical / code */
    private String tier;

    /** LLM 厂商：glm / deepseek / qwen / openai / ollama */
    private String provider;

    /** 具体模型名：glm-4-plus / deepseek-r1 / qwen-max */
    private String model;

    /** 优先级（同档位多 provider 时按优先级选，值小者优先） */
    private Integer priority;

    /** 是否启用：1=启用 0=停用 */
    private Integer enabled;

    /** API Key 环境变量名（如 GLM_API_KEY / DEEPSEEK_API_KEY） */
    private String apiKeyEnv;

    /** API base URL */
    private String baseUrl;

    /** 推荐角色（如 reasoning 档推荐 EA/SE 用 deepseek-r1） */
    private String roleHint;

    /** 覆盖父类 tenantId：t_model_routing 表无此列（系统级全局配置表） */
    @TableField(exist = false)
    private Long tenantId;
}
