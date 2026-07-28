package com.eaiselp.auth.controller;

import com.eaiselp.auth.dto.LoginRequest;
import com.eaiselp.auth.dto.LoginResponse;
import com.eaiselp.auth.dto.UserInfo;
import com.eaiselp.auth.service.AuthService;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    /** POST /api/v1/auth/login —— 用户登录（白名单，不需 token）*/
    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        try {
            LoginResponse resp = authService.login(req);
            // 审计：登录成功（userId 已在 resp.user 中，但此时 LoginUser 尚未注入拦截器——
            // login 接口是白名单，无 JWT，AuditService 内部 LoginUser.get() 会返回 null，
            // 故这里 userId/username 通过 detail 传递，AuditServiceImpl 的 claims=null 路径会兜底 tenantId=0）
            auditService.log("login_success", "user",
                    resp.getUser() != null ? String.valueOf(resp.getUser().getId()) : null,
                    "{\"username\":\"" + safeJson(req.getUsername()) + "\"}");
            return R.ok(resp);
        } catch (BizException e) {
            // 审计：登录失败（凭据错误/账户禁用），不暴露具体原因到 detail（防枚举）
            auditService.log("login_failure", "user", null,
                    "{\"username\":\"" + safeJson(req.getUsername()) + "\"}",
                    "failure", e.getMessage());
            throw e;
        }
    }

    /** 转义 JSON 字符串中的特殊字符，防止 detail 注入。 */
    private String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** GET /api/v1/auth/current —— 恢复登录态（需 token，JwtAuthInterceptor 已注入 LoginUser）*/
    @GetMapping("/current")
    public R<UserInfo> current() {
        // LoginUser 由拦截器注入；userId/tenantId 从 JWT claims 取（权威，不可伪造）
        JwtClaims claims = LoginUser.get();
        if (claims == null || claims.getUserId() == null) {
            return R.fail(ResultCode.UNAUTHORIZED, "未登录");
        }
        return R.ok(authService.currentUser(claims.getUserId(), claims.getTenantId()));
    }

    /** POST /api/v1/auth/logout —— 退出（需 token；M2 仅日志，前端清 storage 为主）*/
    @PostMapping("/logout")
    public R<Void> logout() {
        JwtClaims claims = LoginUser.get();
        if (claims != null) {
            authService.logout(claims.getUserId());
        }
        return R.ok();
    }
}
