package com.eaiselp.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/** JWT 工具（HS256）。签发方=auth，校验方=runtime/gateway/auth 自身。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final SecurityProperties props;
    private SecretKey key;

    @PostConstruct
    public void init() {
        byte[] bytes = props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT secret 必须 ≥32 字节（HS256 要求），当前=" + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** 签发 token。 */
    public String generate(JwtClaims claims) {
        long now = System.currentTimeMillis();
        long expMs = now + props.getJwt().getExpireSeconds() * 1000L;
        return Jwts.builder()
                .issuer(props.getJwt().getIssuer())
                .subject(String.valueOf(claims.getUserId()))
                .claim("userId", claims.getUserId())
                .claim("username", claims.getUsername())
                .claim("displayName", claims.getDisplayName())
                .claim("tenantId", claims.getTenantId())
                .claim("tenantCode", claims.getTenantCode())
                .claim("roles", claims.getRoles())
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析并校验 token，失败抛 JwtException（含 ExpiredJwtException/SignatureException）。 */
    public JwtClaims parse(String token) {
        Claims c = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Object rolesObj = c.get("roles", List.class);
        @SuppressWarnings("unchecked")
        List<String> roles = rolesObj == null ? List.of() : (List<String>) rolesObj;
        return JwtClaims.builder()
                .userId(c.get("userId", Long.class))
                .username(c.get("username", String.class))
                .displayName(c.get("displayName", String.class))
                .tenantId(c.get("tenantId", Long.class))
                .tenantCode(c.get("tenantCode", String.class))
                .roles(roles)
                .iat(c.getIssuedAt() != null ? c.getIssuedAt().getTime() / 1000 : null)
                .exp(c.getExpiration() != null ? c.getExpiration().getTime() / 1000 : null)
                .build();
    }

    public long getExpireSeconds() {
        return props.getJwt().getExpireSeconds();
    }
}
