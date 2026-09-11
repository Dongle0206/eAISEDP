package com.eaiselp.runtime.subscription;

import com.eaiselp.data.service.subscription.InvoiceService;
import com.eaiselp.data.service.subscription.dto.GenerateSummaryVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 月度出账定时任务宿主（case-20260823-商用化 T8，SE D-6/裁决 Q2/#3；AC-F2.7 定时侧）。
 *
 * <p><b>触发</b>：{@code @Scheduled(cron="0 0 2 1 * *", zone="Asia/Shanghai")}——每月 1 日
 * 02:00 错峰（避开 0 点批处理窗口，PRD §6.1）；出账账期 = 上一个自然月。</p>
 *
 * <p><b>运营熔断开关</b>：{@code @ConditionalOnProperty(eaiselp.billing.scheduler-enabled,
 * matchIfMissing=true)}——生产紧急停出账只改 yml 不改代码；测试 profile 置 false
 * （CapabilityLoader auto-refresh 先例升级为 bean 级关闭）。</p>
 *
 * <p><b>防重入三层</b>：① AtomicBoolean CAS（提交前占位）② billingExecutor core/max=1/
 * queue=0 + AbortPolicy（池满立即拒绝）③ uk_invoice_tenant_period（D-7 最终兜底，Service 层）。
 * 方法体只做 CAS + 提交独立池——<b>不占用 @Scheduled 默认单线程调度器</b>（与 CapabilityLoader
 * 共用，长任务直接跑会阻塞 30s 热重载）、不抢 orchestrationExecutor（裁决 #3）。</p>
 *
 * <p><b>SYSTEM 上下文（D-5）</b>：定时线程无登录上下文 → TenantContext 缺省 0 = SYSTEM，
 * 拦截器全放行；InvoiceService 全程显式传 tenantId，禁依赖 ThreadLocal。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.billing.scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class BillingGenerationTask {

    /** 出账账期时区（D-16 单时区部署约定） */
    static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");
    static final String SCHEDULER_OPERATOR = "billing-scheduler";

    private final InvoiceService invoiceService;
    private final ThreadPoolTaskExecutor billingExecutor;
    /** CAS 防重入占位（跨触发的"上一轮尚未提交完成/仍在跑"快速路径） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BillingGenerationTask(InvoiceService invoiceService,
                                 ThreadPoolTaskExecutor billingExecutor) {
        this.invoiceService = invoiceService;
        this.billingExecutor = billingExecutor;
    }

    @Scheduled(cron = "0 0 2 1 * *", zone = "Asia/Shanghai")
    public void scheduled() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[Billing] 上一次出账触发尚未结束，跳过本次调度（CAS 防重入）");
            return;
        }
        String period = lastMonthPeriod();
        try {
            billingExecutor.execute(() -> {
                try {
                    log.info("[Billing] 定时出账开始: period={}, zone={}", period, BILLING_ZONE);
                    GenerateSummaryVo summary = invoiceService.generatePeriod(period, null, SCHEDULER_OPERATOR);
                    log.info("[Billing] 定时出账结束: period={}, processed={}, skippedTrial={}, "
                                    + "skippedNoPlan={}, overwritten={}, locked={}, failed={}",
                            period, summary.getProcessed(), summary.getSkippedTrial(),
                            summary.getSkippedNoPlan(), summary.getOverwritten(),
                            summary.getSkippedLocked(), summary.getFailed().size());
                } catch (Exception e) {
                    // 全量级异常（单租户失败已由 Service 收敛进 failed 清单不中断，D-7）
                    log.error("[Billing] 定时出账异常终止: period={}", period, e);
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false); // 回滚占位（拒绝即本轮结束）
            log.warn("[Billing] 上一次出账仍在运行，跳过本次触发（billingExecutor 拒绝）: period={}", period);
        }
    }

    /** 出账账期 = 上一个自然月（每月 1 日 02:00 触发出上月账，B1 月末快照=账期结束时刻生效档）。 */
    static String lastMonthPeriod() {
        return LocalDate.now(BILLING_ZONE).withDayOfMonth(1).minusMonths(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
