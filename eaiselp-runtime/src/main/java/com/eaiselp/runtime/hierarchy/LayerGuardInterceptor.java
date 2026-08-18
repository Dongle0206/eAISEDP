package com.eaiselp.runtime.hierarchy;

import com.eaiselp.common.result.R;
import com.eaiselp.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 分层开关守卫拦截器（PRJ-002 T28，F10，AC-F10.1/F10.2）。
 *
 * <p><b>拦截范围（只拦新端点，存量零影响）</b>：</p>
 * <ul>
 *   <li>{@code /api/v1/strategies/**} —— L3 战略层：strategy_enabled=false → 业务码 43001；</li>
 *   <li>{@code /api/v1/programs/**}、{@code /api/v1/projects/**}（含项目维度 Case 挂接/解除
 *       子路径）—— L2 项目群+项目层（一体开关，Q8）：program_project_enabled=false → 43002；</li>
 *   <li><b>不拦</b> {@code /api/v1/cases/**}（L1 恒开）与原则/门禁/编排/租户开关接口——
 *       原则/门禁是 L1 治理配置（关 L2 的场景B 客户仍需门禁，SE §7.3）。</li>
 * </ul>
 *
 * <p><b>存量语义（AC-F10.3 开关可逆数据保留）</b>：已关联项目的 Case 走 /api/v1/cases/**
 * 不受影响（继续编排/注入/汇总）；关层只是入口拒绝 + 菜单隐藏，不做任何数据清理，
 * 重新开启后数据原样恢复。</p>
 *
 * <p><b>响应形态</b>：HTTP 200 + R.fail(43001/43002)——前端按 code 统一渲染"该层功能未启用"
 * 空态文案，禁止 500（AC-F10.1）。开关读取走 TenantLayerService 本地缓存（读 miss 回源、
 * 写后失效，管理员改完立即可见）；列缺失/读取异常降级为全开（DBA §5 兼容兜底）。</p>
 *
 * <p><b>注册顺序</b>：批4 任务书裁决 order=3（JWT=1、权限=2 之后）——已通过认证与权限的
 * 请求再判层开关；与 tasks.md T28 原案（guard 在权限拦截器之前）不同，以批4 任务书为准，
 * 语义差异：未授权用户在关层时先见 403 而非 43001（不泄露权限布局的收益让位于
 * 认证链路的稳定顺序）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class LayerGuardInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    /** L3 战略层未启用业务码（SE §7.2） */
    public static final int CODE_STRATEGY_DISABLED = 43001;

    /** L2 项目群/项目层未启用业务码（SE §7.2） */
    public static final int CODE_PROGRAM_PROJECT_DISABLED = 43002;

    private static final ObjectMapper OM = new ObjectMapper();

    private final TenantLayerService layerService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler)
            throws IOException {
        // CORS 预检直接放行（同 PermissionInterceptor 约定）
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        // 去 context-path 后按前缀路由（应用无 context-path，防御性处理）
        String uri = req.getRequestURI() == null ? "" : req.getRequestURI();
        String ctx = req.getContextPath() == null ? "" : req.getContextPath();
        if (!ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        Long tenantId = TenantContext.get();
        if (uri.startsWith("/api/v1/strategies")) {
            if (!layerService.isStrategyEnabled(tenantId)) {
                log.info("[LayerGuard] L3 战略层未启用，拦截 {} tenantId={}", uri, tenantId);
                return write(resp, CODE_STRATEGY_DISABLED, "战略层未启用");
            }
        } else if (uri.startsWith("/api/v1/programs") || uri.startsWith("/api/v1/projects")) {
            if (!layerService.isProgramProjectEnabled(tenantId)) {
                log.info("[LayerGuard] L2 项目群/项目层未启用，拦截 {} tenantId={}", uri, tenantId);
                return write(resp, CODE_PROGRAM_PROJECT_DISABLED, "项目群/项目层未启用");
            }
        }
        return true;
    }

    /** HTTP 200 + 业务码 JSON（AC-F10.1：禁止 500，前端按 code 渲染空态） */
    private boolean write(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(OM.writeValueAsString(R.fail(code, msg)));
        return false;
    }
}
