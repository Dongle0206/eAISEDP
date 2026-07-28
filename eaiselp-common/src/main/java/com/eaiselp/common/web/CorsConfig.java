package com.eaiselp.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置（DFX 安全加固）。
 *
 * origin 白名单通过 eaiselp.cors.allowed-origins 配置（逗号分隔），
 * 开发期默认允许 localhost 各端口 + 部署机 IP。
 * 生产环境必须收紧为实际前端域名。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${eaiselp.cors.allowed-origins:http://localhost:8080,http://localhost:8081,http://localhost:8085,http://127.0.0.1:8080,http://172.16.180.166:8080,http://172.16.180.87:8080}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
