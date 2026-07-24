package com.eaiselp.runtime.controller;

import com.eaiselp.common.result.R;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 权限校验测试桩（验证 AC-F3）。
 * Phase 2 真实业务接口上线后删除本类。
 */
@RestController
@RequestMapping("/api/v1/demo")
public class PermissionDemoController {

    /** 需要 tenant:view 权限。tenant_admin 有，engineer 无 → 验证 AC-F3.1/F3.2。 */
    @GetMapping("/tenant-view")
    @RequirePermission("tenant:view")
    public R<String> tenantView() {
        return R.ok("你好 " + LoginUser.get().getUsername() + "，你有 tenant:view 权限");
    }

    /** 需要 strategy:view 权限。仅 executive/platform_admin 有。 */
    @GetMapping("/strategy-view")
    @RequirePermission("strategy:view")
    public R<String> strategyView() {
        return R.ok("你有 strategy:view 权限");
    }
}
