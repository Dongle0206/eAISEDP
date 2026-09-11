package com.eaiselp.data.service.subscription;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.service.subscription.dto.PlanVo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 套餐服务（case-20260823-商用化 T3/T6；P1~P4 CRUD + features 结构校验 + model_tiers 读取）。
 *
 * <p><b>校验族</b>（Service 承载，Controller 薄层）：code/sla_level 枚举（应用层校验非法 400
 * 指名 + 合法值集，DB 不加约束 P6）、金额 ≥0 + DECIMAL 域上限镜像（D-4：(14,2) ≤ 999,999,999,999.99、
 * (12,6) ≤ 999,999.999999）、features 必需键/值域结构校验（未知键忽略向前兼容）、
 * custom↔edition_override 双向校验（D-9）、标准档同 code 在架唯一（AC-F1.2）、
 * uk_plan_name 冲突 400 统一形态。</p>
 *
 * <p><b>放置位置</b>：eaiselp-data service/subscription 新子包（D-1）——出账五数据源同模块自洽；
 * 套餐应用编排（层开关联动）在 runtime SubscriptionApplyService（data→runtime 反向依赖禁止）。</p>
 */
public interface SubscriptionPlanService {

    /** code 合法值集（领域数据字典，P6 裁决允许的应用层常量集，SUPPORTED_EDITIONS 先例） */
    Set<String> SUPPORTED_CODES = Set.of("starter", "pro", "enterprise", "custom");

    /** sla_level 合法值集（bronze/silver/gold，AC-F3.1） */
    Set<String> SUPPORTED_SLA_LEVELS = Set.of("bronze", "silver", "gold");

    /** custom 套餐 edition_override 合法值集（D-9：三档标准 edition，trial 不可作 override） */
    Set<String> SUPPORTED_EDITION_OVERRIDES = Set.of("starter", "pro", "enterprise");

    /** features.model_tiers 值域 = 真实档位名（t_model_routing/ES-003 §2.5——PRD 的 high/mid/low 为示意写法，DBA R5） */
    Set<String> SUPPORTED_MODEL_TIERS =
            Set.of("opus", "sonnet", "haiku", "reasoning", "structured", "mechanical", "code");

    /** features 必需数字键（P2 契约；未知键忽略向前兼容） */
    List<String> REQUIRED_FEATURE_INT_KEYS = List.of(
            "max_users", "max_cases", "token_limit", "case_limit", "derivation_limit", "storage_limit_mb");

    /**
     * P1 套餐分页列表：code/enabled/keyword(name LIKE) 筛选，默认 id DESC。
     */
    IPage<PlanVo> pageFilter(String code, Boolean enabled, String keyword, long page, long size);

    /**
     * P2 创建套餐（enabled 缺省 true；审计 plan_create）。
     *
     * @param plan     实体（code/name/monthlyPrice/tokenOveragePrice/slaLevel/editionOverride）
     * @param features 功能开关结构化入参（Map，服务端校验必需键/值域后序列化 JSON 落库）
     * @return 创建后 VO
     */
    PlanVo create(Plan plan, Map<String, Object> features);

    /**
     * P4 全量编辑（含 enabled 上下架；商品目录语义——不回写已生成账单与当期 t_quota；
     * 审计 plan_update detail 含 old→new）。
     */
    PlanVo update(Long id, Plan plan, Map<String, Object> features);

    /** P3 详情（40400 不存在）。 */
    PlanVo detail(Long id);

    /**
     * 按 code 查在架套餐（enabled=true 且未逻辑删）；供派生档位校验（T6，单行场景——
     * 标准档同 code 在架唯一由创建校验保证）。custom 同 code 可多份时本方法不适用，
     * 应使用 {@link #findActiveByCodeList}。
     */
    Plan findActiveByCode(String code);

    /**
     * 按 code 查全部在架套餐列表（编排者追加：U2 套餐应用岐义消解——planCode 命中多份
     * enabled custom 时由调用方以 40004 指明"请用 planId 精确指定"，AC-F1.2 custom 多份合法）。
     */
    java.util.List<Plan> findActiveByCodeList(String code);

    /** 按 code 查任意状态套餐（含下架；逻辑删排除）。 */
    Plan findByCode(String code);

    /**
     * T6 模型档位 enforcement：tenant.plan_code → features.model_tiers，请求 tier ∉ tiers →
     * BizException(40004)。
     *
     * <p>防御放行（D-10/R7，不因计费配置锁死派生主链路）：未绑定套餐（plan_code 空）/trial
     * 租户 → 不限；plan 行不存在（逻辑删）或 features 解析失败/model_tiers 缺失 → WARN 放行。
     * 零缓存即时生效（与订阅判定同风格）。</p>
     *
     * @param tenantId 租户 ID（显式传参，禁依赖 ThreadLocal——Controller 从 TenantContext 取）
     * @param tier     请求的模型档位名
     */
    void assertModelTierAllowed(Long tenantId, String tier);

    /**
     * 解析套餐 features JSON 为 Map（解析失败返回 null——调用方按防御放行处理）。
     */
    Map<String, Object> parseFeatures(Plan plan);

    /** VO 转换（features 结构化回显）。 */
    PlanVo toVo(Plan plan);
}
