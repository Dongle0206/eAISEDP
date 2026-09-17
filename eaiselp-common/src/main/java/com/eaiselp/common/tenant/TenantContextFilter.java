package com.eaiselp.common.tenant;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 租户上下文过滤器（case-20260824-技术债清偿 T12：删 X-Tenant-Id 头解析，仅保留兜底清理）。
 *
 * <p><b>Security S2 根除</b>：租户上下文唯一派生来源 = JWT（{@code JwtAuthInterceptor} 解析
 * 后经 {@code LoginUser.set} 同步注入 {@link TenantContext}），不再信任任何请求头——
 * 原头解析等价于"新增白名单即越权面"（伪造 X-Tenant-Id 可指定任意租户），已删除。
 * CLAUDE.md §9.3-5 中"gateway 注入 X-Tenant-Id 头 + runtime 读头"的演进设计被本裁决否决。</p>
 *
 * <p><b>保留职责</b>：请求结束 finally 清理 ThreadLocal，防线程池复用串租户
 * （与 LoginUser.clear 同口径，作为拦截器漏清时的第二道兜底）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        try {
            // 刻意不读任何租户头（T12）：tenant 仅由 JWT 派生，请求头无法伪造
            chain.doFilter(req, resp);
        } finally {
            TenantContext.clear();
        }
    }
}
