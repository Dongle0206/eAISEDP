package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 订阅套餐实体（case-20260823-商用化 V8，F1.1；t_plan 平台级商品资产）。
 *
 * <p><b>字段名按 V8 落盘 DDL 定稿</b>（tasks.md 拆解声明 4 差异定稿表 D-1/D-2，冲突以 V8 为准）：
 * 上下架列是 {@code enabled TINYINT（1=在架/0=下架）}（t_architecture_principle 先例，Boolean 映射），
 * <b>非 SE 方案 §3 的 status VARCHAR</b>；标准三档同 code 在架唯一（enabled=1 且 is_deleted=0
 * 至多一份）为应用层校验（AC-F1.2），custom 可多份（名称区分）。</p>
 *
 * <p><b>平台级表</b>：tenant_id 恒 0 仅占位（同 t_permission 先例）；已加入
 * {@code EaiselpTenantHandler.IGNORE_TABLES}（T2/R6）免租户拦截器过滤，否则平台级行
 * tenant_id=0 查不到（P1 空列表/套餐应用 40400）。</p>
 *
 * <p><b>商品目录语义</b>（SE P4 冻结）：编辑改价/改配额不回写已生成账单（快照自洽）与
 * 已绑定租户当期 t_quota（下次套餐应用才覆写）；下架（enabled=0）不可被新租户应用，
 * 存量绑定租户不受影响（AC-F1.4）。无 DELETE 端点（D-14）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_plan")
public class Plan extends BaseEntity {

    /** 套餐编码：starter/pro/enterprise/custom（领域字典，应用层校验非法 400，DB 无约束 P6） */
    private String code;

    /** 套餐名（全局唯一 uk_plan_name；custom 多份以名称区分） */
    private String name;

    /** 月价（元，DECIMAL(14,2)；≥0 应用层校验，=0 合法免费档 AC-F1.3；B1 快照源） */
    private BigDecimal monthlyPrice;

    /**
     * token 超量单价（元/千 token，DECIMAL(12,6)；6 位小数支撑低单价——B6 构造例 0.335
     * 原样存取，(14,2) 会截断破坏 AC-F2.5）。
     */
    private BigDecimal tokenOveragePrice;

    /**
     * 功能开关 JSON 文本：{max_users, max_cases, token_limit, case_limit, derivation_limit,
     * storage_limit_mb, layer_overrides:{strategy_enabled, program_project_enabled},
     * model_tiers:[...]}（服务端结构校验必需键与值域，未知键忽略向前兼容；model_tiers 值域
     * =真实档位名 opus/sonnet/haiku/reasoning/structured/mechanical/code，ES-003 §2.5）。
     */
    private String features;

    /** SLA 档位：bronze/silver/gold（必填枚举，应用层校验 AC-F3.1；承诺文案前端集中一处 D-17） */
    private String slaLevel;

    /** custom 套餐必填 ∈{starter,pro,enterprise}；非 custom 必须为空（双向校验 400，D-9） */
    private String editionOverride;

    /** 上下架：true=在架可应用（默认）/false=下架不可应用（V8 差异定稿 D-1：enabled 非 status） */
    private Boolean enabled;
}
