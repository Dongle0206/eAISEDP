package com.eaiselp.common.tenant;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TenantContextFilter 单测（case-20260824-技术债清偿 T12，Security S2 根除）。
 *
 * <p>验收口径：X-Tenant-Id 请求头<b>伪造无效</b>——过滤器不再解析任何租户头，
 * 租户上下文唯一来源是 JWT（LoginUser.set 派生）。头伪造用例 + finally 清理回归。</p>
 */
class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clean() {
        TenantContext.clear();
        com.eaiselp.common.security.LoginUser.clear();
    }

    @Test
    void 头伪造X_TenantId_不再注入租户上下文() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-Id", "999"); // 伪造他租户
        MockHttpServletResponse resp = new MockHttpServletResponse();
        // 链内断言：过滤器放行后 TenantContext 仍是系统租户 0（未被头污染）
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest request,
                                   jakarta.servlet.http.HttpServletResponse response) {
                assertEquals(TenantContext.SYSTEM_TENANT, TenantContext.get(),
                        "伪造 X-Tenant-Id=999 不得注入租户上下文（T12：头解析已删，仅 JWT 派生）");
            }
        });

        filter.doFilter(req, resp, chain);

        assertTrue(chain.getRequest() != null, "过滤器放行请求");
    }

    @Test
    void 请求结束_清理ThreadLocal_防线程池串租户() throws Exception {
        TenantContext.set(7L); // 模拟拦截器注入后线程池复用残留

        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, new MockFilterChain());

        assertEquals(TenantContext.SYSTEM_TENANT, TenantContext.get(),
                "finally 兜底清理：请求结束 TenantContext 归零（线程池复用防串租户）");
    }

    @Test
    void 链内异常_仍清理上下文() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TenantContext.set(3L);

        assertThrows(ServletException.class, () -> filter.doFilter(req, resp,
                (request, response) -> {
                    throw new ServletException("boom");
                }));
        assertEquals(TenantContext.SYSTEM_TENANT, TenantContext.get(),
                "异常路径 finally 同样清理");
    }
}
