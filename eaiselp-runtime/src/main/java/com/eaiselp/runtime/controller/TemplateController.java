package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.governance.Template;
import com.eaiselp.runtime.governance.TemplateService;
import com.eaiselp.runtime.governance.dto.TemplateVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 模板库 REST API（case-20260820 T3，路径前缀 /api/v1/templates，契约=api-contracts §2）。
 *
 * <p><b>不限层</b>（AC-SWITCH.1）：不注册 LayerGuard。薄控制器：必填/uk/原地升版校验全在
 * {@link TemplateService}。本期内仅管理，不做编排注入（PRD §7-1 范围外）。</p>
 *
 * <p>权限（V6 seed 1062~1064）：读 {@code template:view}、建 {@code template:create}、
 * 改/删/启停 {@code template:edit}。写审计（template_create/update/delete/status）在 Service。</p>
 */
@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /** 列表筛选（T1）：templateType 精确（含自定义值）/ keyword（name LIKE）/ includeDisabled 缺省 0。 */
    @GetMapping
    @RequirePermission("template:view")
    public R<IPage<TemplateVo>> page(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "20") long size,
                                     @RequestParam(required = false) String templateType,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) Integer includeDisabled) {
        return R.ok(templateService.pageFilter(templateType, keyword, includeDisabled, page, size));
    }

    /** 创建模板（T2，enabled 默认 1；type 开放字典不校验枚举，P6 裁决）。 */
    @PostMapping
    @RequirePermission("template:create")
    public R<TemplateVo> create(@RequestBody TemplateSaveRequest req) {
        Template created = templateService.create(toEntity(req));
        return R.ok(templateService.toDetailVo(created));
    }

    /** 详情（T3）：全字段 + placeholders 实时提取清单（去重排序；空合法）。 */
    @GetMapping("/{id}")
    @RequirePermission("template:view")
    public R<TemplateVo> get(@PathVariable Long id) {
        return R.ok(templateService.detailVo(id));
    }

    /** 编辑（T4，原地升版）：version 必须 ≠ 当前值（相同 400"版本必须变更"，不比较大小）。 */
    @PutMapping("/{id}")
    @RequirePermission("template:edit")
    public R<TemplateVo> update(@PathVariable Long id, @RequestBody TemplateSaveRequest req) {
        Template updated = templateService.edit(id, toEntity(req));
        return R.ok(templateService.toDetailVo(updated));
    }

    /** 逻辑删（T5）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("template:edit")
    public R<Void> delete(@PathVariable Long id) {
        templateService.remove(id);
        return R.ok();
    }

    /** 启停（T6，形态对齐 gate-rules PUT /{id}/enabled 先例）：{ "enabled": 0|1 }。 */
    @PutMapping("/{id}/enabled")
    @RequirePermission("template:edit")
    public R<TemplateVo> toggleEnabled(@PathVariable Long id, @RequestBody EnabledRequest req) {
        return R.ok(templateService.toDetailVo(templateService.toggleEnabled(id, req.getEnabled())));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 模板创建/编辑请求（契约 §2 T2；type 开放字典不校验）。 */
    @Data
    public static class TemplateSaveRequest {
        /** 开放字典：PRD/技术方案/任务清单/测试用例/部署方案 + 自定义值 */
        private String templateType;
        private String templateName;
        private String version;
        /** markdown 正文，支持 {{标识符}} 占位符 */
        private String content;
    }

    /** 启停请求（对齐 gate-rules EnabledRequest 形态；值为 0|1）。 */
    @Data
    public static class EnabledRequest {
        /** 1=启用 0=停用 */
        private Integer enabled;
    }

    /** 请求 → 实体（enabled 不映射——创建由 Service 固定 1，启停走独立端点）。 */
    private static Template toEntity(TemplateSaveRequest req) {
        Template t = new Template();
        t.setTemplateType(req.getTemplateType());
        t.setTemplateName(req.getTemplateName());
        t.setVersion(req.getVersion());
        t.setContent(req.getContent());
        return t;
    }
}
