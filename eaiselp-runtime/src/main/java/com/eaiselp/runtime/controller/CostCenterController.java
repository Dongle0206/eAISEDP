package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.service.subscription.CostCenterService;
import com.eaiselp.data.service.subscription.InvoiceService;
import com.eaiselp.data.service.subscription.dto.CostCenterVo;
import com.eaiselp.data.service.subscription.dto.InvoiceDetailVo;
import com.eaiselp.data.service.subscription.dto.InvoiceVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 租户费用中心 REST API（case-20260823-商用化 T10；路径 /api/v1/cost-center，契约 C1~C3，
 * api-contracts.md §4；cost:view = platform_admin + tenant_admin——engineer 403、
 * <b>executive 403（裁决 Q5：费用是管理层信息）</b>，V8 seed 1086）。
 *
 * <p><b>不限层</b>（AC-G3）零注册。<b>tenant_id 只从 TenantContext 取</b>（G13，防伪造）。
 * <b>draft 隔离双语义</b>：C2 强制 status IN (issued, paid)；C3 draft/他租户 → 404
 * （拦截器隔离之外的第二道语义过滤，AC-F2.9/QA 标注 6）。信息隔离（§8.4）：出参任何字段
 * 禁含 t_derivation.cost。</p>
 */
@RestController
@RequestMapping("/api/v1/cost-center")
@RequiredArgsConstructor
public class CostCenterController {

    private final CostCenterService costCenterService;
    private final InvoiceService invoiceService;

    /** C1: 本期用量与当前套餐（四维实时水位仅展示非账单口径 + 套餐卡/SLA 档透传）。 */
    @GetMapping("/summary")
    @RequirePermission("cost:view")
    public R<CostCenterVo> summary() {
        JwtClaims claims = LoginUser.get();
        if (claims == null || claims.getTenantId() == null) {
            return R.fail(ResultCode.UNAUTHORIZED, "未登录");
        }
        return R.ok(costCenterService.summary(claims.getTenantId()));
    }

    /** C2: 本租户历史账单（仅 issued/paid——draft 对租户不可见，AC-F2.9）。 */
    @GetMapping("/invoices")
    @RequirePermission("cost:view")
    public R<IPage<InvoiceVo>> invoices(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size) {
        return R.ok(invoiceService.pageForTenant(currentTenantId(), page, size));
    }

    /** C3: 本租户账单详情 + 超量明细展开（draft 或他租户 → 404，AC-F2.9/F2.10）。 */
    @GetMapping("/invoices/{id}")
    @RequirePermission("cost:view")
    public R<InvoiceDetailVo> invoiceDetail(@PathVariable Long id) {
        return R.ok(invoiceService.detailForTenant(currentTenantId(), id));
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.get();
        JwtClaims claims = LoginUser.get();
        // platform_admin（tenant 0）无本租户账单语义时自然查空（显式传参，G13）
        return claims != null && claims.getTenantId() != null ? claims.getTenantId() : tenantId;
    }
}
