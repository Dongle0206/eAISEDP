package com.eaiselp.data.service.subscription.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.PlanMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.subscription.BillingCalculator;
import com.eaiselp.data.service.subscription.SubscriptionPlanService;
import com.eaiselp.data.service.subscription.dto.PlanVo;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 套餐服务实现（case-20260823-商用化 T3/T6）。校验口径见接口 Javadoc 与 api-contracts.md §1。
 *
 * <p><b>商品目录语义（P4 冻结）</b>：编辑改价/改配额不回写已生成账单（快照自洽）与已绑定租户
 * 当期 t_quota——本类无任何到 t_invoice/t_quota 的联动写（下次套餐应用才覆写）。</p>
 *
 * <p><b>tenant_id 上下文</b>：t_plan 在 IGNORE_TABLES（T2）免租户过滤——任何上下文直查。
 * {@link #assertModelTierAllowed} 全程显式传 tenantId（G13），不从 ThreadLocal 取。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionPlanServiceImpl implements SubscriptionPlanService {

    private final PlanMapper planMapper;
    private final TenantMapper tenantMapper;
    private final AuditService auditService;

    private static final ObjectMapper OM = new ObjectMapper();

    private static final String CODE_CUSTOM = "custom";
    private static final String EDITION_TRIAL = "trial";

    // ==================== 查询（P1/P3） ====================

    @Override
    public IPage<PlanVo> pageFilter(String code, Boolean enabled, String keyword, long page, long size) {
        LambdaQueryWrapper<Plan> w = new LambdaQueryWrapper<>();
        if (code != null && !code.isBlank()) {
            w.eq(Plan::getCode, code.trim());
        }
        if (enabled != null) {
            w.eq(Plan::getEnabled, enabled);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(Plan::getName, keyword.trim());
        }
        w.orderByDesc(Plan::getId);
        IPage<Plan> p = planMapper.selectPage(new Page<>(page, size), w);
        return p.convert(this::toVo);
    }

    @Override
    public PlanVo detail(Long id) {
        return toVo(loadOr404(id));
    }

    @Override
    public Plan findActiveByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return planMapper.selectOne(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getCode, code.trim())
                .eq(Plan::getEnabled, true)
                .last("LIMIT 1"));
    }

    @Override
    public List<Plan> findActiveByCodeList(String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return planMapper.selectList(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getCode, code.trim())
                .eq(Plan::getEnabled, true)
                .orderByDesc(Plan::getId));
    }

    @Override
    public Plan findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return planMapper.selectOne(new LambdaQueryWrapper<Plan>().eq(Plan::getCode, code.trim()));
    }

    // ==================== 创建（P2） ====================

    @Override
    public PlanVo create(Plan plan, Map<String, Object> features) {
        validateForWrite(plan, features);
        normalize(plan);
        assertStandardCodeUnique(plan.getCode(), plan.getEnabled(), null);
        plan.setFeatures(toFeaturesJson(features));
        try {
            planMapper.insert(plan);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "套餐已存在: " + plan.getName());
        }
        audit("plan_create", plan.getId(), null, plan);
        return toVo(plan);
    }

    // ==================== 编辑（P4，全量含上下架） ====================

    @Override
    public PlanVo update(Long id, Plan patch, Map<String, Object> features) {
        Plan exist = loadOr404(id);
        validateForWrite(patch, features);
        normalize(patch);
        // 标准档同 code 在架唯一：目标态在架时查重（含"下架后复活"场景——目标态 true 即查，D-1）
        assertStandardCodeUnique(patch.getCode(), patch.getEnabled(), id);
        LambdaUpdateWrapper<Plan> uw = new LambdaUpdateWrapper<Plan>().eq(Plan::getId, id)
                .set(Plan::getCode, patch.getCode())
                .set(Plan::getName, patch.getName())
                .set(Plan::getMonthlyPrice, patch.getMonthlyPrice())
                .set(Plan::getTokenOveragePrice, patch.getTokenOveragePrice())
                .set(Plan::getSlaLevel, patch.getSlaLevel())
                .set(Plan::getEditionOverride, patch.getEditionOverride())
                .set(Plan::getEnabled, patch.getEnabled())
                .set(Plan::getFeatures, toFeaturesJson(features));
        try {
            planMapper.update(null, uw);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "套餐已存在: " + patch.getName());
        }
        audit("plan_update", id, exist, patch);
        return detail(id);
    }

    // ==================== T6 模型档位 enforcement（40004） ====================

    @Override
    public void assertModelTierAllowed(Long tenantId, String tier) {
        if (tier == null || tier.isBlank()) {
            return;
        }
        Tenant tenant = tenantId != null ? tenantMapper.selectById(tenantId) : null;
        if (tenant == null) {
            // 防御放行（D-10/R7）：租户缺失不因计费校验加严锁死派生主链路
            log.warn("[Plan] 模型档位校验租户不存在，防御放行: tenantId={}, tier={}", tenantId, tier);
            return;
        }
        if (tenant.getPlanCode() == null || tenant.getPlanCode().isBlank()
                || EDITION_TRIAL.equalsIgnoreCase(tenant.getEdition())) {
            return; // 未绑定套餐 / trial → 不限（现状回归，AC-F1.7）
        }
        List<Plan> plans = planMapper.selectList(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getCode, tenant.getPlanCode())
                .orderByDesc(Plan::getId));
        if (plans.isEmpty()) {
            log.warn("[Plan] 模型档位校验套餐行不存在（逻辑删?），防御放行: tenantId={}, planCode={}, tier={}",
                    tenantId, tenant.getPlanCode(), tier);
            return;
        }
        Plan plan = plans.get(0); // 同 code 多份（仅 custom 合法）时取最新一份做只读判定
        List<String> tiers = parseModelTiers(plan);
        if (tiers == null || tiers.isEmpty()) {
            log.warn("[Plan] 套餐 model_tiers 缺失/解析失败，防御放行: planCode={}, tier={}",
                    tenant.getPlanCode(), tier);
            return;
        }
        if (!tiers.contains(tier)) {
            throw new BizException(ResultCode.MODEL_TIER_FORBIDDEN,
                    "模型档位 " + tier + " 不在当前套餐范围内（套餐 " + plan.getName()
                            + " 可用档位: " + tiers + "）");
        }
    }

    // ==================== features 解析 ====================

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseFeatures(Plan plan) {
        if (plan == null || plan.getFeatures() == null || plan.getFeatures().isBlank()) {
            return null;
        }
        try {
            return OM.readValue(plan.getFeatures(), Map.class);
        } catch (Exception e) {
            log.warn("[Plan] features JSON 解析失败: planId={}", plan.getId());
            return null;
        }
    }

    @Override
    public PlanVo toVo(Plan plan) {
        Map<String, Object> features = parseFeatures(plan);
        if (features == null) {
            features = new LinkedHashMap<>();
        }
        return PlanVo.builder()
                .id(plan.getId())
                .code(plan.getCode())
                .name(plan.getName())
                .monthlyPrice(plan.getMonthlyPrice())
                .tokenOveragePrice(plan.getTokenOveragePrice())
                .slaLevel(plan.getSlaLevel())
                .enabled(plan.getEnabled() == null || plan.getEnabled())
                .editionOverride(plan.getEditionOverride())
                .features(features)
                .createBy(plan.getCreateBy())
                .createTime(plan.getCreateTime() != null
                        ? plan.getCreateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        : null)
                .build();
    }

    // ==================== 校验族（Service 承载） ====================

    private void validateForWrite(Plan plan, Map<String, Object> features) {
        if (plan == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        // code 枚举（非法 400 指名 + 合法值集）
        if (plan.getCode() == null || plan.getCode().isBlank()) {
            throw new BizException(400, "code 不能为空");
        }
        plan.setCode(plan.getCode().trim());
        if (!SUPPORTED_CODES.contains(plan.getCode())) {
            throw new BizException(400, "code 非法: " + plan.getCode() + "，合法值: " + SUPPORTED_CODES);
        }
        // name 必填 ≤200
        if (plan.getName() == null || plan.getName().isBlank()) {
            throw new BizException(400, "name 不能为空");
        }
        plan.setName(plan.getName().trim());
        if (plan.getName().length() > 200) {
            throw new BizException(400, "name 长度超限（≤200）: " + plan.getName().length());
        }
        // 金额 ≥0 + 域上限镜像（D-4：(14,2)/(12,6) 超限 400 指名非 DB 溢出 500）
        if (plan.getMonthlyPrice() == null) {
            throw new BizException(400, "monthlyPrice 不能为空");
        }
        if (plan.getMonthlyPrice().signum() < 0) {
            throw new BizException(400, "monthlyPrice 不能为负数: " + plan.getMonthlyPrice().toPlainString());
        }
        if (plan.getMonthlyPrice().compareTo(BillingCalculator.DECIMAL_14_2_MAX) > 0) {
            throw new BizException(400, "monthlyPrice 超出 DECIMAL(14,2) 域上限 999,999,999,999.99: "
                    + plan.getMonthlyPrice().toPlainString());
        }
        if (plan.getTokenOveragePrice() == null) {
            throw new BizException(400, "tokenOveragePrice 不能为空");
        }
        if (plan.getTokenOveragePrice().signum() < 0) {
            throw new BizException(400, "tokenOveragePrice 不能为负数: "
                    + plan.getTokenOveragePrice().toPlainString());
        }
        if (plan.getTokenOveragePrice().compareTo(BillingCalculator.DECIMAL_12_6_MAX) > 0) {
            throw new BizException(400, "tokenOveragePrice 超出 DECIMAL(12,6) 域上限 999,999.999999: "
                    + plan.getTokenOveragePrice().toPlainString());
        }
        // sla_level 枚举（空/枚举外 400，AC-F3.1）
        if (plan.getSlaLevel() == null || plan.getSlaLevel().isBlank()
                || !SUPPORTED_SLA_LEVELS.contains(plan.getSlaLevel())) {
            throw new BizException(400, "slaLevel 非法: " + plan.getSlaLevel()
                    + "，合法值: " + SUPPORTED_SLA_LEVELS);
        }
        // custom↔edition_override 双向校验（D-9）
        boolean custom = CODE_CUSTOM.equals(plan.getCode());
        String override = plan.getEditionOverride();
        if (custom) {
            if (override == null || override.isBlank()) {
                throw new BizException(400, "custom 套餐必须指定 editionOverride（∈ "
                        + SUPPORTED_EDITION_OVERRIDES + "）");
            }
            if (!SUPPORTED_EDITION_OVERRIDES.contains(override)) {
                throw new BizException(400, "editionOverride 非法: " + override
                        + "，合法值: " + SUPPORTED_EDITION_OVERRIDES);
            }
        } else if (override != null && !override.isBlank()) {
            throw new BizException(400, "非 custom 套餐 editionOverride 必须为空（仅 custom 套餐可指定）");
        }
        validateFeatures(features);
    }

    /** features 服务端结构校验：必需键 + 值域；未知键忽略（向前兼容，PRD 4.1.1）。 */
    private void validateFeatures(Map<String, Object> features) {
        if (features == null || features.isEmpty()) {
            throw new BizException(400, "features 不能为空（必需键: " + REQUIRED_FEATURE_INT_KEYS
                    + " + layer_overrides + model_tiers）");
        }
        for (String key : REQUIRED_FEATURE_INT_KEYS) {
            Object v = features.get(key);
            if (v == null) {
                throw new BizException(400, "features 缺少必需键: " + key);
            }
            long value = asNonNegativeIntegral(v, "features." + key);
            if (value > Integer.MAX_VALUE && !"token_limit".equals(key) && !"storage_limit_mb".equals(key)) {
                throw new BizException(400, "features." + key + " 超出合理值域: " + value);
            }
        }
        Object layerOverridesRaw = features.get("layer_overrides");
        if (!(layerOverridesRaw instanceof Map<?, ?> layerOverrides)) {
            throw new BizException(400, "features.layer_overrides 必须为对象（含 strategy_enabled/"
                    + "program_project_enabled）");
        }
        for (String flagKey : List.of("strategy_enabled", "program_project_enabled")) {
            Object v = layerOverrides.get(flagKey);
            if (v == null) {
                throw new BizException(400, "features.layer_overrides 缺少必需键: " + flagKey);
            }
            asFlag(v, "features.layer_overrides." + flagKey);
        }
        Object tiersRaw = features.get("model_tiers");
        if (!(tiersRaw instanceof List<?> tiers)) {
            throw new BizException(400, "features.model_tiers 必须为数组（值域: " + SUPPORTED_MODEL_TIERS + "）");
        }
        for (Object t : tiers) {
            if (t == null || !SUPPORTED_MODEL_TIERS.contains(String.valueOf(t))) {
                throw new BizException(400, "features.model_tiers 值非法: " + t
                        + "，合法值: " + SUPPORTED_MODEL_TIERS);
            }
        }
    }

    /** 非负整数校验（JSON 小数如 1.5 → 400 指名，非反序列化 500）。 */
    private long asNonNegativeIntegral(Object v, String field) {
        if (v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger) {
            long value = ((Number) v).longValue();
            if (value < 0) {
                throw new BizException(400, field + " 不能为负数: " + value);
            }
            return value;
        }
        throw new BizException(400, field + " 必须为非负整数（非法值: " + v + "）");
    }

    /** 层开关值校验：0/1/true/false。 */
    private void asFlag(Object v, String field) {
        if (v instanceof Boolean || v instanceof Integer && (((Integer) v) == 0 || ((Integer) v) == 1)) {
            return;
        }
        throw new BizException(400, field + " 必须为 0/1/true/false（非法值: " + v + "）");
    }

    /** 标准三档同 code 在架唯一（enabled=true 至多一份；custom 多份合法，AC-F1.2）。excludeId 编辑排除自身。 */
    private void assertStandardCodeUnique(String code, Boolean enabled, Long excludeId) {
        if (CODE_CUSTOM.equals(code) || enabled == null || !enabled) {
            return; // custom 多份合法；目标态下架不占在架位
        }
        LambdaQueryWrapper<Plan> w = new LambdaQueryWrapper<Plan>()
                .eq(Plan::getCode, code)
                .eq(Plan::getEnabled, true);
        if (excludeId != null) {
            w.ne(Plan::getId, excludeId);
        }
        if (planMapper.selectCount(w) > 0) {
            throw new BizException(400, "标准档同 code 在架套餐已存在: " + code + "（至多一份，AC-F1.2；"
                    + "如需替换请先下架既有套餐）");
        }
    }

    /** 落库归一：金额 scale 对齐 DECIMAL 定义（14,2)/(12,6)；enabled 缺省 true。 */
    private void normalize(Plan plan) {
        plan.setMonthlyPrice(plan.getMonthlyPrice().setScale(2, java.math.RoundingMode.HALF_UP));
        plan.setTokenOveragePrice(plan.getTokenOveragePrice().setScale(6, java.math.RoundingMode.HALF_UP));
        if (plan.getEnabled() == null) {
            plan.setEnabled(true);
        }
        if (!CODE_CUSTOM.equals(plan.getCode())) {
            plan.setEditionOverride(null);
        }
        plan.setTenantId(0L); // 平台级表占位恒 0（V8 契约）
    }

    private String toFeaturesJson(Map<String, Object> features) {
        try {
            return OM.writeValueAsString(features);
        } catch (Exception e) {
            throw new BizException(400, "features 序列化失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    List<String> parseModelTiers(Plan plan) {
        Map<String, Object> features = parseFeatures(plan);
        if (features == null) {
            return null;
        }
        Object tiers = features.get("model_tiers");
        if (!(tiers instanceof List<?> list)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (Object t : list) {
            if (t != null) {
                result.add(String.valueOf(t));
            }
        }
        return result;
    }

    private Plan loadOr404(Long id) {
        Plan plan = id != null ? planMapper.selectById(id) : null;
        if (plan == null) {
            throw new BizException(ResultCode.NOT_FOUND, "套餐不存在: " + id);
        }
        return plan;
    }

    /** 审计（plan_create/plan_update；update detail 含 old→new 关键字段，公共验收约束 6）。 */
    private void audit(String action, Long id, Plan old, Plan now) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id);
        detail.put("code", now.getCode());
        detail.put("name", now.getName());
        detail.put("monthlyPrice", now.getMonthlyPrice());
        detail.put("tokenOveragePrice", now.getTokenOveragePrice());
        detail.put("slaLevel", now.getSlaLevel());
        detail.put("enabled", now.getEnabled());
        if (old != null) {
            Map<String, Object> oldSnap = new LinkedHashMap<>();
            oldSnap.put("code", old.getCode());
            oldSnap.put("name", old.getName());
            oldSnap.put("monthlyPrice", old.getMonthlyPrice());
            oldSnap.put("tokenOveragePrice", old.getTokenOveragePrice());
            oldSnap.put("slaLevel", old.getSlaLevel());
            oldSnap.put("enabled", old.getEnabled());
            detail.put("old", oldSnap);
        }
        detail.put("operator", currentUsername());
        try {
            auditService.log(action, "plan", String.valueOf(id), OM.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("[Plan] 审计序列化失败（不阻塞业务）: {}", e.getMessage());
        }
    }

    private String currentUsername() {
        JwtClaims claims = LoginUser.get();
        return claims != null && claims.getUsername() != null ? claims.getUsername() : "";
    }
}
