package com.eaiselp.runtime.config;

import com.eaiselp.common.ratelimit.BucketRegistry;
import com.eaiselp.common.ratelimit.RateLimitInterceptor;
import com.eaiselp.common.security.JwtAuthInterceptor;
import com.eaiselp.common.security.JwtUtil;
import com.eaiselp.data.service.PermissionService;
import com.eaiselp.runtime.security.PermissionInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * runtime 配置：限流 + JWT + 权限 三拦截器 + 前端静态资源托管。
 * 静态资源通过 eaiselp.web.root 外部目录配置，指向独立前端仓 clone 目录。
 *
 * <p><b>拦截器顺序（M2-DFX，SE §4.2.4）</b>：
 * <ol>
 *   <li>order=0 限流拦截器：最先执行。原因——登录接口限流只能用 IP 维度（登录时无 JWT），
 *       若限流排在 JWT 之后，未带 token 的请求会先被 JWT 拦 401，限流桶形同虚设。
 *       限流放最前能挡住暴力破解/烧刷，无论是否登录。</li>
 *   <li>order=1 JWT 认证拦截器：所有 /api/** 都要 token（runtime 无公开接口）。</li>
 *   <li>order=2 权限校验拦截器：仅对 @RequiresPermission 标注的方法生效。</li>
 * </ol>
 */
@Configuration
public class RuntimeWebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final PermissionService permissionService;
    private final BucketRegistry bucketRegistry;   // M2-DFX：限流桶注册表

    @Value("${eaiselp.web.root:}")
    private String webRoot;

    public RuntimeWebMvcConfig(JwtUtil jwtUtil, PermissionService permissionService,
                               BucketRegistry bucketRegistry) {
        this.jwtUtil = jwtUtil;
        this.permissionService = permissionService;
        this.bucketRegistry = bucketRegistry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 0. 限流拦截器（M2-DFX）：最先执行，挡暴力破解/烧刷；登录接口限流必须放在 JWT 前
        registry.addInterceptor(new RateLimitInterceptor(bucketRegistry))
                .addPathPatterns("/api/**")
                .order(0);
        // 1. JWT 认证拦截器：所有 /api/** 都要 token（runtime 无公开接口）
        registry.addInterceptor(new JwtAuthInterceptor(jwtUtil))
                .addPathPatterns("/api/**")
                .order(1);
        // 2. 权限校验拦截器：仅对 @RequiresPermission 标注的方法生效
        registry.addInterceptor(new PermissionInterceptor(permissionService))
                .addPathPatterns("/api/**")
                .order(2);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 托管前端静态文件（外部目录），消除跨域问题
        // 配置：eaiselp.web.root=D:/eaiselp/web（指向前端 clone 目录）
        // 注意：只匹配非 API 路径，避免拦截 /api/** Controller 请求
        if (webRoot != null && !webRoot.isBlank()) {
            String location = webRoot.replace("\\", "/");
            if (!location.endsWith("/")) location = location + "/";
            registry.addResourceHandler("/*.html", "/pages/**", "/assets/**", "/css/**", "/js/**")
                    .addResourceLocations("file:" + location)
                    .resourceChain(true);
        }
    }
}
