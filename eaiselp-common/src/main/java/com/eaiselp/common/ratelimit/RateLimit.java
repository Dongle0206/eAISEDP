package com.eaiselp.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（M2-DFX，SE 技术方案 §4.2.2）。
 *
 * <p>贴在 Controller 方法上，由 {@link RateLimitInterceptor} 读取。
 * 基于 Bucket4j 令牌桶（内存），按 {@link #key()} 维度分桶独立计数。
 * 超限抛 {@link RateLimitedException}，由 GlobalExceptionHandler 统一转 429 + Retry-After。
 *
 * <p>典型配置（SE §4.2.3）：
 * <ul>
 *   <li>登录 POST /login：{@code key=IP, capacity=5, refillPerMin=5}</li>
 *   <li>派生 POST /derive：{@code key=TENANT, capacity=10, refillPerMin=10}</li>
 *   <li>通用：{@code key=USER, capacity=100, refillPerMin=100}</li>
 * </ul>
 *
 * <p>语义：{@code capacity} = 桶容量（瞬时突发上限）；{@code refillPerMin} = 每分钟匀速补充令牌数。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 桶名（用于日志/监控区分，与 key 拼接成桶标识）。 */
    String name() default "default";

    /** 限流维度：IP / TENANT / USER（SE §4.2.4 Key 解析策略）。 */
    KeyType key() default KeyType.USER;

    /** 桶容量（令牌上限，允许瞬时突发）。 */
    int capacity() default 100;

    /** 每分钟匀速补充令牌数（令牌桶 refill 速率）。 */
    int refillPerMin() default 100;

    /** 超限返回给前端的提示文案。 */
    String message() default "请求过于频繁，请稍后再试";

    /** Retry-After 头秒数（默认 60s，引导前端退避）。 */
    int retryAfterSeconds() default 60;

    /** 限流 Key 维度。 */
    enum KeyType { IP, TENANT, USER }
}
