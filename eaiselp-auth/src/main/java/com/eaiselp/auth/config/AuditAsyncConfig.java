package com.eaiselp.auth.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 审计日志异步线程池配置（M3-2，auth 模块）。
 *
 * <p>提供名为 {@code runtimeAuditExecutor} 的 bean，供 {@code AuditLogger.@Async("runtimeAuditExecutor")} 使用。
 * 与 runtime 模块同名同参，统一审计日志写入行为（auth 模块独立进程，需各自配置 bean）。
 *
 * <p><b>拒绝策略 CallerRunsPolicy</b>：审计日志是合规要求，队列满时由调用线程同步写，
 * 宁可慢一点也要落库（reliability-governance §全链路可追溯）。
 */
@Slf4j
@Configuration
public class AuditAsyncConfig {

    @Bean("runtimeAuditExecutor")
    public ThreadPoolTaskExecutor runtimeAuditExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("auth-audit-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        log.info("[AuditAsyncConfig] runtimeAuditExecutor 初始化: core=2, max=8, queue=200, 拒绝策略=CallerRunsPolicy");
        return ex;
    }
}
