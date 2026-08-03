package com.eaiselp.adapter.resilience;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 熔断器门面（按 provider 维护独立 {@link CircuitBreaker}）。
 *
 * <p>{@code @Component} 由 Spring 扫描装配；适配器（如 {@code GlmLlmAdapter}）注入后，
 * 在 invoke 开头调 {@link #allowRequest(String)} 判定是否放行，
 * 成功调 {@link #recordSuccess(String)}、异常调 {@link #recordFailure(String)}。
 *
 * <p>provider 维度隔离：单个 provider（如 glm）熔断不影响其他 provider（如 deepseek）。
 * 实例按需懒创建（{@link ConcurrentHashMap#computeIfAbsent}），首次访问某 provider 时才建。
 */
@Component
public class LlmCircuitBreaker {

    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /** 是否放行指定 provider 的请求。 */
    public boolean allowRequest(String provider) {
        return get(provider).allowRequest();
    }

    /** 记录指定 provider 一次成功（重置计数，状态回 CLOSED）。 */
    public void recordSuccess(String provider) {
        get(provider).recordSuccess();
    }

    /** 记录指定 provider 一次失败（累计失败计数，达阈值切 OPEN）。 */
    public void recordFailure(String provider) {
        get(provider).recordFailure();
    }

    /** 获取指定 provider 的熔断器（不存在则懒创建）。包级可见便于测试。 */
    CircuitBreaker get(String provider) {
        if (provider == null || provider.isBlank()) {
            provider = "default";
        }
        return breakers.computeIfAbsent(provider, k -> new CircuitBreaker());
    }
}
