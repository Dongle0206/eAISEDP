package com.eaiselp.common.ratelimit;

import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.tenant.TenantContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 限流拦截器（M2-DFX，SE 技术方案 §4.2.4）。
 *
 * <p>读取 Controller 方法上的 {@link RateLimit} 注解，按 {@link RateLimit.KeyType} 解析 Key，
 * 经 {@link BucketRegistry} 取桶并 {@code tryConsume(1)}；失败抛 {@link RateLimitedException}
 * （GlobalExceptionHandler 转 429 + Retry-After）。
 *
 * <p>Key 解析策略（SE §4.2.4，防绕过）：
 * <ul>
 *   <li>{@code IP}：优先 X-Forwarded-For 首段（反向代理场景），回退 remoteAddr。
 *       <b>注意 X-Forwarded-For 可伪造</b>，M2 dogfooding 直连可接受，M3 上网关后须由网关覆写。
 *       （此点已记 SE §11 风险表，Dev 不在本期收敛。）</li>
 *   <li>{@code TENANT}：从 {@link TenantContext}（JwtAuthInterceptor 已从 JWT claims 注入）取 tenantId。</li>
 *   <li>{@code USER}：从 {@link LoginUser}（JWT claims）取 userId。</li>
 * </ul>
 *
 * <p>登录接口特殊性：登录时用户尚未认证、无 JWT，故登录限流只能用 IP（SE §4.2.4 明示）。
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final BucketRegistry registry;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        // 非 Controller 方法（静态资源等）直接放行
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RateLimit rl = hm.getMethodAnnotation(RateLimit.class);
        if (rl == null) {
            return true;
        }

        String key = resolveKey(rl.key(), req);
        String bucketId = rl.name() + ":" + rl.key().name() + ":" + key;
        Bucket bucket = registry.getOrCreate(bucketId, rl.capacity(), rl.refillPerMin());

        if (bucket.tryConsume(1)) {
            return true;   // 通过
        }
        // 超限：抛异常交 GlobalExceptionHandler 转 429 + Retry-After
        log.warn("[RateLimit] 超限桶={}, key={}, msg={}", rl.name(), key, rl.message());
        throw new RateLimitedException(rl.message(), rl.retryAfterSeconds());
    }

    /**
     * 解析限流 Key。
     *
     * <p>注意：登录接口无 JWT（LoginUser/TenantContext 为空），必须用 IP 维度。
     * 此处对 TENANT/USER 在上下文缺失时回退到 IP，保证不 NPE 且仍有限流（SE §4.2.4 兜底）。
     */
    private String resolveKey(RateLimit.KeyType keyType, HttpServletRequest req) {
        switch (keyType) {
            case IP:
                return resolveIp(req);
            case TENANT: {
                Long tid = TenantContext.get();
                return tid != null ? String.valueOf(tid) : resolveIp(req);   // 未登录兜底 IP
            }
            case USER: {
                Long uid = LoginUser.getUserId();
                return uid != null ? String.valueOf(uid) : resolveIp(req);   // 未登录兜底 IP
            }
            default:
                return resolveIp(req);
        }
    }

    /**
     * 解析客户端 IP。
     *
     * <p>优先取 X-Forwarded-For 首段（多级代理取最原始客户端），
     * 缺失则回退 {@link HttpServletRequest#getRemoteAddr()}。
     *
     * <p><b>已知风险（SE §11）</b>：X-Forwarded-For 可被客户端伪造。
     * M2 dogfooding 直连无反向代理，X-Forwarded-For 一般不存在 → 走 remoteAddr；
     * M3 上网关后须由网关覆写 X-Forwarded-For，应用只信任网关注入的第一跳。
     */
    private String resolveIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 多级代理："client, proxy1, proxy2" → 取首段
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = req.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }
}
