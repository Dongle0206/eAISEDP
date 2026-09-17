package com.eaiselp.common.web;

import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.exception.QuotaExceededException;
import com.eaiselp.common.ratelimit.RateLimitedException;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 全局异常处理：BizException→对应 code；校验异常→40001；未知→50000。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("[Biz] code={}, msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        return R.fail(ResultCode.BAD_CREDENTIAL, "用户名或密码错误");
    }

    /**
     * 请求体不可读（case-20260824-技术债清偿 T5，平台-S6）：坏 JSON / 缺 body / 类型不匹配的
     * {@code @RequestBody} 反序列化失败原先落入兜底 handler → 50000（客户端错误被当服务端错误）。
     * 归位 400（语义增强兼容：原本就是失败响应，仅 code/msg 修正），不回显解析器内部细节。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[NotReadable] 请求体格式错误: {}", e.getMessage());
        return R.fail(400, "请求体格式错误");
    }

    /**
     * 方法参数类型不匹配（T5 同径）：如 {@code /api/v1/xxx/{id}} 传非数字——
     * 客户端参数错误归位 400，指名参数与目标类型，不泄露内部堆栈。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String paramName = e.getName();
        log.warn("[TypeMismatch] 参数类型错误: name={}, value={}", paramName,
                e.getValue() != null ? String.valueOf(e.getValue()) : null);
        return R.fail(400, "请求参数类型错误: " + paramName);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleUnknown(Exception e) {
        log.error("[Unknown] 服务内部错误", e);
        return R.fail(ResultCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试");
    }

    /**
     * 限流超限（M2-DFX，SE §4.2.5）：返回 HTTP 429 + Retry-After 头。
     *
     * <p>Retry-After 引导前端退避（秒数取自 {@link RateLimitedException}，
     * 默认 60s，可在 @RateLimit 注解上覆盖）。
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<R<Void>> handleRateLimited(RateLimitedException e) {
        log.info("[RateLimit] 429 限流触发: msg={}", e.getMessage());
        return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(R.fail(429, e.getMessage()));
    }

    /**
     * 配额超限（M2 SP-7，ES-003 §9.3）：返回 HTTP 429。
     *
     * <p>与限流同属「资源耗尽类拒绝」，复用 429 状态码语义。区别：
     * 限流是瞬时速率超限（Retry-After 后可重试），配额是当月累计额度耗尽
     * （次月重置前重试无意义），故不挂 Retry-After 头。
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<R<Void>> handleQuotaExceeded(QuotaExceededException e) {
        log.info("[Quota] 429 配额超限触发: type={}, msg={}", e.getQuotaType(), e.getMessage());
        return ResponseEntity.status(429)
                .body(R.fail(429, e.getMessage()));
    }
}
