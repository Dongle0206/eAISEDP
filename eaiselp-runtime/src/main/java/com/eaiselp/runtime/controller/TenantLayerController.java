package com.eaiselp.runtime.controller;

import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.hierarchy.TenantLayerService;
import com.eaiselp.runtime.hierarchy.dto.LayerStateVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 租户分层开关 REST API（PRJ-002 T28，F10，路径前缀 /api/v1/tenant/layers）。
 *
 * <p>契约对齐 SE §8.2：</p>
 * <ul>
 *   <li>GET：登录即可（无 @RequirePermission）——菜单渲染需要（T29 登录后拉取存
 *       sessionStorage），全角色可读，只含本租户两开关布尔值、无敏感信息。</li>
 *   <li>PUT：{@code tenant:layer:edit}（V4 r3 seed 1044，仅 platform_admin/tenant_admin）。
 *       只 UPDATE 开关列本身、写后缓存主动失效（管理员改完立即可见）；关闭仅影响
 *       入口可见性（菜单隐藏 + API 业务码 43001/43002），数据全保留可逆（AC-F10.3）。</li>
 * </ul>
 *
 * <p>本端点不判分层开关（自身是开关管理入口，SE §7.3），LayerGuardInterceptor 不拦本前缀。</p>
 */
@RestController
@RequestMapping("/api/v1/tenant/layers")
@RequiredArgsConstructor
public class TenantLayerController {

    private final TenantLayerService layerService;
    private final AuditService auditService;

    /** 查询本租户开关状态（登录即可；菜单渲染数据源，T29）。 */
    @GetMapping
    public R<LayerStateVo> get() {
        Long tenantId = TenantContext.get();
        LayerStateVo vo = new LayerStateVo();
        vo.setStrategyEnabled(layerService.isStrategyEnabled(tenantId));
        vo.setProgramProjectEnabled(layerService.isProgramProjectEnabled(tenantId));
        return R.ok(vo);
    }

    /**
     * 更新分层开关（AC-F10.3 开关可逆数据保留：只改开关列，不做任何数据清理）。
     * 请求字段为 null 表示该项不变（支持单开关更新）。
     */
    @PutMapping
    @RequirePermission("tenant:layer:edit")
    public R<LayerStateVo> update(@RequestBody LayerUpdateRequest req) {
        Long tenantId = TenantContext.get();
        if (req.getStrategyEnabled() != null) {
            layerService.setLayerEnabled(tenantId, TenantLayerService.LAYER_STRATEGY, req.getStrategyEnabled());
        }
        if (req.getProgramProjectEnabled() != null) {
            layerService.setLayerEnabled(tenantId, TenantLayerService.LAYER_PROGRAM_PROJECT,
                    req.getProgramProjectEnabled());
        }
        LayerStateVo vo = new LayerStateVo();
        vo.setStrategyEnabled(layerService.isStrategyEnabled(tenantId));
        vo.setProgramProjectEnabled(layerService.isProgramProjectEnabled(tenantId));
        auditService.log("layer_update", "tenant_layer", String.valueOf(tenantId),
                "{\"strategyEnabled\":" + vo.getStrategyEnabled()
                        + ",\"programProjectEnabled\":" + vo.getProgramProjectEnabled() + "}");
        return R.ok(vo);
    }

    /** 开关更新请求（null = 该项不变） */
    @Data
    public static class LayerUpdateRequest {
        /** L3 战略层开关 */
        private Boolean strategyEnabled;
        /** L2 项目群+项目层一体开关（PRD Q8） */
        private Boolean programProjectEnabled;
    }
}
