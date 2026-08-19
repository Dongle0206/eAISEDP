package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.hierarchy.Adr;
import com.eaiselp.runtime.hierarchy.AdrService;
import com.eaiselp.runtime.hierarchy.dto.AdrVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * ADR 架构决策记录 REST API（case-20260818 T17，路径前缀 /api/v1/adrs，契约=api-contracts §4）。
 *
 * <p><b>不限层</b>（AC-SWITCH.2）：不注册 LayerGuard——任何层开关组合下恒可用
 * （原则/ADR/雷达同为租户级知识资产）。薄控制器：五段式校验/编号生成/状态机/
 * deprecateReason 审计承载（C3）/principleSyncHints 组装全在 {@link AdrService}（批A T12）。</p>
 *
 * <p>权限（V5 seed 1053~1055）：读 {@code adr:view}、建 {@code adr:create}、
 * 改/删/流转 {@code adr:edit}。写审计（adr_create/update/delete/transit）在 Service。</p>
 */
@RestController
@RequestMapping("/api/v1/adrs")
@RequiredArgsConstructor
public class AdrController {

    private final AdrService adrService;

    /** 列表筛选（AC-F4.4）：status 缺省 proposed,accepted 双值 / principleCode / keyword（title LIKE）。 */
    @GetMapping
    @RequirePermission("adr:view")
    public R<IPage<AdrVo>> page(@RequestParam(defaultValue = "1") long page,
                                @RequestParam(defaultValue = "20") long size,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String principleCode,
                                @RequestParam(required = false) String keyword) {
        return R.ok(adrService.pageFilter(status, principleCode, keyword, page, size));
    }

    /** 创建 ADR（状态固定 proposed；adrCode 缺省 ADR-NNN 服务端生成）。 */
    @PostMapping
    @RequirePermission("adr:create")
    public R<AdrVo> create(@RequestBody AdrSaveRequest req) {
        Adr created = adrService.create(toEntity(req));
        return R.ok(adrService.toVo(created));
    }

    /** 详情：全字段 + deprecateReason 审计回显（C3）+ supersededBy；跨租户 → 404。 */
    @GetMapping("/{id}")
    @RequirePermission("adr:view")
    public R<AdrVo> get(@PathVariable Long id) {
        return R.ok(adrService.detailVo(id));
    }

    /** 编辑（五段式/关联原则校验同创建；status/supersededBy 不在此改——只走 transit）。 */
    @PutMapping("/{id}")
    @RequirePermission("adr:edit")
    public R<AdrVo> update(@PathVariable Long id, @RequestBody AdrSaveRequest req) {
        Adr updated = adrService.edit(id, toEntity(req));
        return R.ok(adrService.toVo(updated));
    }

    /** 逻辑删。 */
    @DeleteMapping("/{id}")
    @RequirePermission("adr:edit")
    public R<Void> delete(@PathVariable Long id) {
        adrService.remove(id);
        return R.ok();
    }

    /**
     * 状态流转（AC-F4.2/F4.3）：superseded 必填 supersededBy（目标须 accepted 且≠自身）、
     * deprecated 必填 deprecateReason（值写审计 detail 并在响应回显，C3）；响应携
     * principleSyncHints（流转离开 accepted 且关联非空时，提示而非自动）。
     */
    @PostMapping("/{id}/transit")
    @RequirePermission("adr:edit")
    public R<AdrVo> transit(@PathVariable Long id, @RequestBody TransitRequest req) {
        if (req.getTarget() == null || req.getTarget().isBlank()) {
            return R.fail(400, "target 不能为空");
        }
        return R.ok(adrService.transit(id, req.getTarget().trim(),
                req.getSupersededBy(), req.getDeprecateReason()));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** ADR 创建/编辑请求（API 语义名，C4：context/decision/consequences ↔ V5 *_text 列）。 */
    @Data
    public static class AdrSaveRequest {
        /** 缺省服务端生成 ADR-NNN，可自定义（租户内唯一） */
        private String adrCode;
        private String title;
        private String context;
        private String decision;
        private String consequences;
        /** 关联架构原则 code 列表（逐 code 存在性校验，不存在整单 400 指名） */
        private List<String> relatedPrincipleCodes;
        /** ISO yyyy-MM-dd */
        private LocalDate decisionDate;
        private String author;
    }

    /** 状态流转请求：target 必填；supersededBy/deprecateReason 按目标态必填（Service 校验）。 */
    @Data
    public static class TransitRequest {
        /** proposed / accepted / deprecated / superseded */
        private String target;
        private String supersededBy;
        private String deprecateReason;
    }

    /** 请求 → 实体（语义名→V5 列名换名；status/supersededBy 不映射——只走 transit）。 */
    private static Adr toEntity(AdrSaveRequest req) {
        Adr adr = new Adr();
        adr.setAdrCode(req.getAdrCode());
        adr.setTitle(req.getTitle());
        adr.setContextText(req.getContext());
        adr.setDecisionText(req.getDecision());
        adr.setConsequenceText(req.getConsequences());
        adr.setRelatedPrincipleCodes(toJson(req.getRelatedPrincipleCodes()));
        adr.setDecisionDate(req.getDecisionDate());
        adr.setAuthor(req.getAuthor());
        return adr;
    }

    /** 关联原则列表 → JSON 数组 String（null/空 → null 不进 SQL；JSON 承载见 T4 实体说明）。 */
    private static String toJson(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        return codes.stream()
                .map(c -> "\"" + c.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]")
                .orElse(null);
    }
}
