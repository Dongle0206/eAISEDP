package com.eaiselp.common.security;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * JWT payload 载体（对齐 PRD §5.2.1）。
 * 不含 permissions（Q-5：防 token 膨胀，permissions 由 /current 实时查）。
 */
@Data
@Builder
public class JwtClaims {
    private Long userId;
    private String username;
    private String displayName;
    private Long tenantId;
    private String tenantCode;
    private List<String> roles;   // 角色码数组
    private Long iat;             // 签发时间（秒）
    private Long exp;             // 过期时间（秒）
}
