package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.governance.Risk;
import com.eaiselp.runtime.governance.RiskService;
import com.eaiselp.runtime.governance.dto.RiskDashboardVo;
import com.eaiselp.runtime.governance.dto.RiskVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 风险登记册 REST API（case-20260821 T2/T10/T12，路径前缀 /api/v1/risks，契约=api-contracts §1）。
 *
 * <p><b>不限层</b>（AC-SWITCH.1）：不注册 LayerGuard——任何层开关组合下恒可用。
 * 薄控制器：必填/枚举/P·I 边界/关联/状态机校验全在 {@link RiskService}（仅"target
 * 不能为空"级前置判空，StandardController 先例）。</p>
 *
 * <p>权限（V7 seed 1071~1073）：读 {@code risk:view}（R1/R3/R7）、建 {@code risk:create}
 * （R2）、改/删/流转 {@code risk:edit}（R4/R5/R6）。写审计（risk_create/update/delete/
 * transit）在 Service。</p>
 *
 * <p><b>计算字段防伪造</b>（AC-F1.5）：riskValue/riskLevel 不在入参模型——提交即被
 * 反序列化丢弃，服务端重算覆盖。数值入参一律 BigDecimal 承载（D-4：1.5 可到达 Service
 * 被 400 指名拒绝，不得 50000）。</p>
 *
 * <p><b>relatedObjects.id 统一 String</b>（编排者契约对齐裁决，2026-09-04）：
 * program/project=数字主键的字符串形式（提交数字或字符串等价，Jackson 自动强转）；
 * <b>case=平台对外业务键 caseId 字符串</b>（t_case.case_id VARCHAR，如 "case-xxx"），
 * 与既有 API 惯例一致（Case 对外标识非 BIGINT 主键）。存 JSON 自由结构，出参同口径。</p>
 */
