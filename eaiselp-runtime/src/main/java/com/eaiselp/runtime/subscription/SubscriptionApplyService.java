package com.eaiselp.runtime.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.PlanMapper;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.TenantSubscriptionService;
import com.eaiselp.data.service.TenantSubscriptionService.SubscriptionStatus;
import com.eaiselp.data.service.subscription.SubscriptionPlanService;
import com.eaiselp.runtime.hierarchy.TenantLayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 套餐应用编排（case-20260823-商用化 T4；AC-F1.5/F1.6，U2 扩展 planCode/planId 分叉的编排体）。
 *
 * <p><b>放置位置（SE D-1）</b>：runtime/subscription——层开关覆写必须走
 * {@link TenantLayerService#setLayerEnabled}（runtime/hierarchy，含写后缓存失效）；
 * data→runtime 反向依赖违反 P3 禁止，故编排（而非计算）在 runtime。</p>
 *
 * <p><b>单事务五类落库（AC-F1.5）</b>：① t_tenant.edition（三档=code / custom=editionOverride，
 * D-9——SUPPORTED_EDITIONS 四值域不变）② t_tenant.plan_code 绑定 ③ expire_time 置空
 * （PRD 4.2.2 缺省，付费档到期拦截不生效）④ t_quota 当期四限 + monthly_cost=套餐月价覆写
 * （行不存在按模板 upsert，uk(tenant,period)；水位列不动——Q4 降级水位不回退只防新增）
 * ⑤ 层开关按 layer_overrides 覆写 ×2（套餐说开就开/说关就关，写后缓存失效在事务内——
 * evict 无副作用，回滚后下次读回源，天然一致，SE §8.3）。任一步失败全部回滚
 * （Mockito doThrow 断言五类全保持旧值）。</p>
 *
 * <p><b>planId/planCode 岐义消解（编排者追加裁决）</b>：入参可选 planId（数字主键，
 * <b>优先</b>——存在时 planCode 忽略）与 planCode；planCode 命中多份 enabled custom 套餐时
 * （AC-F1.2 custom 多份合法的固有歧义）抛 {@code BizException(40004)} 指明
 * "存在多份同名 custom 套餐，请用 planId 精确指定"。</p>
 *
 * <p><b>模型档位经 plan_code 绑定即时生效</b>（enforcement 在 RuntimeController 40004 挂点，
 * 本类不重复实现）；<b>tenant_id 全程显式传参</b>（G13/D-5 惯例——t_tenant/t_plan 在
 * IGNORE_TABLES 直查，t_quota INSERT 由实体显式携带 tenant_id）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionApplyService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String EDITION_TRIAL = "trial";
    private static final String CODE_CUSTOM = "custom";

    private final TenantMapper tenantMapper;
    private final PlanMapper planMapper;
    private final QuotaMapper quotaMapper;
    private final TenantLayerService tenantLayerService;
    private final TenantSubscriptionService subscriptionService;
    private final SubscriptionPlanService planService;
    private final AuditService auditService;

    /**
     * U2 套餐应用（单事务五类落库；planId 优先，planCode 岐义 40004）。
     *
     * @param tenantId 目标租户（路径参数，显式传参）
     * @param planId   套餐数字主键（可选，优先——非空时 planCode 忽略）
     * @param planCode 套餐编码（可选；多份 enabled custom 时 40004 请用 planId）
     * @return 修改后回显（SubscriptionStatus 扩展 planCode/planName/slaLevel）
     * @throws BizException 40400 租户/套餐不存在；400 套餐已下架不可应用（AC-F1.4）；
     *                      40004 planCode 命中多份 enabled custom 套餐
     */
    @Transactional(rollbackFor = Exception.class)
    public SubscriptionStatus applyPlan(Long tenantId, Long planId, String planCode) {
        try {
            return applyPlanInternal(tenantId, planId, planCode);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // AC-F1.5：五类落库任一步基础设施异常（如配额行锁定失败）→ 统一业务异常
            // （原始 RuntimeException 与转换后的 BizException 均匹配 rollbackFor=Exception，事务必回滚）
            throw new BizException(500, "套餐应用失败（事务已回滚，无半应用状态）: " + e.getMessage());
        }
    }

    private SubscriptionStatus applyPlanInternal(Long tenantId, Long planId, String planCode) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ResultCode.NOT_FOUND, "租户不存在: " + tenantId);
        }
        Plan plan = resolvePlan(planId, planCode);
        if (plan == null) {
            throw new BizException(ResultCode.NOT_FOUND,
                    "套餐不存在: " + (planId != null ? "id=" + planId : "code=" + planCode));
        }
        if (plan.getEnabled() == null || !plan.getEnabled()) {
            throw new BizException(400, "套餐已下架，不可应用: " + plan.getName() + "（AC-F1.4；存量绑定租户不受影响）");
        }
        Map<String, Object> features = planService.parseFeatures(plan);
        if (features == null) {
            throw new BizException(400, "套餐 features 配置缺失/非法，不可应用: " + plan.getName());
        }

        // —— 五类落库目标值（快照先算，落库后 old→new 审计用） ——
        String newEdition = CODE_CUSTOM.equals(plan.getCode()) ? plan.getEditionOverride() : plan.getCode();
        if (newEdition == null || newEdition.isBlank() || EDITION_TRIAL.equals(newEdition)) {
            throw new BizException(400, "custom 套餐 editionOverride 缺失或非法（D-9）: " + plan.getEditionOverride());
        }
        long tokenLimit = longOf(features.get("token_limit"));
        long caseLimit = longOf(features.get("case_limit"));
        long derivationLimit = longOf(features.get("derivation_limit"));
        long storageLimitMb = longOf(features.get("storage_limit_mb"));
        Map<?, ?> layerOverrides = (Map<?, ?>) features.getOrDefault("layer_overrides", Map.of());
        boolean strategyEnabled = flagOf(layerOverrides.get("strategy_enabled"), true);
        boolean programProjectEnabled = flagOf(layerOverrides.get("program_project_enabled"), true);

        // ①②③ t_tenant 三列：edition / plan_code / expire_time 置空（D-13：U2 裸通道不动 plan_code，
        //    套餐应用通道才写——本方法即唯一写点）
        LambdaUpdateWrapper<Tenant> tuw = new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, tenantId)
                .set(Tenant::getEdition, newEdition)
                .set(Tenant::getPlanCode, plan.getCode())
                .set(Tenant::getExpireTime, null);
        tenantMapper.update(null, tuw);

        // ④ t_quota 当期行四限 + monthly_cost 覆写（行不存在按模板 upsert；水位不动）
        String period = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Quota exist = quotaMapper.selectOne(new LambdaQueryWrapper<Quota>()
                .eq(Quota::getTenantId, tenantId).eq(Quota::getPeriod, period));
        if (exist != null) {
            LambdaUpdateWrapper<Quota> quw = new LambdaUpdateWrapper<Quota>()
                    .eq(Quota::getId, exist.getId())
                    .set(Quota::getTokenLimit, tokenLimit)
                    .set(Quota::getCaseLimit, (int) caseLimit)
                    .set(Quota::getDerivationLimit, (int) derivationLimit)
                    .set(Quota::getStorageLimitMb, storageLimitMb)
                    .set(Quota::getMonthlyCost, plan.getMonthlyPrice());
            quotaMapper.update(null, quw);
        } else {
            Quota quota = new Quota();
            quota.setTenantId(tenantId); // SYSTEM 上下文（platform_admin tenant 0）下拦截器放行，显式携带（D-5）
            quota.setPeriod(period);
            quota.setTokenLimit(tokenLimit);
            quota.setTokenUsed(0L);
            quota.setCaseLimit((int) caseLimit);
            quota.setCaseUsed(0);
            quota.setDerivationLimit((int) derivationLimit);
            quota.setDerivationUsed(0);
            quota.setStorageLimitMb(storageLimitMb);
            quota.setStorageUsedMb(0L);
            quota.setMonthlyCost(plan.getMonthlyPrice());
            try {
                quotaMapper.insert(quota);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // uk(tenant,period) 并发兜底：转 UPDATE 覆写（幂等 upsert 语义）
                log.warn("[Apply] 配额行并发插入冲突，转覆写: tenantId={}, period={}", tenantId, period);
                LambdaUpdateWrapper<Quota> quw = new LambdaUpdateWrapper<Quota>()
                        .eq(Quota::getTenantId, tenantId).eq(Quota::getPeriod, period)
                        .set(Quota::getTokenLimit, tokenLimit)
                        .set(Quota::getCaseLimit, (int) caseLimit)
                        .set(Quota::getDerivationLimit, (int) derivationLimit)
                        .set(Quota::getStorageLimitMb, storageLimitMb)
                        .set(Quota::getMonthlyCost, plan.getMonthlyPrice());
                quotaMapper.update(null, quw);
            }
        }

        // ⑤ 层开关覆写 ×2（必须走 TenantLayerService——写后缓存失效在事务内，SE D-1 关键依据）
        tenantLayerService.setLayerEnabled(tenantId, TenantLayerService.LAYER_STRATEGY, strategyEnabled);
        tenantLayerService.setLayerEnabled(tenantId, TenantLayerService.LAYER_PROGRAM_PROJECT, programProjectEnabled);

        // 审计 tenant_edition_change（沿用既有 action——B3 期初判定数据源；detail 五类快照 old→new + 操作者）
        auditApply(tenant, plan, newEdition, period, tokenLimit, caseLimit, derivationLimit,
                storageLimitMb, strategyEnabled, programProjectEnabled, features);

        // 回显：更新后直查 + 套餐三字段填充
        SubscriptionStatus status = subscriptionService.getSubscriptionStatus(tenantId);
        status.setPlanCode(plan.getCode());
        status.setPlanName(plan.getName());
        status.setSlaLevel(plan.getSlaLevel());
        log.info("[Apply] 套餐应用完成: tenantId={}, plan={}({}), edition={}->{}",
                tenantId, plan.getName(), plan.getId(), tenant.getEdition(), newEdition);
        return status;
    }

    /**
     * U1 出参扩展填充（向前兼容）：planCode/planName/slaLevel 三字段（未绑定 → 三 null）。
     * 绑定 code 对应套餐行缺失（逻辑删）时 planCode 仍回显、name/sla 置 null + WARN。
     */
    public void fillPlanSnapshot(Long tenantId, SubscriptionStatus status) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getPlanCode() == null || tenant.getPlanCode().isBlank()) {
            return; // 未绑定（trial/裸通道）→ 三字段保持 null
        }
        status.setPlanCode(tenant.getPlanCode());
        List<Plan> plans = planMapper.selectList(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getCode, tenant.getPlanCode())
                .orderByDesc(Plan::getId));
        if (plans.isEmpty()) {
            log.warn("[Apply] 绑定套餐行不存在（逻辑删?），回显仅 planCode: tenantId={}, planCode={}",
                    tenantId, tenant.getPlanCode());
            return;
        }
        Plan plan = plans.get(0); // 同 code 多份（仅 custom）取最新一份展示
        status.setPlanName(plan.getName());
        status.setSlaLevel(plan.getSlaLevel());
    }

    // ==================== 内部 ====================

    /**
     * 套餐解析：planId 优先（非空时 planCode 忽略，编排者追加裁决）。
     * planCode 分支语义（契约 §2）：不存在（含逻辑删）→ 40400；存在但 enabled=0 → 400
     * （AC-F1.4，由 applyPlan 统一判定）；命中多份 enabled custom → 40004 岐义指明用 planId。
     */
    private Plan resolvePlan(Long planId, String planCode) {
        if (planId != null) {
            return planMapper.selectById(planId);
        }
        if (planCode != null && !planCode.isBlank()) {
            String code = planCode.trim();
            List<Plan> all = planMapper.selectList(new LambdaQueryWrapper<Plan>()
                    .eq(Plan::getCode, code)
                    .orderByDesc(Plan::getId)); // 含下架（逻辑删自动排除）——供 applyPlan 判 400
            if (all.isEmpty()) {
                return null;
            }
            List<Plan> actives = all.stream().filter(p -> p.getEnabled() != null && p.getEnabled()).toList();
            if (actives.size() > 1) {
                throw new BizException(ResultCode.MODEL_TIER_FORBIDDEN,
                        "存在多份同名 custom 套餐（code=" + code + "，" + actives.size()
                                + " 份在架），请用 planId 精确指定");
            }
            // 在架唯一 → 用之；全部下架 → 取最新一份（applyPlan 的 enabled 检查抛 400）
            return actives.size() == 1 ? actives.get(0) : all.get(0);
        }
        return null;
    }

    private static long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** 层开关值：1/true → true；0/false → false；null → 默认（true，防御——开层不误伤）。 */
    private static boolean flagOf(Object v, boolean dft) {
        if (v == null) {
            return dft;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return dft;
    }

    /** 审计 detail 五类快照 old→new + 操作者（AC-F1.6；B3 期初判定数据源——detail 格式即 T7 判定契约）。 */
    private void auditApply(Tenant old, Plan plan, String newEdition, String period,
                            long tokenLimit, long caseLimit, long derivationLimit, long storageLimitMb,
                            boolean strategyEnabled, boolean programProjectEnabled, Map<String, Object> features) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("oldEdition", old.getEdition());
        detail.put("newEdition", newEdition);
        detail.put("oldExpireTime", old.getExpireTime() != null ? old.getExpireTime().format(FORMATTER) : "");
        detail.put("newExpireTime", "");
        detail.put("oldPlanCode", old.getPlanCode() != null ? old.getPlanCode() : "");
        detail.put("newPlanCode", plan.getCode());
        Map<String, Object> quotaSnap = new LinkedHashMap<>();
        quotaSnap.put("period", period);
        quotaSnap.put("tokenLimit", tokenLimit);
        quotaSnap.put("caseLimit", caseLimit);
        quotaSnap.put("derivationLimit", derivationLimit);
        quotaSnap.put("storageLimitMb", storageLimitMb);
        quotaSnap.put("monthlyCost", plan.getMonthlyPrice() != null
                ? plan.getMonthlyPrice().setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
        detail.put("quota", quotaSnap);
        Map<String, Object> layerSnap = new LinkedHashMap<>();
        layerSnap.put("strategyEnabled", strategyEnabled);
        layerSnap.put("programProjectEnabled", programProjectEnabled);
        detail.put("layerOverrides", layerSnap);
        detail.put("modelTiers", features.get("model_tiers"));
        detail.put("operator", currentUsername());
        try {
            auditService.log("tenant_edition_change", "tenant", String.valueOf(old.getId()),
                    OM.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("[Apply] 审计序列化失败（不阻塞业务）: {}", e.getMessage());
        }
    }

    private String currentUsername() {
        var claims = com.eaiselp.common.security.LoginUser.get();
        return claims != null && claims.getUsername() != null ? claims.getUsername() : "";
    }
}
