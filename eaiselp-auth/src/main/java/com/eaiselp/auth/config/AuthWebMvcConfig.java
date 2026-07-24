package com.eaiselp.auth.config;

import com.eaiselp.common.security.JwtAuthInterceptor;
import com.eaiselp.common.security.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;

    public AuthWebMvcConfig(JwtUtil jwtUtil) { this.jwtUtil = jwtUtil; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthInterceptor(jwtUtil))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/login");   // 仅 login 白名单
    }
}
