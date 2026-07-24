package com.eaiselp.common.security;

import com.eaiselp.common.tenant.TenantContext;

/**
 * 当前登录用户上下文（ThreadLocal）。JwtAuthInterceptor 解析后注入，请求结束清理。
 * JWT 模式下 tenant 以 token 为准（LoginUser.set 覆盖 TenantContext），前端不传 X-Tenant-Id。
 */
public class LoginUser {
    private static final ThreadLocal<JwtClaims> CURRENT = new ThreadLocal<>();

    public static void set(JwtClaims claims) {
        CURRENT.set(claims);
        if (claims != null && claims.getTenantId() != null) {
            TenantContext.set(claims.getTenantId());   // 同步注入租户上下文（多租户隔离）
        }
    }
    public static JwtClaims get() { return CURRENT.get(); }
    public static Long getUserId() { JwtClaims c = CURRENT.get(); return c == null ? null : c.getUserId(); }
    public static void clear() {
        CURRENT.remove();
        TenantContext.clear();
    }
}
