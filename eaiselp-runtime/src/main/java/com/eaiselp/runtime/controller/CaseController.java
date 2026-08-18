package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.constant.PlatformConst;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Artifact;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.service.ArtifactService;
import com.eaiselp.data.service.CaseService;
import com.eaiselp.data.service.DerivationService;
import com.eaiselp.runtime.hierarchy.ProjectProgressService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Case 管理 REST API。
 *
 * <p>提供 Case 的分页查询 / 详情 / 创建，以及 Case 维度的派生记录、产物列表聚合查询。
 * 返回风格对齐 {@link RuntimeController}：统一用 {@link R} 包装。</p>
 *
 * <p>权限：读类接口需 {@code case:read}，创建需 {@code case:create}
 * （PermissionInterceptor 按 {@link RequirePermission} 拦截校验）。</p>
 */
@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;
    private final DerivationService derivationService;
    private final ArtifactService artifactService;
    private final AuditService auditService;
    private final ProjectProgressService progressService;   // PRJ-002 T17：删除触发项目进度重算

    /** 分页查询 Case，可选按 status 过滤。 */
    @GetMapping
    @RequirePermission("case:view")
    public R<IPage<Case>> page(@RequestParam(defaultValue = "1") long page,
                               @RequestParam(defaultValue = "20") long size,
                               @RequestParam(required = false) String status) {
        Page<Case> p = new Page<>(page, size);
        return R.ok(caseService.page(p, status));
    }

    /** Case 详情。 */
    @GetMapping("/{caseId}")
    @RequirePermission("case:view")
    public R<Case> get(@PathVariable String caseId) {
        Case c = caseService.getOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Case>()
                        .eq(Case::getCaseId, caseId));
        if (c == null) return R.fail(404, "Case 不存在: " + caseId);
        return R.ok(c);
    }

    /** 创建 Case（入参 title + description；description 落 requirement 字段）。 */
    @PostMapping
    @RequirePermission("case:create")
    @Transactional(rollbackFor = Exception.class)
    public R<Case> create(@RequestBody CreateCaseRequest req) {
        if (req.getTitle() == null || req.getTitle().trim().isEmpty()) {
            return R.fail(400, "title 不能为空");
        }
        Case c = new Case();
        c.setCaseId(PlatformConst.CASE_ID_PREFIX + UUID.randomUUID());
        c.setTitle(req.getTitle().trim());
        c.setRequirement(req.getDescription());
        // schema 列默认值：layer=L1 / tier=standard / status=drafting / current_stage=stage_0。
        // 显式赋值以保证落库后返回实体一致（避免 MP null 字段被省略、回显需二次查库）。
        c.setLayer(PlatformConst.Layer.L1);
        c.setTier(PlatformConst.Tier.STANDARD);
        c.setStatus("drafting");
        c.setCurrentStage("stage_0");
        caseService.save(c);
        // 审计：Case 创建（GRC 治理：操作可追溯）
        auditService.log("case_create", "case", c.getCaseId(),
                "{\"title\":\"" + safeJson(c.getTitle()) + "\",\"layer\":\"" + c.getLayer() + "\"}");
        return R.ok(c);
    }

    /** Case 维度派生记录（按创建时间倒序）。 */
    @GetMapping("/{caseId}/derivations")
    @RequirePermission("case:view")
    public R<List<Derivation>> derivations(@PathVariable String caseId) {
        return R.ok(derivationService.listByCaseId(caseId));
    }

    /** Case 维度产物列表（按创建时间倒序）。 */
    @GetMapping("/{caseId}/artifacts")
    @RequirePermission("case:view")
    public R<List<Artifact>> artifacts(@PathVariable String caseId) {
        return R.ok(artifactService.listByCaseId(caseId));
    }

    /**
     * Case 逻辑删（PRJ-002 T17，AC-F8.3；权限 case:delete，V4 r3 seed 1045）。
     *
     * <p>已关联项目的 Case 删除时自动解除挂接（仅置空 project_id 一列，Q7 最小更新）
     * 并触发该项目进度重算（逻辑删的 Case 不计入分子分母）；Case 本体走 @TableLogic
     * 逻辑删，产物/派生记录保留可追溯。</p>
     *
     * <p>不加事务（同 SE 决策 D-2 口径）：单条 update 自动提交，异步重算提交于
     * 删除落库之后提交，读到的必然是提交后真值。</p>
     */
    @DeleteMapping("/{caseId}")
    @RequirePermission("case:delete")
    public R<Void> delete(@PathVariable String caseId) {
        Case c = caseService.getOne(new LambdaQueryWrapper<Case>().eq(Case::getCaseId, caseId));
        if (c == null) return R.fail(404, "Case 不存在: " + caseId);
        Long oldProjectId = c.getProjectId();
        if (oldProjectId != null) {
            // 自动解除项目挂接（Q7 最小更新：仅触碰 project_id 一列）
            caseService.update(new LambdaUpdateWrapper<Case>()
                    .eq(Case::getId, c.getId())
                    .set(Case::getProjectId, null));
            progressService.recalculateAsync(oldProjectId, TenantContext.get());
        }
        caseService.removeById(c.getId());
        auditService.log("case_delete", "case", c.getCaseId(),
                "{\"title\":\"" + safeJson(c.getTitle()) + "\",\"projectId\":"
                        + (oldProjectId == null ? "null" : oldProjectId) + "}");
        return R.ok();
    }

    @Data
    public static class CreateCaseRequest {
        private String title;
        private String description;
    }

    /** JSON 字符串转义（审计 detail 防注入） */
    private static String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
