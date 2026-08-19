package com.eaiselp.runtime.config;

import com.eaiselp.common.ratelimit.BucketRegistry;
import com.eaiselp.common.ratelimit.RateLimitInterceptor;
import com.eaiselp.common.security.JwtAuthInterceptor;
import com.eaiselp.common.security.JwtUtil;
import com.eaiselp.data.service.PermissionService;
import com.eaiselp.runtime.hierarchy.LayerGuardInterceptor;
import com.eaiselp.runtime.hierarchy.TenantLayerService;
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
 *   <li>order=3 分层开关守卫拦截器（PRJ-002 T28，批4）：L3 关→/api/v1/strategies/**
 *       43001；L2 关→/api/v1/programs/**、/api/v1/projects/** 43002（只拦新端点，
 *       /api/v1/cases/** 与原则/门禁/编排/开关接口不受影响，AC-F10.1/10.3）。</li>
 * </ol>
 */
@Configuration
public class RuntimeWebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final PermissionService permissionService;
    private final BucketRegistry bucketRegistry;   // M2-DFX：限流桶注册表
    private final TenantLayerService tenantLayerService;   // PRJ-002 T28：分层开关读取（含本地缓存）

    @Value("${eaiselp.web.root:}")
    private String webRoot;

    public RuntimeWebMvcConfig(JwtUtil jwtUtil, PermissionService permissionService,
                               BucketRegistry bucketRegistry, TenantLayerService tenantLayerService) {
        this.jwtUtil = jwtUtil;
        this.permissionService = permissionService;
        this.bucketRegistry = bucketRegistry;
        this.tenantLayerService = tenantLayerService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 0. 限流拦截器（M2-DFX）：最先执行，挡暴力破解/烧刷；登录接口限流必须放在 JWT 前
        registry.addInterceptor(new RateLimitInterceptor(bucketRegistry))
                .addPathPatterns("/api/**")
                .order(0);
        // 1. JWT 认证拦截器：所有 /api/** 都要 token
        //    白名单：租户自助注册（#23，未登录可访问）+ OpenAPI 文档路径（Swagger/AC-SW.3：
        //    /v3/api-docs/**、/swagger-ui/**、/swagger-ui.html 不被 401 挡下，匿名可查——
        //    文档本身不含业务数据，生产防外泄由 SPRINGDOC_ENABLED=false 整体关闭兜底）
        registry.addInterceptor(new JwtAuthInterceptor(jwtUtil))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/tenant/register")
                .excludePathPatterns("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .order(1);
        // 2. 权限校验拦截器：仅对 @RequiresPermission 标注的方法生效
        registry.addInterceptor(new PermissionInterceptor(permissionService))
                .addPathPatterns("/api/**")
                .order(2);
        // 3. 分层开关守卫拦截器（PRJ-002 T28，批4 order=3 裁决：JWT/权限之后）：
        //    只拦三层贯通端点前缀，L3 关→43001 / L2 关→43002，HTTP 200 + 业务码（禁 500）。
        //    SystemManage/Runtime/Case 等存量接口不在此列（AC-F10.3 存量语义零影响）。
        //    case-20260818 T20（C6）：L2 组新增 milestones / project-dependencies / metrics
        //    三前缀（群聚合时间线挂 programs 前缀天然 43002）；adrs / tech-radar / principles
        //    不限层不注册（AC-SWITCH.2）。前缀组语义与 LayerGuardInterceptor.L2_PREFIXES 一一对应。
        registry.addInterceptor(new LayerGuardInterceptor(tenantLayerService))
                .addPathPatterns("/api/v1/strategies/**", "/api/v1/programs/**", "/api/v1/projects/**",
                        "/api/v1/milestones/**", "/api/v1/project-dependencies/**", "/api/v1/metrics/**")
                .order(3);
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
