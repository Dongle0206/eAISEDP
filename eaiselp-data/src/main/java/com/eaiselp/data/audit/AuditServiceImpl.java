package com.eaiselp.data.audit;

import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计日志服务实现（M3-2）。
 *
 * <p>从 {@link LoginUser}（ThreadLocal，由 JwtAuthInterceptor 注入）取 userId/username/tenantId，
 * 从 {@link RequestContextHolder} 取当前请求 IP。同步构造 {@link GovernanceLog}，
 * 委托 {@link AuditLogger}{@code @Async} 异步写入。</p>
 *
 * <p><b>必须在请求线程内调用</b>：本实现依赖 RequestContextHolder 与 LoginUser ThreadLocal，
 * 异步线程无法访问原始请求上下文（所以本类是同步读取上下文 + 异步写库的组合）。</p>
 *
 * <p><b>同步通道 {@link #logSync}</b>（case-20260824-技术债清偿 T11）：在调用方线程直接
 * INSERT（加入调用方事务），失败异常上抛——资金类强一致审计专用（bill_transit）。
 * 上下文构造逻辑与异步通道同源（{@link #buildEntry}），仅落库路径不同。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditLogger auditLogger;
    private final GovernanceLogMapper governanceLogMapper;

    @Override
    public void log(String action, String resourceType, String resourceId,
                    String detail, String result, String errorMsg) {
        try {
            // 异步写库（不阻塞主流程，失败只 log.error）
            auditLogger.write(buildEntry(action, resourceType, resourceId, detail, result, errorMsg));
        } catch (Exception e) {
            // 构造审计日志本身失败（极少）：不影响业务
            log.error("[Audit] 构造审计日志失败: action={}, resourceType={}, resourceId={}",
                    action, resourceType, resourceId, e);
        }
    }

    @Override
    public void logSync(String action, String resourceType, String resourceId,
                        String detail, String result, String errorMsg) {
        // 强一致通道（T11）：调用方线程直接 INSERT，加入调用方事务——任何失败上抛由调用方回滚。
        // 不吞异常：本方法的契约就是"审计失败 = 业务失败"。
        GovernanceLog entry = buildEntry(action, resourceType, resourceId, detail, result, errorMsg);
        governanceLogMapper.insert(entry);
    }

    /** 构造审计条目（两通道同源：LoginUser 取人/租户，RequestContextHolder 取 IP）。 */
    private GovernanceLog buildEntry(String action, String resourceType, String resourceId,
                                     String detail, String result, String errorMsg) {
        GovernanceLog entry = new GovernanceLog();
        // 上下文从 LoginUser（JWT claims）取，防客户端伪造（ES-003 §9.3 G13）
        JwtClaims claims = LoginUser.get();
        if (claims != null) {
            entry.setUserId(claims.getUserId());
            entry.setUsername(claims.getUsername());
            entry.setTenantId(claims.getTenantId() != null ? claims.getTenantId() : 0L);
        } else {
            // 未登录场景（如定时出账）：tenantId 留 0=系统级，userId/username 留 null
            entry.setTenantId(0L);
        }
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setDetail(detail);
        entry.setIpAddress(resolveClientIp());
        entry.setResult(result != null ? result : "success");
        entry.setErrorMsg(errorMsg);
        return entry;
    }

    /**
     * 从当前 HTTP 请求解析客户端 IP（支持反向代理 X-Forwarded-For 链路）。
     *
     * <p>异步线程或无请求上下文场景返回 null（AuditLogger 容忍 null ip）。
     */
    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // X-Forwarded-For 可能是链式：client, proxy1, proxy2 — 取第一个（最原始客户端）
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) return real.trim();
            return req.getRemoteAddr();
        } catch (Exception e) {
            log.debug("[Audit] 解析客户端 IP 失败（忽略）", e);
            return null;
        }
    }
}
