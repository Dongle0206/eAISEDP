package com.eaiselp.common.tenant;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 租户上下文过滤器：从 Header 解析 tenant_id。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter implements Filter {

    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        try {
            String tid = request.getHeader(HEADER_TENANT_ID);
            if (tid != null && !tid.isEmpty()) {
                try {
                    TenantContext.set(Long.parseLong(tid));
                } catch (NumberFormatException ignore) {}
            }
            chain.doFilter(req, resp);
        } finally {
            TenantContext.clear();
        }
    }
}
