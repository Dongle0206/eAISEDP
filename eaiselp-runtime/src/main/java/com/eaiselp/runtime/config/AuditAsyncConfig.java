package com.eaiselp.runtime.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 审计日志异步线程池配置（M3-2）。
 *
 * <p>提供名为 {@code runtimeAuditExecutor} 的 bean，供 {@code AuditLogger.@Async("runtimeAuditExecutor")} 使用。
 *
 * <p><b>线程池参数</b>：
 * <ul>
 *   <li>core=2：审计日志是 IO 写库，非热点路径，core 设低避免占用资源。</li>
 *   <li>max=8：突发上限（高并发登录/Case 创建场景）。</li>
 *   <li>queue=200：缓冲队列，core+queue+max 最多承载 208 条审计日志。</li>
 *   <li>拒绝策略 {@link ThreadPoolExecutor.CallerRunsPolicy}：与 LLM 线程池不同，审计日志走 CallerRuns——
 *       队列满时由 Tomcat 业务线程直接同步写库（短暂阻塞可接受，比丢审计日志更合规）。
 *       审计日志是合规要求，宁可慢一点也要落库（reliability-governance §全链路可追溯）。</li>
 * </ul>
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
        ex.setThreadNamePrefix("runtime-audit-");
        // 审计日志不能丢：队列满时由调用线程同步写（CallerRunsPolicy）
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        log.info("[AuditAsyncConfig] runtimeAuditExecutor 初始化: core=2, max=8, queue=200, 拒绝策略=CallerRunsPolicy");
        return ex;
    }
}