@RestController
@RequestMapping("/api/v1/risks")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;

    /**
     * 列表（R1）：默认排序 riskValue DESC, id DESC（写死，QA 断言用）；筛选
     * category/level/status/overdueOnly（SQL 口径 review_date&lt;今天 AND status≠closed）/
     * keyword（riskName LIKE）；VO 含服务端判定 overdue（D-10）。
     */
    @GetMapping
    @RequirePermission("risk:view")
    public R<IPage<RiskVo>> page(@RequestParam(defaultValue = "1") long page,
                                 @RequestParam(defaultValue = "20") long size,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(required = false) String level,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Boolean overdueOnly,
                                 @RequestParam(required = false) String keyword) {
        return R.ok(riskService.pageFilter(category, level, status, overdueOnly, keyword, page, size));
    }

    /** 创建风险（R2，status 固定 open；riskValue/riskLevel 服务端算——入参模型无此二字段）。 */
    @PostMapping
    @RequirePermission("risk:create")
    public R<RiskVo> create(@RequestBody RiskSaveRequest req) {
        Risk created = riskService.create(toEntity(req));
        return R.ok(riskService.toVo(created));
    }

    /** 详情（R3）：全字段 + relatedObjects 解析 [{type,id,name,deleted}]（悬空占位，AC-F1.6）。 */
    @GetMapping("/{id}")
    @RequirePermission("risk:view")
    public R<RiskVo> get(@PathVariable Long id) {
        return R.ok(riskService.detailVo(id));
    }

    /** 编辑（R4）：open/mitigating 全字段可编辑且编辑即重算；closed 编辑任意字段 → 400 终态只读。 */
    @PutMapping("/{id}")
    @RequirePermission("risk:edit")
    public R<RiskVo> update(@PathVariable Long id, @RequestBody RiskSaveRequest req) {
        Risk updated = riskService.edit(id, toEntity(req));
        return R.ok(riskService.toVo(updated));
    }

    /** 逻辑删（R5）+ 审计 risk_delete；删除后列表不可见。 */
    @DeleteMapping("/{id}")
    @RequirePermission("risk:edit")
    public R<Void> delete(@PathVariable Long id) {
        riskService.remove(id);
        return R.ok();
    }

    /**
     * 状态流转（R6，AC-F1.4 + §0.3-1 消解）：open→mitigating；mitigating→closed（必填
     * resolutionNote）；mitigating→open 回退合法；open→closed 跳级 400；closed 出边 400；
     * 自流转幂等。
     */
    @PostMapping("/{id}/transit")
    @RequirePermission("risk:edit")
    public R<RiskVo> transit(@PathVariable Long id, @RequestBody TransitRequest req) {
        if (req.getTarget() == null || req.getTarget().isBlank()) {
            return R.fail(400, "target 不能为空");
        }
        return R.ok(riskService.transit(id, req.getTarget().trim(), req.getResolutionNote()));
    }

    /**
     * 风险看板聚合（R7，纯只读，AC-F1.12~F1.15）：cells 恰 25 格（仅未 closed 计入）+
     * 等级分布四档 + 高风险清单（level∈{high,critical} 未 closed，riskValue DESC, id DESC）。
     * 无任何写语义端点映射（写请求 405/404 天然）。
     */
    @GetMapping("/dashboard")
    @RequirePermission("risk:view")
    public R<RiskDashboardVo> dashboard() {
        return R.ok(riskService.dashboard());
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 风险创建/编辑请求（契约 §1 R2；数值字段 BigDecimal 承载——D-4）。 */
    @Data
    public static class RiskSaveRequest {
        /** 风险名（必填 ≤200，uk 冲突 400） */
        private String riskName;
        /** strategy/compliance/operations/technical/security（非法 400 指名） */
        private String category;
        /** 概率 1~5 整数（BigDecimal 承载：0/6/1.5/负数 → 400 指名，AC-F1.3） */
        private BigDecimal probability;
        /** 影响 1~5（同上） */
        private BigDecimal impact;
        /** 缓解措施 */
        private String description;
        private String mitigation;
        /** 应急预案 */
        private String contingencyPlan;
        /** 风险责任人（必填，AC-F1.1） */
        private String owner;
        /** 关联对象（可空多选；id 语义见类 Javadoc：program/project=数字字符串、case=caseId） */
        private List<RelatedObjectItem> relatedObjects;
        /** 复评日期（可空，yyyy-MM-dd） */
        private LocalDate reviewDate;
    }

    /**
     * 关联对象条目（{type, id}）：type∈program/project/case；id 统一 String——
     * program/project 为数字 id（数字或字符串提交等价），case 为 caseId 业务键字符串。
     */
    @Data
    public static class RelatedObjectItem {
        /** program / project / case */
        private String type;
        /** 对象标识（String，语义见上） */
        private String id;
    }

    /** 状态流转请求（R6 契约）：target 必填；resolutionNote 在 target=closed 时必填（Service 校验 400）。 */
    @Data
    public static class TransitRequest {
        /** mitigating / closed / open（回退合法） */
        private String target;
        /** 处置说明（target=closed 必填） */
        private String resolutionNote;
    }

    /**
     * 请求 → 实体。status/riskValue/riskLevel 不映射（服务端固定/重算——防伪造链，
     * AC-F1.5）；resolutionNote 不映射（仅 transit 端点承载）。
     */
    private static Risk toEntity(RiskSaveRequest req) {
        Risk r = new Risk();
        r.setRiskName(req.getRiskName());
        r.setCategory(req.getCategory());
        r.setProbability(req.getProbability());
        r.setImpact(req.getImpact());
        r.setDescription(req.getDescription());
        r.setMitigation(req.getMitigation());
        r.setContingencyPlan(req.getContingencyPlan());
        r.setOwner(req.getOwner());
        r.setRelatedObjects(toJsonRelated(req.getRelatedObjects()));
        r.setReviewDate(req.getReviewDate());
        return r;
    }

    /**
     * 关联列表 → JSON 数组 String（[{"type":"program","id":"123"}]，id 统一字符串形式）。
     *
     * <p>空列表返回 {@code "[]"}（parseRelatedObjects 兼容还原为空列表）——PUT 全量编辑下
     * 空数组必须可落库清空关联（null 保留"不更新"语义），同 StandardController.toJson
     * 空列表语义修正先例。</p>
     */
    static String toJsonRelated(List<RelatedObjectItem> items) {
        if (items == null) {
            return null;
        }
        if (items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (RelatedObjectItem item : items) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            String type = item.getType() == null ? "" : item.getType()
                    .replace("\\", "\\\\").replace("\"", "\\\"");
            String id = item.getId() == null ? "" : item.getId()
                    .replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("{\"type\":\"").append(type).append("\",\"id\":\"").append(id).append("\"}");
        }
        return sb.append(']').toString();
    }
}
