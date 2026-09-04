package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.governance.Standard;
import com.eaiselp.runtime.governance.StandardService;
import com.eaiselp.runtime.governance.dto.StandardVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工程标准 REST API（case-20260820 T2，路径前缀 /api/v1/standards，契约=api-contracts §1）。
 *
 * <p><b>不限层</b>（AC-SWITCH.1）：不注册 LayerGuard——任何层开关组合下恒可用
 * （标准/模板/资产/规则同为租户级知识资产）。薄控制器：必填/uk/关联/编辑限制/状态机
 * 校验全在 {@link StandardService}（仅"target 不能为空"级前置判空，AdrController:83 先例）。
 * 批B 增补：S6 状态流转端点与 S1 gateName 打通筛选（§4.5 翻译口径）。</p>
 *
 * <p>权限（V6 seed 1059~1061）：读 {@code standard:view}、建 {@code standard:create}、
 * 改/删 {@code standard:edit}（transit 亦 edit）。写审计（standard_create/update/delete/
 * transit/auto_deprecate）在 Service。</p>
 */
@RestController
@RequestMapping("/api/v1/standards")
@RequiredArgsConstructor
public class StandardController {

    private final StandardService standardService;

    /**
     * 列表筛选（S1）：status 缺省 draft,published / principleCode（JSON 内存过滤）/
     * gateName（批B T13：D-9 打通查询——published 口径 + 含逻辑删行占位，供打回解析与
     * 规则页"已关联标准"复用）/ keyword（title LIKE）。
     */
    @GetMapping
    @RequirePermission("standard:view")
    public R<IPage<StandardVo>> page(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "20") long size,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String principleCode,
                                     @RequestParam(required = false) String gateName,
                                     @RequestParam(required = false) String keyword) {
        return R.ok(standardService.pageFilter(status, principleCode, gateName, keyword, page, size));
    }

    /** 创建标准（S2，状态固定 draft；standardCode 缺省 STD-NNNN 服务端生成）。 */
    @PostMapping
    @RequirePermission("standard:create")
    public R<StandardVo> create(@RequestBody StandardSaveRequest req) {
        Standard created = standardService.create(toEntity(req));
        return R.ok(standardService.toVo(created));
    }

    /** 详情（S3）：全字段 + deprecateReason 列直读回显；跨租户/不存在 → 404。 */
    @GetMapping("/{id}")
    @RequirePermission("standard:view")
    public R<StandardVo> get(@PathVariable Long id) {
        return R.ok(standardService.detailVo(id));
    }

    /** 编辑（S4，draft 专属；published/deprecated 编辑任意字段 → 400"发布后不可编辑，请升版"）。 */
    @PutMapping("/{id}")
    @RequirePermission("standard:edit")
    public R<StandardVo> update(@PathVariable Long id, @RequestBody StandardSaveRequest req) {
        Standard updated = standardService.edit(id, toEntity(req));
        return R.ok(standardService.toVo(updated));
    }

    /** 逻辑删（S5）：列表不可见+审计留痕；门禁侧"已删除"占位由查询侧实现（批B T13 D-9）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("standard:edit")
    public R<Void> delete(@PathVariable Long id) {
        standardService.remove(id);
        return R.ok();
    }

    /**
     * 状态流转（S6，批B T12，AC-F1.2/F1.4）：draft→published（触发发布自动取代事务，
     * 同编号旧 published 自动 deprecated + 双审计）/ draft/published→deprecated（必填原因）；
     * deprecated 终态、published→draft 非法 400（状态机在 StandardStatus，校验在 Service）。
     */
    @PostMapping("/{id}/transit")
    @RequirePermission("standard:edit")
    public R<StandardVo> transit(@PathVariable Long id, @RequestBody TransitRequest req) {
        if (req.getTarget() == null || req.getTarget().isBlank()) {
            return R.fail(400, "target 不能为空");
        }
        return R.ok(standardService.transit(id, req.getTarget().trim(), req.getDeprecateReason()));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 标准创建/编辑请求（契约 §1 S2；JSON 数组字段在 toEntity 转 String 承载）。 */
    @Data
    public static class StandardSaveRequest {
        /** 缺省服务端生成 STD-NNNN，可自定义（同编号同版本冲突 400） */
        private String standardCode;
        private String title;
        private String version;
        /** markdown 正文 */
        private String content;
        /** 关联架构原则 code 列表（逐 code 存在性校验，不存在整单 400 指名） */
        private List<String> relatedPrincipleCodes;
        /** 关联门禁规则 name 列表（逐 name 存在且 enabled 校验，§4.5 翻译口径） */
        private List<String> relatedGateNames;
    }

    /** 状态流转请求（S6 契约 §1）：target 必填；deprecateReason 在 target=deprecated 时必填（Service 校验 400）。 */
    @Data
    public static class TransitRequest {
        /** published / deprecated */
        private String target;
        /** 废弃/作废原因（target=deprecated 必填；发布取代时由事务自动生成不入参） */
        private String deprecateReason;
    }

    /** 请求 → 实体（status 不映射——由 Service 固定 draft；流转走批B T12 的 transit 端点）。 */
    private static Standard toEntity(StandardSaveRequest req) {
        Standard s = new Standard();
        s.setStandardCode(req.getStandardCode());
        s.setTitle(req.getTitle());
        s.setVersion(req.getVersion());
        s.setContent(req.getContent());
        s.setRelatedPrincipleCodes(toJson(req.getRelatedPrincipleCodes()));
        s.setRelatedGateNames(toJson(req.getRelatedGateNames()));
        return s;
    }

    /**
     * 关联列表 → JSON 数组 String。
     *
     * <p>S3（评审）修正：空列表返回 {@code "[]"}（parseCodes 兼容还原为空列表）——
     * PUT 全量编辑下 MP updateById 忽略 null 字段，返回 null 会让"提交空数组清空
     * 关联原则/关联门禁/标签"静默失效（回显仍为旧值）。null（字段未传）仍返回 null
     * （不更新语义保留）。同 AdrController.toJson 先例 + 空列表语义修正。</p>
     */
    static String toJson(List<String> codes) {
        if (codes == null) {
            return null;
        }
        if (codes.isEmpty()) {
            return "[]";
        }
        return codes.stream()
                .map(c -> "\"" + c.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]")
                .orElse(null);
    }
}
