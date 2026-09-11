package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.service.subscription.InvoiceService;
import com.eaiselp.data.service.subscription.dto.GenerateSummaryVo;
import com.eaiselp.data.service.subscription.dto.InvoiceDetailVo;
import com.eaiselp.data.service.subscription.dto.InvoiceVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 平台账单 REST API（case-20260823-商用化 T7/T9；路径 /api/v1/invoices，契约 I1~I4，
 * api-contracts.md §3；platform_admin 专属——bill:view/bill:manage，V8 seed 1084/1085）。
 *
 * <p><b>跨租户通道（D-5）</b>：platform_admin（tenant 0 = SYSTEM）上下文拦截器全放行，
 * I1 列表天然跨租户；I3 补生成与定时任务走同一 Service 入口（幂等口径单点 D-7）。</p>
 *
 * <p><b>不限层</b>（AC-G3）零注册；<b>防伪造（约束 3）</b>：状态机无入参绑定——
 * status/issuedTime/paidTime/金额出账列均不出现在任何入参 DTO（toEntity 不存在，直传 id/target）。</p>
 */
@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    /** I1: 平台账单分页列表（跨租户；period/tenantId/status 筛选；平台侧可见 draft）。 */
    @GetMapping
    @RequirePermission("bill:view")
    public R<IPage<InvoiceVo>> page(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) String period,
                                    @RequestParam(required = false) Long tenantId,
                                    @RequestParam(required = false) String status) {
        return R.ok(invoiceService.pagePlatform(period, tenantId, status, page, size));
    }

    /** I2: 账单详情（全字段 + detail JSON 解析回显；detail 禁含 t_derivation.cost §8.4）。 */
    @GetMapping("/{id}")
    @RequirePermission("bill:view")
    public R<InvoiceDetailVo> get(@PathVariable Long id) {
        return R.ok(invoiceService.detail(id));
    }

    /** I3: 手动补生成（与定时任务同一 Service 入口；幂等 D-7——draft 覆盖/issued、paid 保护）。 */
    @PostMapping("/generate")
    @RequirePermission("bill:manage")
    public R<GenerateSummaryVo> generate(@RequestBody GenerateRequest req) {
        if (req.getPeriod() == null || req.getPeriod().isBlank()) {
            return R.fail(40000, "period 不能为空（yyyy-MM）");
        }
        return R.ok(invoiceService.generatePeriod(req.getPeriod().trim(), req.getTenantId(), operator()));
    }

    /** I4: 账单状态流转（draft→issued→paid 单向顺次；跳变/回退/paid 后流转 400，AC-F2.8）。 */
    @PostMapping("/{id}/transit")
    @RequirePermission("bill:manage")
    public R<InvoiceVo> transit(@PathVariable Long id, @RequestBody TransitRequest req) {
        if (req.getTarget() == null || req.getTarget().isBlank()) {
            return R.fail(400, "target 不能为空（issued/paid）");
        }
        return R.ok(invoiceService.transit(id, req.getTarget().trim()));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与工具
    // ------------------------------------------------------------------

    /** 补生成请求（契约 §3 I3；tenantId 缺省=全量该账期所有租户）。 */
    @Data
    public static class GenerateRequest {
        /** yyyy-MM（格式非法 40000） */
        private String period;
        private Long tenantId;
    }

    /** 流转请求（契约 §3 I4）：target ∈ {issued, paid}——无其他字段（防伪造约束 3）。 */
    @Data
    public static class TransitRequest {
        private String target;
    }

    private String operator() {
        JwtClaims claims = LoginUser.get();
        return claims != null && claims.getUsername() != null ? claims.getUsername() : "";
    }
}
