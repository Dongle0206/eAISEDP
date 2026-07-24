package com.eaiselp.common.web;

import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.ratelimit.RateLimitedException;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
