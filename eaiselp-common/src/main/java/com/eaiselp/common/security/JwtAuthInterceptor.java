package com.eaiselp.common.security;

import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 认证拦截器：解析 token → 注入 LoginUser + TenantContext。
 * 不查库（权限校验由 PermissionInterceptor 单独负责）。
 * 失败：无 token→40101；token 无效/过期→40102。HTTP 401。
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final ObjectMapper OM = new ObjectMapper();

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws IOException {
        // CORS 预检请求（OPTIONS）直接放行，不校验 token
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        String auth = req.getHeader(HEADER);
        if (auth == null || !auth.startsWith(PREFIX)) {
            return writeUnauthorized(resp, ResultCode.UNAUTHORIZED, "未登录或 token 缺失");
        }
        String token = auth.substring(PREFIX.length()).trim();
        try {
            JwtClaims claims = jwtUtil.parse(token);
            LoginUser.set(claims);
            return true;
        } catch (ExpiredJwtException e) {
            log.info("[Auth] token 过期: user={}", e.getClaims().get("username"));
            return writeUnauthorized(resp, ResultCode.TOKEN_INVALID, "token 无效或已过期");
        } catch (JwtException e) {
            log.info("[Auth] token 无效: {}", e.getMessage());
            return writeUnauthorized(resp, ResultCode.TOKEN_INVALID, "token 无效或已过期");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        LoginUser.clear();   // 必清，防 ThreadLocal 泄漏
    }

    private boolean writeUnauthorized(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(HttpStatus.UNAUTHORIZED.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(OM.writeValueAsString(R.fail(code, msg)));
        return false;
    }
}
