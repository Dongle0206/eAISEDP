package com.eaiselp.common.exception;

/**
 * 配额超限异常（M2 SP-7，ES-003 §9.3 P11）。
 *
 * <p>派生提交前对租户当月配额做强校验（派生次数 / token 消耗），任一超限即抛此异常。
 * 由 {@code GlobalExceptionHandler} 捕获后转 HTTP 429（与限流语义一致：资源耗尽类拒绝），
 * 引导前端提示用户升级档位或等待次月重置。
 *
 * <p>继承 RuntimeException（非受检），避免在调用链签名上到处声明 throws。
 * 与 {@link com.eaiselp.common.ratelimit.RateLimitedException} 区别：
 * 限流是「瞬时速率」超限，配额是「累计额度」耗尽，二者语义不同但都映射 429。
 */
public class QuotaExceededException extends RuntimeException {

    /** 配额维度标识：derivation（派生次数）/ token（token 消耗）。 */
    private final String quotaType;

    public QuotaExceededException(String quotaType, String message) {
        super(message);
        this.quotaType = quotaType;
    }

    public String getQuotaType() {
        return quotaType;
    }
}
