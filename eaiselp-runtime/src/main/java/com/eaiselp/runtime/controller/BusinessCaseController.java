package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.governance.BusinessCase;
import com.eaiselp.runtime.governance.BusinessCaseService;
import com.eaiselp.runtime.governance.dto.BusinessCaseVo;
import com.eaiselp.runtime.governance.dto.PortfolioVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商业案例 REST API（case-20260821 T4/T11/T13，路径前缀 /api/v1/business-cases，
 * 契约=api-contracts §3）。
 *
 * <p><b>不限层</b>（AC-SWITCH.1）：不注册 LayerGuard。薄控制器：必填/金额/因子/confidence
 * 步进/战略存在性/状态机校验全在 {@link BusinessCaseService}（仅"target/decisionNote
 * 不能为空"级前置判空）。</p>
 *
 * <p>权限（V7 seed 1077~1080）：读 {@code bizcase:view}（B1/B3/B8）、建
 * {@code bizcase:create}（B2）、改/删/决策记录 {@code bizcase:edit}（B4/B5/B6）、
 * <b>流转 {@code bizcase:approve}</b>（B7 独立原子——四类流转目标统一挂 approve，
 * §0.3-2 消解：创建与审批分离，PM 无该原子 → 403，AC-F2.9）。</p>
 *
 * <p><b>计算字段防伪造</b>（AC-F2.4）：netBenefit/paybackYears/roiPercent/riceScore
 * 不在入参模型——提交 rice_score=999 连绑定入口都没有，服务端重算覆盖。数值入参一律
 * BigDecimal 承载（D-4）。字段名 roiPercent 来自 V7 列 roi_percent（差异定稿）。</p>
 */
@RestController
@RequestMapping("/api/v1/business-cases")
@RequiredArgsConstructor
public class BusinessCaseController {

    private final BusinessCaseService businessCaseService;

    /**
     * 列表（B1）：默认排序 riceScore DESC, id DESC（写死，QA 断言用）；筛选 status/
     * strategyId（related_strategy_ids JSON 内存过滤，分页后过滤——战略反向展示复用，D-8）/
     * keyword（caseName LIKE）。
     */
    @GetMapping
    @RequirePermission("bizcase:view")
    public R<IPage<BusinessCaseVo>> page(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Long strategyId,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(businessCaseService.pageFilter(status, strategyId, keyword, page, size));
    }

    /** 创建案例（B2，status 固定 draft；四计算列服务端算——入参模型无此四字段）。 */
    @PostMapping
    @RequirePermission("bizcase:create")
    public R<BusinessCaseVo> create(@RequestBody CaseSaveRequest req) {
        BusinessCase created = businessCaseService.create(toEntity(req));
        return R.ok(businessCaseService.toVo(created));
    }

    /** 详情（B3）：全字段 + relatedStrategies 解析 [{id,title,deleted}]（战略逻辑删占位，AC-F2.8）。 */
    @GetMapping("/{id}")
    @RequirePermission("bizcase:view")
    public R<BusinessCaseVo> get(@PathVariable Long id) {
        return R.ok(businessCaseService.detailVo(id));
    }

    /** 编辑（B4，draft 专属）：draft → 200 且重算；approved/executing → 400 输入不可改；终态 → 400。 */
    @PutMapping("/{id}")
    @RequirePermission("bizcase:edit")
    public R<BusinessCaseVo> update(@PathVariable Long id, @RequestBody CaseSaveRequest req) {
        BusinessCase updated = businessCaseService.edit(id, toEntity(req));
        return R.ok(businessCaseService.toVo(updated));
    }

    /** 逻辑删（B5）：仅 draft 可删；非 draft → 400（合规资产留痕，AC-F2.7）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("bizcase:edit")
    public R<Void> delete(@PathVariable Long id) {
        businessCaseService.remove(id);
        return R.ok();
    }

    /**
     * 更新决策记录（B6，T11）：draft/approved/executing 可更新（执行期进展记录）；
     * rejected/done → 400；审计 detail 含旧值→新值（AC-AUDIT.1）。
     */
    @PutMapping("/{id}/decision-note")
    @RequirePermission("bizcase:edit")
    public R<BusinessCaseVo> updateDecisionNote(@PathVariable Long id,
                                                @RequestBody DecisionNoteRequest req) {
        if (req.getDecisionNote() == null || req.getDecisionNote().isBlank()) {
            return R.fail(400, "decisionNote 不能为空");
        }
        return R.ok(businessCaseService.updateDecisionNote(id, req.getDecisionNote()));
    }

