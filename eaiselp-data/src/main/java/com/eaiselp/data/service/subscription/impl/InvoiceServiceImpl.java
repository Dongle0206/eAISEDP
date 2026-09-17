package com.eaiselp.data.service.subscription.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.entity.Invoice;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import com.eaiselp.data.mapper.InvoiceMapper;
import com.eaiselp.data.mapper.PlanMapper;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.subscription.BillingCalculator;
import com.eaiselp.data.service.subscription.InvoiceService;
import com.eaiselp.data.service.subscription.dto.GenerateSummaryVo;
import com.eaiselp.data.service.subscription.dto.InvoiceDetailVo;
import com.eaiselp.data.service.subscription.dto.InvoiceVo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度账单服务实现（case-20260823-商用化 T7/T9/T10）。判定序与幂等口径见接口 Javadoc
 * （SE §4.2 唯一实现口径；计费数值全走 {@link BillingCalculator}——本类零算术）。
 *
 * <p><b>SYSTEM 上下文（D-5）</b>：出账路径（定时线程/platform_admin tenant 0 手动补生成）
 * 拦截器全放行——本类所有读写显式携带 tenant_id（实体 set / wrapper eq），禁依赖 ThreadLocal。
 * 单租户生成 = 单条 INSERT 或单条 UPDATE，天然原子（无多写一致性需求，不加显式事务；
 * 幂等由 uk_invoice_tenant_period 兜底，D-7）。</p>
 *
 * <p><b>B3 期初判定数据源</b>：t_governance_log（在 IGNORE_TABLES，显式条件查询）——
 * detail JSON 的 oldEdition/newEdition 三段式即 SubscriptionApplyService/U2 裸通道的审计
 * 契约（AC-F1.6 传导）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String EDITION_TRIAL = "trial";
    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_ISSUED = "issued";
    private static final String STATUS_PAID = "paid";
    private static final String SCHEDULER_OPERATOR = "billing-scheduler";

    private final InvoiceMapper invoiceMapper;
    private final TenantMapper tenantMapper;
    private final PlanMapper planMapper;
    private final QuotaMapper quotaMapper;
    private final GovernanceLogMapper governanceLogMapper;
    private final AuditService auditService;

    // ==================== I3：出账生成（定时/手动同一入口） ====================

    @Override
    public GenerateSummaryVo generatePeriod(String period, Long tenantId, String operator) {
        YearMonth ym = parsePeriod(period);
        List<Tenant> tenants;
        if (tenantId != null) {
            Tenant one = tenantMapper.selectById(tenantId);
            tenants = one != null ? List.of(one) : List.of();
            if (one == null) {
                throw new BizException(ResultCode.NOT_FOUND, "租户不存在: " + tenantId);
            }
        } else {
            // 全量：t_tenant（active 且未逻辑删，T8 契约）；IGNORE_TABLES 直查天然全租户
            tenants = tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                    .eq(Tenant::getStatus, "active"));
        }

        GenerateSummaryVo summary = GenerateSummaryVo.builder()
                .period(period).processed(0).skippedTrial(0).skippedNoPlan(0)
                .overwritten(0).skippedLocked(0).failed(new ArrayList<>()).build();
        for (Tenant tenant : tenants) {
            try {
                switch (generateForTenant(tenant, ym, operator)) {
                    case PROCESSED -> summary.setProcessed(summary.getProcessed() + 1);
                    case OVERWRITTEN -> summary.setOverwritten(summary.getOverwritten() + 1);
                    case SKIPPED_TRIAL -> summary.setSkippedTrial(summary.getSkippedTrial() + 1);
                    case SKIPPED_NO_PLAN -> summary.setSkippedNoPlan(summary.getSkippedNoPlan() + 1);
                    case SKIPPED_LOCKED -> summary.setSkippedLocked(summary.getSkippedLocked() + 1);
                }
            } catch (Exception e) {
                // 单租户失败不中断全量（D-7）：上限镜像 400 等——进 failed 清单，reason 指名
                String reason = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName() + "（无消息）";
                summary.getFailed().add(new GenerateSummaryVo.FailedItem(tenant.getId(), reason));
                log.warn("[Billing] 租户出账失败（不中断全量）: tenantId={}, period={}, reason={}",
                        tenant.getId(), period, reason, e);
            }
        }
        auditGeneration(period, tenantId, summary, operator);
        log.info("[Billing] 出账完成: period={}, processed={}, skippedTrial={}, skippedNoPlan={}, "
                        + "overwritten={}, locked={}, failed={}", period, summary.getProcessed(),
                summary.getSkippedTrial(), summary.getSkippedNoPlan(), summary.getOverwritten(),
                summary.getSkippedLocked(), summary.getFailed().size());
        return summary;
    }

    /** 单租户单账期生成（判定序 ①~⑦，返回归类结果；异常上抛由 generatePeriod 收敛进 failed）。 */
    private GenResult generateForTenant(Tenant tenant, YearMonth ym, String operator) {
        Long tenantId = tenant.getId();   // D-5：全程显式传参，禁 ThreadLocal
        LocalDateTime periodStart = ym.atDay(1).atStartOfDay();
        LocalDateTime periodEnd = ym.plusMonths(1).atDay(1).atStartOfDay();

        // ① B2：全程 trial → 无行（非 0 元账单）
        if (EDITION_TRIAL.equalsIgnoreCase(tenant.getEdition())) {
            return GenResult.SKIPPED_TRIAL;
        }
        // ② D-13：付费但 plan_code 空 → 跳过 + WARN（无价格依据，不出 0 元账单）
        if (tenant.getPlanCode() == null || tenant.getPlanCode().isBlank()) {
            log.warn("[Billing] 付费租户未绑定套餐，跳过出账（D-13，运营补绑定后可手动补生成）: "
                    + "tenantId={}, period={}", tenantId, ym);
            return GenResult.SKIPPED_NO_PLAN;
        }
        // ③ B3：期初为 trial（账期内首个 trial→非trial 变更）→ 转正赠送跳过（优先于折算）
        if (convertedFromTrialWithinPeriod(tenantId, periodStart, periodEnd)) {
            log.info("[Billing] 期初 trial 期内转正，本月赠送不出账（B3）: tenantId={}, period={}",
                    tenantId, ym);
            return GenResult.SKIPPED_TRIAL;
        }
        // ④ B1 期末档：按出账时刻 plan_code 查（含下架——AC-F1.4 存量绑定不受影响；逻辑删排除）
        List<Plan> plans = planMapper.selectList(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getCode, tenant.getPlanCode())
                .orderByDesc(Plan::getId));
        if (plans.isEmpty()) {
            log.warn("[Billing] 绑定套餐行不可见（逻辑删?），跳过出账: tenantId={}, planCode={}, period={}",
                    tenantId, tenant.getPlanCode(), ym);
            return GenResult.SKIPPED_NO_PLAN;
        }
        Plan plan = plans.get(0); // 同 code 多份（仅 custom 合法）取最新一份（与 T6 判定同策略）

        // ⑤ B5 用量聚合（两段 UNION status='success'；t_quota 水位仅快照展示不作口径）
        long usageTokens = orZero(invoiceMapper.sumSuccessTokens(tenantId, periodStart, periodEnd));
        Map<String, Object> features = parseFeatures(plan);
        long tokenLimit = features != null ? longOf(features.get("token_limit")) : 0L;

        // ⑥ Q6 折算判定：期初租户不存在（create_time ≥ 期初）且出账付费 → 折算（B2/B3 已优先拦截）
        boolean prorated = tenant.getCreateTime() != null && !tenant.getCreateTime().isBefore(periodStart);
        BigDecimal prorationAmount = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        int daysInMonth = ym.lengthOfMonth();
        int remainingDays = 0;
        if (prorated) {
            remainingDays = daysInMonth - tenant.getCreateTime().getDayOfMonth() + 1; // 含创建当天（Q6）
            prorationAmount = BillingCalculator.proratedBaseFee(plan.getMonthlyPrice(), daysInMonth, remainingDays);
        }

        // ⑦ Calculator 计算（唯一算术点）+ 快照/明细组装 + 幂等落库
        long overageTokens = BillingCalculator.overageTokens(usageTokens, tokenLimit);
        long ktokenBilled = BillingCalculator.ktokenBilled(overageTokens);
        BigDecimal overageRaw = BillingCalculator.overageFeeRaw(ktokenBilled, plan.getTokenOveragePrice());
        BigDecimal overageAmount = BillingCalculator.overageFee(ktokenBilled, plan.getTokenOveragePrice());
        BigDecimal totalAmount = BillingCalculator.totalAmount(prorationAmount, plan.getMonthlyPrice(), overageRaw);
        // 上限镜像兜底（proration/planPrice 单值超域——正常套餐创建已校验，防御出账侧）
        BillingCalculator.assertWithinDecimal14x2(prorationAmount, "proration_amount");

        String detail = buildDetailJson(plan, tokenLimit, usageTokens, overageTokens, ktokenBilled,
                tenantId, ym, prorated, tenant.getCreateTime(), remainingDays, daysInMonth);

        // 幂等（D-7）：uk 查行 → INSERT（DuplicateKey 兜底转已存在分支）/ draft 覆盖 / issued、paid 保护
        Invoice exist = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getTenantId, tenantId).eq(Invoice::getPeriod, ym.toString()));
        if (exist != null && !STATUS_DRAFT.equals(exist.getStatus())) {
            log.warn("[Billing] 账单已 {}，不覆盖（D-7；如需修正人工作废重开属范围外 §7-1）: "
                    + "tenantId={}, period={}, invoiceId={}", exist.getStatus(), tenantId, ym, exist.getId());
            return GenResult.SKIPPED_LOCKED;
        }
        if (exist == null) {
            Invoice invoice = new Invoice();
            invoice.setTenantId(tenantId); // SYSTEM 上下文拦截器放行——实体显式携带（D-5）
            invoice.setPeriod(ym.toString());
            invoice.setPlanCode(plan.getCode());
            invoice.setPlanPrice(plan.getMonthlyPrice());
            invoice.setUsageTokens(usageTokens);
            invoice.setOverageTokens(overageTokens);
            invoice.setOverageAmount(overageAmount);
            invoice.setProrationAmount(prorationAmount);
            invoice.setTotalAmount(totalAmount);
            invoice.setDetail(detail);
            invoice.setStatus(STATUS_DRAFT);
            invoice.setCreateBy(operator);
            try {
                invoiceMapper.insert(invoice);
                log.info("[Billing] 账单生成: tenantId={}, period={}, total={}, prorated={}, plan={}",
                        tenantId, ym, totalAmount, prorated, plan.getCode());
                return GenResult.PROCESSED;
            } catch (DuplicateKeyException e) {
                // 并发双触发兜底（uk 最终防线）：转已存在分支——重查按 draft 覆盖/锁定处理
                log.warn("[Billing] 并发出账 uk 冲突，转已存在分支: tenantId={}, period={}", tenantId, ym);
                exist = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getTenantId, tenantId).eq(Invoice::getPeriod, ym.toString()));
                if (exist == null || !STATUS_DRAFT.equals(exist.getStatus())) {
                    return GenResult.SKIPPED_LOCKED;
                }
            }
        }
        // draft → 重算覆盖 UPDATE（补记用量后金额更新，AC-F2.7）
        LambdaUpdateWrapper<Invoice> uw = new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, exist.getId())
                .set(Invoice::getPlanCode, plan.getCode())
                .set(Invoice::getPlanPrice, plan.getMonthlyPrice())
                .set(Invoice::getUsageTokens, usageTokens)
                .set(Invoice::getOverageTokens, overageTokens)
                .set(Invoice::getOverageAmount, overageAmount)
                .set(Invoice::getProrationAmount, prorationAmount)
                .set(Invoice::getTotalAmount, totalAmount)
                .set(Invoice::getDetail, detail)
                .set(Invoice::getUpdateBy, operator);
        invoiceMapper.update(null, uw);
        log.info("[Billing] draft 账单重算覆盖: tenantId={}, period={}, total={}", tenantId, ym, totalAmount);
        return GenResult.OVERWRITTEN;
    }

    // ==================== I4：状态流转（AC-F2.8） ====================

    /**
     * 资金状态流转（case-20260824-技术债清偿 T11：审计改<b>同步写</b>）。
     *
     * <p><b>强一致语义</b>：流转 UPDATE 与 bill_transit 审计在同一事务——审计写失败异常上抛，
     * 流转一并回滚，杜绝"资金已变无审计"（原异步审计 + try-catch 吞异常只 WARN，
     * 审计丢失后流转照常提交，违反资金审计完整性）。非资金类审计不受影响（仍走异步 {@code log}）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoiceVo transit(Long id, String target) {
        if (target == null || target.isBlank()
                || !(STATUS_ISSUED.equals(target) || STATUS_PAID.equals(target))) {
            throw new BizException(400, "target 非法: " + target + "（合法值: issued / paid）");
        }
        Invoice exist = loadOr404(id);
        String current = exist.getStatus();
        boolean legal = (STATUS_DRAFT.equals(current) && STATUS_ISSUED.equals(target))
                || (STATUS_ISSUED.equals(current) && STATUS_PAID.equals(target));
        if (!legal) {
            throw new BizException(400, "账单非法流转: " + current + " → " + target
                    + "（仅允许下一顺次 draft→issued→paid；跳变/回退/paid 后流转一律 400，AC-F2.8）");
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<Invoice> uw = new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, id)
                .eq(Invoice::getStatus, current) // 条件更新防并发双流转
                .set(Invoice::getStatus, target)
                .set(STATUS_ISSUED.equals(target), Invoice::getIssuedTime, now)
                .set(STATUS_PAID.equals(target), Invoice::getPaidTime, now);
        if (invoiceMapper.update(null, uw) == 0) {
            throw new BizException(400, "账单状态已变化（并发流转冲突），请刷新重试: " + id);
        }
        String operator = currentUsername();
        // 同步审计（T11）：logSync 在本事务内 INSERT，失败上抛 → 上面 UPDATE 一并回滚。
        // detail 序列化失败同样视为审计失败（上抛回滚，不吞）。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", current);
        detail.put("to", target);
        detail.put("operator", operator);
        detail.put("time", now.format(FORMATTER));
        detail.put("period", exist.getPeriod());
        detail.put("tenantId", exist.getTenantId());
        try {
            auditService.logSync("bill_transit", "bill", String.valueOf(id),
                    OM.writeValueAsString(detail));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("bill_transit 审计写入失败（流转回滚）: " + id, e);
        }
        return toVo(loadOr404(id));
    }

    // ==================== I1/I2：平台侧查询 ====================

    @Override
    public IPage<InvoiceVo> pagePlatform(String period, Long tenantId, String status, long page, long size) {
        LambdaQueryWrapper<Invoice> w = new LambdaQueryWrapper<>();
        if (period != null && !period.isBlank()) {
            w.eq(Invoice::getPeriod, period.trim());
        }
        if (tenantId != null) {
            w.eq(Invoice::getTenantId, tenantId);
        }
        if (status != null && !status.isBlank()) {
            w.eq(Invoice::getStatus, status.trim());
        }
        w.orderByDesc(Invoice::getPeriod).orderByDesc(Invoice::getId);
        return invoiceMapper.selectPage(new Page<>(page, size), w).convert(this::toVo);
    }

    @Override
    public InvoiceDetailVo detail(Long id) {
        Invoice invoice = loadOr404(id);
        return toDetailVo(invoice);
    }

    // ==================== C2/C3：租户侧（draft 隔离第二道语义过滤） ====================

    @Override
    public IPage<InvoiceVo> pageForTenant(Long tenantId, long page, long size) {
        LambdaQueryWrapper<Invoice> w = new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getTenantId, tenantId)
                .in(Invoice::getStatus, List.of(STATUS_ISSUED, STATUS_PAID)) // draft 对租户不可见（AC-F2.9）
                .orderByDesc(Invoice::getPeriod);
        return invoiceMapper.selectPage(new Page<>(page, size), w).convert(this::toVo);
    }

    @Override
    public InvoiceDetailVo detailForTenant(Long tenantId, Long id) {
        Invoice invoice = id != null ? invoiceMapper.selectById(id) : null;
        if (invoice == null || !invoice.getTenantId().equals(tenantId)
                || STATUS_DRAFT.equals(invoice.getStatus())) {
            // draft 或他租户账单 → 404（AC-F2.9/F2.10；拦截器隔离之外第二道过滤）
            throw new BizException(ResultCode.NOT_FOUND, "账单不存在: " + id);
        }
        return toDetailVo(invoice);
    }

    // ==================== 内部 ====================

    /** B3 期初判定（§4.2③）：账期内首个 trial→非trial 变更存在 → 期初为 trial（赠送）。 */
    private boolean convertedFromTrialWithinPeriod(Long tenantId, LocalDateTime start, LocalDateTime end) {
        List<GovernanceLog> logs = governanceLogMapper.selectList(new LambdaQueryWrapper<GovernanceLog>()
                .eq(GovernanceLog::getAction, "tenant_edition_change")
                .eq(GovernanceLog::getResourceType, "tenant")
                .eq(GovernanceLog::getResourceId, String.valueOf(tenantId))
                .ge(GovernanceLog::getCreateTime, start)
                .lt(GovernanceLog::getCreateTime, end)
                .orderByAsc(GovernanceLog::getCreateTime));
        for (GovernanceLog entry : logs) {
            String[] editions = extractEditions(entry.getDetail());
            if (editions != null && EDITION_TRIAL.equalsIgnoreCase(editions[0])
                    && !EDITION_TRIAL.equalsIgnoreCase(editions[1])) {
                return true; // 首个 trial→非trial 变更 ∈ 账期
            }
        }
        return false; // 无审计 fallback（§0.3-2）：出账时付费 + create<期初 → 正常出账（保守少收）
    }

    /** 审计 detail JSON 提取 oldEdition/newEdition（三段式契约；解析失败返回 null 跳过该行）。 */
    private String[] extractEditions(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OM.readTree(detailJson);
            String old = node.path("oldEdition").asText(null);
            String now = node.path("newEdition").asText(null);
            return (old != null && now != null) ? new String[]{old, now} : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** detail JSON 组装（附 A 结构；t_derivation.cost 禁入——本方法零 cost 引用，§8.4）。 */
    private String buildDetailJson(Plan plan, long tokenLimit, long usageTokens, long overageTokens,
                                   long ktokenBilled, Long tenantId, YearMonth ym, boolean prorated,
                                   LocalDateTime createTime, int remainingDays, int daysInMonth) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            Map<String, Object> planSnap = new LinkedHashMap<>();
            planSnap.put("code", plan.getCode());
            planSnap.put("name", plan.getName());
            planSnap.put("monthlyPrice", plan.getMonthlyPrice());
            planSnap.put("tokenOveragePrice", plan.getTokenOveragePrice());
            planSnap.put("slaLevel", plan.getSlaLevel());
            detail.put("plan", planSnap);
            detail.put("tokenUsed", usageTokens);
            detail.put("tokenLimit", tokenLimit);
            detail.put("tokenOverageTokens", overageTokens);
            detail.put("ktokenBilled", ktokenBilled);
            // 展示水位（t_quota 当期行 used 列；仅快照不作账单口径 B5）
            Quota quota = quotaMapper.selectOne(new LambdaQueryWrapper<Quota>()
                    .eq(Quota::getTenantId, tenantId).eq(Quota::getPeriod, ym.toString()));
            detail.put("derivationCount", quota != null && quota.getDerivationUsed() != null
                    ? quota.getDerivationUsed() : 0);
            detail.put("caseCount", quota != null && quota.getCaseUsed() != null ? quota.getCaseUsed() : 0);
            detail.put("storageUsedMb", quota != null && quota.getStorageUsedMb() != null
                    ? quota.getStorageUsedMb() : 0L);
            if (prorated) {
                Map<String, Object> proration = new LinkedHashMap<>();
                proration.put("createdDate", createTime != null ? createTime.toLocalDate().toString() : null);
                proration.put("remainingDays", remainingDays);
                proration.put("daysInMonth", daysInMonth);
                proration.put("prorated", true);
                detail.put("firstMonthProration", proration);
            }
            return OM.writeValueAsString(detail);
        } catch (Exception e) {
            throw new BizException(500, "账单明细组装失败: " + e.getMessage());
        }
    }

    /** 出账审计：定时 → bill_generate_scheduled；手动 → bill_generate（§8.1 清单）。 */
    private void auditGeneration(String period, Long tenantId, GenerateSummaryVo s, String operator) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("period", period);
            detail.put("tenantId", tenantId);
            detail.put("processed", s.getProcessed());
            detail.put("skippedTrial", s.getSkippedTrial());
            detail.put("skippedNoPlan", s.getSkippedNoPlan());
            detail.put("overwritten", s.getOverwritten());
            detail.put("locked", s.getSkippedLocked());
            detail.put("failedCount", s.getFailed().size());
            detail.put("operator", operator);
            String action = SCHEDULER_OPERATOR.equals(operator)
                    ? "bill_generate_scheduled" : "bill_generate";
            auditService.log(action, "bill", period, OM.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("[Billing] 出账审计序列化失败（不阻塞业务）: {}", e.getMessage());
        }
    }

    private static YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new BizException(ResultCode.BAD_REQUEST, "period 不能为空（yyyy-MM）");
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (Exception e) {
            throw new BizException(ResultCode.BAD_REQUEST, "period 格式非法（须 yyyy-MM）: " + period);
        }
    }

    private Map<String, Object> parseFeatures(Plan plan) {
        if (plan.getFeatures() == null || plan.getFeatures().isBlank()) {
            return null;
        }
        try {
            return OM.readValue(plan.getFeatures(), Map.class);
        } catch (Exception e) {
            log.warn("[Billing] 套餐 features 解析失败（token_limit 基准退化 0）: planCode={}", plan.getCode());
            return null;
        }
    }

    private static long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static long orZero(Long v) {
        return v != null ? v : 0L;
    }

    private Invoice loadOr404(Long id) {
        Invoice invoice = id != null ? invoiceMapper.selectById(id) : null;
        if (invoice == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账单不存在: " + id);
        }
        return invoice;
    }

    private InvoiceVo toVo(Invoice i) {
        InvoiceVo vo = new InvoiceVo();
        fillVo(vo, i);
        return vo;
    }

    private InvoiceDetailVo toDetailVo(Invoice i) {
        InvoiceDetailVo vo = new InvoiceDetailVo();
        fillVo(vo, i);
        if (i.getDetail() != null && !i.getDetail().isBlank()) {
            try {
                vo.setDetail(OM.readValue(i.getDetail(), Map.class));
            } catch (Exception e) {
                log.warn("[Billing] detail JSON 解析失败（回显 null）: invoiceId={}", i.getId());
            }
        }
        return vo;
    }

    private void fillVo(InvoiceVo vo, Invoice i) {
        vo.setId(i.getId());
        vo.setTenantId(i.getTenantId());
        vo.setPeriod(i.getPeriod());
        vo.setPlanCode(i.getPlanCode());
        vo.setPlanPrice(i.getPlanPrice());
        vo.setUsageTokens(i.getUsageTokens());
        vo.setOverageTokens(i.getOverageTokens());
        vo.setOverageAmount(i.getOverageAmount());
        vo.setProrationAmount(i.getProrationAmount());
        vo.setTotalAmount(i.getTotalAmount());
        vo.setStatus(i.getStatus());
        vo.setIssuedTime(i.getIssuedTime() != null ? i.getIssuedTime().format(FORMATTER) : null);
        vo.setPaidTime(i.getPaidTime() != null ? i.getPaidTime().format(FORMATTER) : null);
        vo.setCreateBy(i.getCreateBy());
        vo.setCreateTime(i.getCreateTime() != null ? i.getCreateTime().format(FORMATTER) : null);
    }

    private String currentUsername() {
        var claims = com.eaiselp.common.security.LoginUser.get();
        return claims != null && claims.getUsername() != null ? claims.getUsername() : "";
    }

    /** 单租户生成结果归类（I3 汇总计数）。 */
    enum GenResult {
        PROCESSED, OVERWRITTEN, SKIPPED_TRIAL, SKIPPED_NO_PLAN, SKIPPED_LOCKED
    }
}
