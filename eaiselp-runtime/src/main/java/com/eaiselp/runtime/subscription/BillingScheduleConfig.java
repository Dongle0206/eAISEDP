package com.eaiselp.runtime.subscription;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 出账独立线程池配置（case-20260823-商用化 T8，SE D-6/裁决 #3）。
 *
 * <p><b>core=1 / max=1 / queue=0 / AbortPolicy</b>：月度出账天然串行（同一时刻至多一份
 * 全量任务在跑）；queue=0 使并发触发立即拒绝（AbortPolicy 抛 RejectedExecutionException →
 * BillingGenerationTask 捕获 WARN"上一次出账仍在运行，跳过"）。
 * <b>不占用 @Scheduled 默认单线程调度器</b>（与 CapabilityLoader 30s 热重载共用，出账 60s 级
 * 长任务直接占用会阻塞热重载）、<b>不抢 orchestrationExecutor</b>（裁决 #3）——AsyncConfig
 * 既有池零改动，本类为独立新增 Config。</p>
 */
@Slf4j
@Configuration
public class BillingScheduleConfig {

    @Bean("billingExecutor")
    public ThreadPoolTaskExecutor billingExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(0);
        ex.setThreadNamePrefix("billing-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(300);
        ex.initialize();
        log.info("[BillingScheduleConfig] billingExecutor 初始化: core=1, max=1, queue=0, AbortPolicy（出账专用独立池）");
        return ex;
    }
}
