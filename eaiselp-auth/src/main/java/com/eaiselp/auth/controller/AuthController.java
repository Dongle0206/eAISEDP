package com.eaiselp.auth.controller;

import com.eaiselp.auth.dto.LoginRequest;
import com.eaiselp.auth.dto.LoginResponse;
import com.eaiselp.auth.dto.UserInfo;
import com.eaiselp.auth.service.AuthService;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** POST /api/v1/auth/login —— 用户登录（白名单，不需 token）*/
    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req));
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
