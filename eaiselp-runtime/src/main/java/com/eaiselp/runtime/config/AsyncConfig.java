package com.eaiselp.runtime.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置（M2-DFX，SE 技术方案 §4.3.2）。
 *
 * <p>{@code @EnableAsync} 激活 {@link org.springframework.scheduling.annotation.Async} 代理，
 * 使 {@code DerivationAsyncRunner.deriveAsync} 真正异步执行。
 *
 * <p><b>runtimeLlmExecutor 线程池参数（SE §4.3.2 / §10 D-2）</b>：
 * <ul>
 *   <li>core=5：LLM 是外部慢 IO + 烧 token，core 设低防突发烧光配额；
 *       且应与 LLM provider QPS 限额挂钩（GLM 若限 5 QPS，core>5 也只被 provider 限速徒增超时）。</li>
 *   <li>max=20：突发上限。</li>
 *   <li>queue=50：缓冲队列，core+queue+max 最多承载 70 个任务。</li>
 *   <li>拒绝策略 {@link ThreadPoolExecutor.AbortPolicy}（SE §10 D-5 已裁断）：
 *       抛 RejectedExecutionException → Controller 捕获返回 503。</li>
 *   <li><b>禁用 CallerRunsPolicy</b>：异步化后提交者是 Tomcat 线程，
 *       CallerRuns 会让 Tomcat 线程跑 LLM，摧毁线程池隔离初衷（SE §4.1.5 明裁）。</li>
 * </ul>
 *
 * <p>参数外置到 application.yml（{@code eaiselp.async.llm.*}），便于调参不重打包。
 *
 * <p>优雅停机：{@code waitForTasksToCompleteOnShutdown=true} + 60s，
 * 给在飞 LLM 调用最多 60s 收尾。
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig {

    @Value("${eaiselp.async.llm.core:5}")
    private int corePoolSize;

    @Value("${eaiselp.async.llm.max:20}")
    private int maxPoolSize;

    @Value("${eaiselp.async.llm.queue:50}")
    private int queueCapacity;

    @Bean("runtimeLlmExecutor")
    public ThreadPoolTaskExecutor runtimeLlmExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(corePoolSize);
        ex.setMaxPoolSize(maxPoolSize);
        ex.setQueueCapacity(queueCapacity);
        ex.setThreadNamePrefix("runtime-llm-");
        // SE §4.1.5 / D-5：AbortPolicy（禁用 CallerRunsPolicy，防 Tomcat 线程跑 LLM 摧毁隔离）
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);   // 优雅停机
        ex.setAwaitTerminationSeconds(60);              // 给在飞 LLM 调用最多 60s 收尾
        ex.initialize();
        log.info("[AsyncConfig] runtimeLlmExecutor 初始化: core={}, max={}, queue={}, 拒绝策略=AbortPolicy",
                corePoolSize, maxPoolSize, queueCapacity);
        return ex;
    }
}
