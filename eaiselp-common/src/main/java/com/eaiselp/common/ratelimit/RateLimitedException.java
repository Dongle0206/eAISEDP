package com.eaiselp.common.ratelimit;

/**
 * 限流超限异常（M2-DFX，SE §4.2.4）。
 *
 * <p>由 {@link RateLimitInterceptor} 在令牌桶 tryConsume 失败时抛出，
 * GlobalExceptionHandler 捕获后转 HTTP 429 + {@code Retry-After} 头。
 *
 * <p>继承 RuntimeException（非受检），避免在 Controller 方法签名上到处声明 throws。
 */
public class RateLimitedException extends RuntimeException {

    /** Retry-After 头秒数（取自注解 retryAfterSeconds，默认 60）。 */
    private final int retryAfterSeconds;

    public RateLimitedException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
