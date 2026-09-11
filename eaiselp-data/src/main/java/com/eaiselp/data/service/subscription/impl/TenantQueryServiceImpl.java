package com.eaiselp.data.service.subscription.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.PlanMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.subscription.TenantQueryService;
import com.eaiselp.data.service.subscription.dto.TenantItemVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台侧租户列表查询实现（case-20260823-商用化 编排者追加）。
 *
 * <p><b>避免 N+1</b>：planName 快照按页内出现的 plan_code 去重后一次批量查 t_plan
 * （商品目录 ≤ 数十行，全量内存映射）；daysLeft 逐行按登录提示同公式计算（无 DB 交互）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantQueryServiceImpl implements TenantQueryService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String EDITION_TRIAL = "trial";
    private static final long ONE_DAY_MILLIS = 24L * 3600 * 1000;

    private final TenantMapper tenantMapper;
    private final PlanMapper planMapper;

    @Override
    public IPage<TenantItemVo> pageTenants(String keyword, long page, long size) {
        LambdaQueryWrapper<Tenant> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(Tenant::getTenantName, kw).or().like(Tenant::getTenantCode, kw));
        }
        w.orderByDesc(Tenant::getId);
        IPage<Tenant> p = tenantMapper.selectPage(new Page<>(page, size), w);

        Map<String, String> planNames = loadPlanNames(p.getRecords());
        return p.convert(t -> TenantItemVo.builder()
                .id(t.getId())
                .tenantCode(t.getTenantCode())
                .tenantName(t.getTenantName())
                .edition(t.getEdition())
                .planCode(t.getPlanCode())
                .planName(t.getPlanCode() != null ? planNames.get(t.getPlanCode()) : null)
                .expireTime(t.getExpireTime() != null ? t.getExpireTime().format(FORMATTER) : null)
                .daysLeft(daysLeftOf(t))
                .status(t.getStatus())
                .build());
    }

    /** daysLeft：与 TenantSubscriptionServiceImpl.buildStatus 同 ceil 口径（唯一登录提示口径）。 */
    private Integer daysLeftOf(Tenant t) {
        if (!EDITION_TRIAL.equalsIgnoreCase(t.getEdition()) || t.getExpireTime() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(t.getExpireTime())) {
            return 0;
        }
        return (int) Math.ceil(Duration.between(now, t.getExpireTime()).toMillis() / (double) ONE_DAY_MILLIS);
    }

    /** 页内 plan_code 去重批量查套餐名（避免逐行 N+1；同 code 多份 custom 取最新一份 id DESC 首行）。 */
    private Map<String, String> loadPlanNames(List<Tenant> tenants) {
        Map<String, String> names = new HashMap<>();
        var codes = tenants.stream()
                .map(Tenant::getPlanCode)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .toList();
        if (codes.isEmpty()) {
            return names;
        }
        List<Plan> plans = planMapper.selectList(new LambdaQueryWrapper<Plan>()
                .in(Plan::getCode, codes)
                .orderByDesc(Plan::getId));
        for (Plan plan : plans) {
            names.putIfAbsent(plan.getCode(), plan.getName()); // id DESC 首份优先（最新）
        }
        return names;
    }
}
