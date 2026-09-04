package com.eaiselp.common.result;

/** 业务错误码常量（对齐 PRD §5.1.3）。 */
public final class ResultCode {
    public static final int SUCCESS = 0;
    public static final int BAD_REQUEST = 40000;      // 请求参数错误（M3-3 新增：用户管理 CRUD 校验失败）
    public static final int BAD_CREDENTIAL = 40001;   // 用户名或密码错误（不区分用户不存在 vs 密码错，防枚举）
    public static final int ACCOUNT_DISABLED = 40002; // 账户已禁用
    public static final int TRIAL_EXPIRED = 40003;    // 试用已到期（case-20260820 F3，Q9 定稿：4000x 家族顺延）
    public static final int NOT_FOUND = 40400;        // 资源不存在（M3-3 新增：用户/资源找不到）
    public static final int UNAUTHORIZED = 40101;     // 未登录或 token 缺失
    public static final int TOKEN_INVALID = 40102;    // token 无效或已过期
    public static final int FORBIDDEN = 40301;        // 无权限访问该资源
    public static final int RATE_LIMITED = 42901;     // 预留：gateway 限流
    public static final int INTERNAL_ERROR = 50000;   // 服务内部错误
    private ResultCode() {}
}
