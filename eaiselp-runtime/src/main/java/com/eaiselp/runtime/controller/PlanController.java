package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.service.subscription.SubscriptionPlanService;
import com.eaiselp.data.service.subscription.dto.PlanVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 套餐管理 REST API（case-20260823-商用化 T3；路径 /api/v1/plans，契约 P1~P4，
 * api-contracts.md §1；platform_admin 专属——权限 V8 seed 1081~1083）。
 *
 * <p><b>不限层</b>（AC-G3）：不注册 LayerGuard——商用化域任何层开关组合下完整可用。</p>
 *
 * <p><b>薄层</b>（公共验收约束 1）：参数接收 → Service → R&lt;T&gt;；除判空外不写业务校验
 * （枚举/金额域上限/features 结构/在架唯一/uk 兜底全在 SubscriptionPlanService）。
 * 金额入参一律 BigDecimal 承载（约束 2，禁 float/double）。</p>
 *
 * <p><b>防伪造链（约束 3）</b>：toEntity 不映射任何服务端字段（usageTokens/金额出账列
 * 均不在套餐域；本域的 id/enabled 由路径/显式入参承载且经 Service 校验）。</p>
 *
 * <p>无 DELETE 端点（D-14：上下架覆盖全生命周期，防镀金）。</p>
 */
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private final SubscriptionPlanService planService;

    /** P1: 套餐分页列表（code/enabled/keyword(name LIKE) 筛选，默认 id DESC；features 结构化回显）。 */
    @GetMapping
    @RequirePermission("plan:view")
    public R<IPage<PlanVo>> page(@RequestParam(defaultValue = "1") long page,
                                 @RequestParam(defaultValue = "20") long size,
                                 @RequestParam(required = false) String code,
                                 @RequestParam(required = false) Boolean enabled,
                                 @RequestParam(required = false) String keyword) {
        return R.ok(planService.pageFilter(code, enabled, keyword, page, size));
    }

    /** P3: 套餐详情（全字段；40400 不存在/跨租户——平台级表 tenant 0 上下文直查）。 */
    @GetMapping("/{id}")
    @RequirePermission("plan:view")
    public R<PlanVo> get(@PathVariable Long id) {
        return R.ok(planService.detail(id));
    }

    /** P2: 创建套餐（enabled 缺省 true；审计 plan_create 在 Service）。 */
    @PostMapping
    @RequirePermission("plan:create")
    public R<PlanVo> create(@RequestBody PlanSaveRequest req) {
        if (req.getFeatures() == null) {
            return R.fail(40000, "features 不能为空");
        }
        return R.ok(planService.create(toEntity(req), req.getFeatures()));
    }

    /** P4: 全量编辑（含 enabled 上下架；商品目录语义不回写账单/当期配额；审计 plan_update old→new）。 */
    @PutMapping("/{id}")
    @RequirePermission("plan:edit")
    public R<PlanVo> update(@PathVariable Long id, @RequestBody PlanSaveRequest req) {
        if (req.getFeatures() == null) {
            return R.fail(40000, "features 不能为空");
        }
        return R.ok(planService.update(id, toEntity(req), req.getFeatures()));
    }

    // ------------------------------------------------------------------
    // 请求 DTO（内嵌 static class，先例 TenantController/StandardController）
    // ------------------------------------------------------------------

    /** 套餐创建/编辑请求（契约 §1 P2；金额 BigDecimal 承载，features 结构化 Map 非裸 JSON 文本）。 */
    @Data
    public static class PlanSaveRequest {
        /** starter/pro/enterprise/custom；非法 400 指名（AC-F1.2） */
        private String code;
        /** ≤200；uk_plan_name 冲突 400 */
        private String name;
        /** ≥0 且 ≤ 999,999,999,999.99；=0 合法免费档（AC-F1.3） */
        private BigDecimal monthlyPrice;
        /** ≥0 且 ≤ 999,999.999999（DECIMAL(12,6)，0.335 原样存取） */
        private BigDecimal tokenOveragePrice;
        /** bronze/silver/gold；空/枚举外 400（AC-F3.1） */
        private String slaLevel;
        /** custom 必填 ∈{starter,pro,enterprise}；非 custom 必须为空（双向 400，D-9） */
        private String editionOverride;
        /** 必需键/值域结构校验在 Service（未知键忽略向前兼容） */
        private Map<String, Object> features;
        /** 缺省 true（在架） */
        private Boolean enabled;
    }

    /** 请求 → 实体（enabled 经 Service 校验归一；无服务端生成字段映射——防伪造约束）。 */
    private static Plan toEntity(PlanSaveRequest req) {
        Plan p = new Plan();
        p.setCode(req.getCode());
        p.setName(req.getName());
        p.setMonthlyPrice(req.getMonthlyPrice());
        p.setTokenOveragePrice(req.getTokenOveragePrice());
        p.setSlaLevel(req.getSlaLevel());
        p.setEditionOverride(req.getEditionOverride());
        p.setEnabled(req.getEnabled());
        return p;
    }
}
