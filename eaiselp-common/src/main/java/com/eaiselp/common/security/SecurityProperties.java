package com.eaiselp.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全相关配置（@ConfigurationProperties 绑定 eaiselp.security.*）。
 * 严禁明文写死密钥，走环境变量 JWT_SECRET（同 GLM_API_KEY 模式）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "eaiselp.security")
public class SecurityProperties {
    private final Jwt jwt = new Jwt();
    /** 生产强制 HTTPS（M2 开发期 false）。 */
    private boolean forceHttps = false;

    @Data
    public static class Jwt {
        /** HS256 密钥（≥32 字节）。yml 用 ${JWT_SECRET:dev-placeholder}，运维通过环境变量注入生产密钥。 */
        private String secret = "dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm";
        /** 有效期秒数，默认 24h。 */
        private long expireSeconds = 86400L;
        /** 签发方。 */
        private String issuer = "eaiselp-auth";
    }
}
