package com.eaiselp.runtime.security;

import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** @RequiresPermission 校验拦截器：查 PermissionService，任一权限码满足即通过，否则 40301。 */
@Slf4j
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private static final ObjectMapper OM = new ObjectMapper();
    private final PermissionService permissionService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws IOException {
        // CORS 预检请求（OPTIONS）直接放行
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        if (!(handler instanceof HandlerMethod hm)) return true;
        // 方法级注解优先，类级次之
        RequirePermission ann = hm.getMethodAnnotation(RequirePermission.class);
        if (ann == null) ann = hm.getBeanType().getAnnotation(RequirePermission.class);
        if (ann == null) return true;   // 无注解不校验

        Long userId = LoginUser.getUserId();
        if (userId == null) {
            return writeForbidden(resp, ResultCode.UNAUTHORIZED, "未登录");
        }
        List<Long> roleIds = permissionService.getRoleIdsByUserId(userId);
        List<String> userPerms = permissionService.getPermissionCodesByRoleIds(roleIds);
        boolean ok = Arrays.stream(ann.value()).anyMatch(userPerms::contains);
        if (!ok) {
            log.warn("[Perm] 拒绝: userId={}, 需要={}, 持有={}", userId, Arrays.toString(ann.value()), userPerms);
            return writeForbidden(resp, ResultCode.FORBIDDEN, "无权限访问该资源");
        }
        return true;
    }

    private boolean writeForbidden(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(code == ResultCode.UNAUTHORIZED ? HttpStatus.UNAUTHORIZED.value() : HttpStatus.FORBIDDEN.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(OM.writeValueAsString(R.fail(code, msg)));
        return false;
    }
}