    /**
     * 状态流转（B7，AC-F2.6 + §0.3-2 消解）：draft→approved / draft→rejected（必填
     * rejectedReason）/ approved→executing / executing→done；跳级/批准后撤销/终态出边 400；
     * 自流转幂等。<b>整体挂 bizcase:approve</b>（PM 全 403，AC-F2.9）。
     */
    @PostMapping("/{id}/transit")
    @RequirePermission("bizcase:approve")
    public R<BusinessCaseVo> transit(@PathVariable Long id, @RequestBody TransitRequest req) {
        if (req.getTarget() == null || req.getTarget().isBlank()) {
            return R.fail(400, "target 不能为空");
        }
        return R.ok(businessCaseService.transit(id, req.getTarget().trim(), req.getRejectedReason()));
    }

    /**
     * 投资组合聚合（B8，纯只读，AC-F2.10~F2.12）：cases 全量（含 rejected/done，
     * riceScore DESC, id DESC）+ summary 四项投资口径（仅 approved/executing/done）+
     * statusDistribution 全量五态（双口径并存）。无写语义端点映射。
     */
    @GetMapping("/portfolio")
    @RequirePermission("bizcase:view")
    public R<PortfolioVo> portfolio(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size) {
        return R.ok(businessCaseService.portfolio(page, size));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 案例创建/编辑请求（契约 §3 B2；数值字段 BigDecimal 承载——D-4）。 */
    @Data
    public static class CaseSaveRequest {
        /** 案例名（必填 ≤200，uk 冲突 400） */
        private String caseName;
        /** 案例描述 */
        private String description;
        /** 关联战略 id 列表（可空多选；逐 id 存在性校验，无效 400 指名，AC-F2.8） */
        private List<Long> relatedStrategyIds;
        /** 一次性成本（元，≥0；=0 合法触发边界态，AC-F2.5） */
        private BigDecimal onetimeCost;
        /** 年运营成本（元，≥0） */
        private BigDecimal annualOpCost;
        /** 量化收益/年（元，≥0） */
        private BigDecimal annualBenefit;
        /** RICE 触达 1~10 整数（0/11/1.5 → 400 指名，AC-F2.4） */
        private BigDecimal reach;
        /** RICE 影响 1~10（同上） */
        private BigDecimal impact;
        /** RICE 信心（0.1 步进离散恰 10 档 0.1~1.0；0.05/0.15/0.85 → 400） */
        private BigDecimal confidence;
        /** RICE 投入 1~10（同 reach） */
        private BigDecimal effort;
    }

    /** 决策记录更新请求（B6 契约）：decisionNote 必填（空 → 400）。 */
    @Data
    public static class DecisionNoteRequest {
        private String decisionNote;
    }

    /** 状态流转请求（B7 契约）：target 必填；rejectedReason 在 target=rejected 时必填（Service 校验 400）。 */
    @Data
    public static class TransitRequest {
        /** approved / rejected / executing / done */
        private String target;
        /** 拒绝原因（target=rejected 必填，AC-F2.6） */
        private String rejectedReason;
    }

    /**
     * 请求 → 实体。status/rejectedReason/decisionNote 不映射（流转/B6 端点专属）；
     * 四计算列不在入参模型（防伪造链，AC-F2.4）。
     */
    private static BusinessCase toEntity(CaseSaveRequest req) {
        BusinessCase c = new BusinessCase();
        c.setCaseName(req.getCaseName());
        c.setDescription(req.getDescription());
        c.setRelatedStrategyIds(toJsonIds(req.getRelatedStrategyIds()));
        c.setOnetimeCost(req.getOnetimeCost());
        c.setAnnualOpCost(req.getAnnualOpCost());
        c.setAnnualBenefit(req.getAnnualBenefit());
        c.setReach(req.getReach());
        c.setImpact(req.getImpact());
        c.setConfidence(req.getConfidence());
        c.setEffort(req.getEffort());
        return c;
    }

    /**
     * 战略 id 列表 → JSON 数组 String（[1,5]）。
     *
     * <p>空列表返回 {@code "[]"}（parseStrategyIds 兼容还原）——PUT 全量编辑下空数组
     * 必须可落库清空关联（null 保留"不更新"语义），同 StandardController.toJson 先例。</p>
     */
    static String toJsonIds(List<Long> ids) {
        if (ids == null) {
            return null;
        }
        if (ids.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (Long id : ids) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.append(']').toString();
    }
}
