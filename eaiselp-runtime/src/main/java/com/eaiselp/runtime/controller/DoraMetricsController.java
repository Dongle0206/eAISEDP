package com.eaiselp.runtime.controller;

import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.hierarchy.DoraMetricsService;
import com.eaiselp.runtime.hierarchy.dto.DoraBoardVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DORA 效能看板 REST API（L2 层，case-20260818 T19，路径前缀 /api/v1/metrics，
 * 契约=api-contracts §1）。
 *
 * <p><b>薄控制器（任务书口径）</b>：参数三态校验（scope 非法/scope≠all 缺 scopeId/
 * periodDays 非三档 → 400）、scope 解析、四指标聚合与 5min TTL 缓存全在
 * {@link DoraMetricsService}（批A T14，D-1 实时聚合+缓存）；BizException 经
 * GlobalExceptionHandler → R.fail 业务码。</p>
 *
 * <p>三级维度：scope=project（单项目）/ program（项目群成员项目）/ all（租户全量）。
 * 权限（V5 seed 1052）{@code dora:view}（tenant_admin/project_manager/executive，
 * engineer 403）。L2 关闭 → LayerGuardInterceptor 43002（T20）。</p>
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class DoraMetricsController {

    private final DoraMetricsService doraMetricsService;

    /**
     * DORA 看板聚合（缓存 5min，D-1）：四指标卡 + 打回率参考值 + 空态文案
     * （field 语义与 AC-F1.1~F1.6 断言一一对应，示例=AC 构造值见 api-contracts §1）。
     */
    @GetMapping("/dora")
    @RequirePermission("dora:view")
    public R<DoraBoardVo> dora(@RequestParam String scope,
                               @RequestParam(required = false) Long scopeId,
                               @RequestParam(required = false) Integer periodDays) {
        return R.ok(doraMetricsService.dora(scope, scopeId, periodDays));
    }
}
