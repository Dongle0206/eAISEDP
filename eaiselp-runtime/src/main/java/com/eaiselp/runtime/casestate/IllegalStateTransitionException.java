package com.eaiselp.runtime.casestate;

import com.eaiselp.common.exception.BizException;

/**
 * Case 状态流转非法异常（M2 SP-3）。
 *
 * <p>继承 {@link BizException}，由 GlobalExceptionHandler 统一捕获转 R.fail(code, msg)，
 * code=400 表示业务校验失败（非法流转路径 / Case 不存在）。客户端据此渲染明确错误。
 */
public class IllegalStateTransitionException extends BizException {

    /** 业务校验失败码（非法状态流转）。 */
    private static final int CODE_ILLEGAL_TRANSITION = 400;

    public IllegalStateTransitionException(String message) {
        super(CODE_ILLEGAL_TRANSITION, message);
    }

    public IllegalStateTransitionException(String message, Throwable cause) {
        super(CODE_ILLEGAL_TRANSITION, message);
        initCause(cause);
    }
}
