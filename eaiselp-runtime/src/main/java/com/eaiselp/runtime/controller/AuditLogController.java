package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.ratelimit.RateLimit;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.service.GovernanceLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审计日志查询 REST API（Wave4 审计日志查询 API）。
 *
 * <p>提供审计日志的分页查询与按用户查询，供管理后台「操作审计」/「用户行为」页面使用。
 * 返回风格对齐 {@link CaseController}：统一用 {@link R} 包装。
 *
 * <p><b>权限</b>：所有接口需 {@code audit:read}（PermissionInterceptor 按 {@link com.eaiselp.common.security.RequirePermission}
 * 拦截校验）——审计日志含敏感操作痕迹，仅审计/管理员角色可读。
 *
 * <p><b>多租户隔离（关键，ES-003 §9.3 G13）</b>：t_governance_log 在
 * {@code EaiselpTenantHandler.IGNORE_TABLES} 中，MyBatis-Plus 租户拦截器<b>不会</b>自动注入
 * tenant_id 条件。本 Controller 从 {@link LoginUser#get()}（JWT claims，由 JwtAuthInterceptor 注入）
 * 取 tenantId 后<b>显式</b>传入 {@link GovernanceLogService#page}，杜绝客户端伪造 tenantId 越权
 * 读取他租户审计日志。LoginUser.set 已同步注入 TenantContext，二者一致。
 *
 * <p><b>限流</b>：分页查询 100 次/分/用户（防审计页拉满打爆 DB）。
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final GovernanceLogService governanceLogService;

    /**
     * 分页查询审计日志（按当前租户隔离）。
     *
     * <p>tenantId 从 LoginUser 取（防客户端伪造），action 可选过滤。
     *
     * @param page   页码（默认 1）
     * @param size   每页条数（默认 20）
     * @param action 操作动作过滤（可选，如 case_create / login_success）
     */
    @GetMapping
    @com.eaiselp.common.security.RequirePermission("audit:read")
    @RateLimit(name = "audit-page", key = RateLimit.KeyType.USER,
            capacity = 100, refillPerMin = 100,
            message = "审计日志查询过于频繁，请稍后再试")
    public R<IPage<GovernanceLog>> page(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) String action) {
        // 显式从 LoginUser 取 tenantId（IGNORE_TABLES 不走拦截器，必须显式传，防跨租户越权）
        long tenantId = resolveTenantId();
        return R.ok(governanceLogService.page(tenantId, action, page, size));
    }

    /**
     * 按用户查审计日志（最近 N 条）。
     *
     * <p>用于「某用户的操作历史」场景。userId 由路径传入，调用方应确保当前登录用户有
     * {@code audit:read} 权限（PermissionInterceptor 把关）。
     *
     * @param userId 用户 ID
     * @param limit  返回条数上限（默认 50，最大 100，防大结果集）
     */
    @GetMapping("/users/{userId}")
    @com.eaiselp.common.security.RequirePermission("audit:read")
    @RateLimit(name = "audit-user", key = RateLimit.KeyType.USER,
            capacity = 100, refillPerMin = 100,
            message = "审计日志查询过于频繁，请稍后再试")
    public R<List<GovernanceLog>> listByUserId(@PathVariable Long userId,
                                               @RequestParam(defaultValue = "50") int limit) {
        // limit 上限兜底：防客户端传超大值拖慢 DB
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return R.ok(governanceLogService.listByUserId(userId, safeLimit));
    }

    /**
     * 解析当前登录用户租户 ID。
     *
     * <p>优先从 LoginUser（JWT claims）取；LoginUser 未注入（理论上 JwtAuthInterceptor 已拦截）
     * 时回落 TenantContext，仍为空则 0=系统级（兜底，不应发生于受保护接口）。
     */
    private long resolveTenantId() {
        if (LoginUser.get() != null && LoginUser.get().getTenantId() != null) {
            return LoginUser.get().getTenantId();
        }
        return TenantContext.get();
    }
}
