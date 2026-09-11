package com.eaiselp.data.service.subscription.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.PlanMapper;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.subscription.CostCenterService;
import com.eaiselp.data.service.subscription.dto.CostCenterVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 费用中心汇总实现（case-20260823-商用化 T10）。tenant_id 显式传参（G13）；t_quota 为租户级
 * 表（租户上下文自动过滤 + 显式 eq 叠加无害）；t_tenant/t_plan 在 IGNORE_TABLES 直查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostCenterServiceImpl implements CostCenterService {

    private static final String EDITION_TRIAL = "trial";

    private final TenantMapper tenantMapper;
    private final PlanMapper planMapper;
    private final QuotaMapper quotaMapper;

    @Override
    public CostCenterVo summary(Long tenantId) {
        Tenant tenant = tenantId != null ? tenantMapper.selectById(tenantId) : null;
        CostCenterVo.CostCenterVoBuilder builder = CostCenterVo.builder();

        // 四维水位：t_quota 当期行（不存在 → quota=null）
        String period = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Quota quota = tenantId != null ? quotaMapper.selectOne(new LambdaQueryWrapper<Quota>()
                .eq(Quota::getTenantId, tenantId).eq(Quota::getPeriod, period)) : null;
        if (quota != null) {
            builder.quota(CostCenterVo.QuotaWatermark.builder()
                    .period(period)
                    .token(CostCenterVo.Dimension.builder()
                            .used(nz(quota.getTokenUsed())).limit(nz(quota.getTokenLimit()))
                            .alertThreshold(quota.getAlertThreshold())
                            .build())
                    .caseDimension(CostCenterVo.Dimension.builder()
                            .used(nz(quota.getCaseUsed())).limit(nz(quota.getCaseLimit())).build())
                    .derivation(CostCenterVo.Dimension.builder()
                            .used(nz(quota.getDerivationUsed())).limit(nz(quota.getDerivationLimit())).build())
                    .storage(CostCenterVo.StorageDimension.builder()
                            .usedMb(nz(quota.getStorageUsedMb())).limitMb(nz(quota.getStorageLimitMb())).build())
                    .build());
        }

        // 当前套餐卡：trial / plan_code 空 → null（AC-F2.9；绑定行缺失 → null + WARN）
        if (tenant == null || tenant.getPlanCode() == null || tenant.getPlanCode().isBlank()
                || EDITION_TRIAL.equalsIgnoreCase(tenant.getEdition())) {
            return builder.build();
        }
        List<Plan> plans = planMapper.selectList(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getCode, tenant.getPlanCode())
                .orderByDesc(Plan::getId));
        if (plans.isEmpty()) {
            log.warn("[CostCenter] 绑定套餐行不可见（逻辑删?），套餐卡返回 null: tenantId={}, planCode={}",
                    tenantId, tenant.getPlanCode());
            return builder.build();
        }
        Plan plan = plans.get(0); // 同 code 多份（仅 custom）取最新
        builder.plan(CostCenterVo.PlanCard.builder()
                .code(plan.getCode())
                .name(plan.getName())
                .monthlyPrice(plan.getMonthlyPrice())
                .tokenOveragePrice(plan.getTokenOveragePrice())
                .slaLevel(plan.getSlaLevel()) // 档位透传，承诺文案前端集中（D-17）
                .build());
        return builder.build();
    }

    private static long nz(Number v) {
        return v != null ? v.longValue() : 0L;
    }
}
