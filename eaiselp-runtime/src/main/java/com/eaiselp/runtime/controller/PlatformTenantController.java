package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.service.subscription.TenantQueryService;
import com.eaiselp.data.service.subscription.dto.TenantItemVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧租户分页列表（case-20260823-商用化 编排者追加端点；GET /api/v1/tenants）。
 *
 * <p><b>鉴权</b>：按 TenantController U1/U2 先例——JWT claims roles 显式校验 platform_admin
 * （编排者裁决：不新增权限原子；TA/PM/engineer/executive → 40301）。只读端点零审计打点
 * （编排者裁决）。t_tenant 在 IGNORE_TABLES（按 id 直查先例），天然跨租户平台通道。</p>
 *
 * <p><b>数据支撑</b>：PRD F1.3/前端 tenant-list.html——行含 plan_code/planName 快照与
 * daysLeft（trial 行红标临期提示数据源，登录提示同 ceil 口径）。</p>
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class PlatformTenantController {

    private final TenantQueryService tenantQueryService;

    /** 租户分页：keyword=企业名/编码模糊；默认 id DESC。 */
    @GetMapping
    public R<IPage<TenantItemVo>> page(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long pageSize,
                                       @RequestParam(required = false) String keyword) {
        JwtClaims claims = LoginUser.get();
        if (claims == null) {
            return R.fail(ResultCode.UNAUTHORIZED, "未登录");
        }
        if (claims.getRoles() == null || !claims.getRoles().contains("platform_admin")) {
            return R.fail(ResultCode.FORBIDDEN, "仅 platform_admin 可查看平台租户列表");
        }
        return R.ok(tenantQueryService.pageTenants(keyword, page, pageSize));
    }
}
