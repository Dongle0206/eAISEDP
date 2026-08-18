package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.service.CaseService;
import com.eaiselp.runtime.hierarchy.ProgramService;
import com.eaiselp.runtime.hierarchy.Project;
import com.eaiselp.runtime.hierarchy.ProjectProgressService;
import com.eaiselp.runtime.hierarchy.ProjectService;
import com.eaiselp.runtime.hierarchy.dto.ProjectDetailVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 项目管理 REST API（L2 层，PRJ-002 T25 + 批4 Case 挂接端点，路径前缀 /api/v1/projects）。
 *
 * <p>契约对齐 SE §8.1/§8.2：CRUD + 按项目群过滤 + 详情聚合（含已绑定原则清单）。
 * 进度三列（progress/caseTotal/caseDone）为系统汇总字段，创建强制 0/0/0、编辑一律忽略
 * （AC-F3.2 双保险之二；唯一合法写入方是 ProjectProgressService 全量重算）。</p>
 *
 * <p>Case 挂接/解除（T17，Q7 最小更新裁决：仅触碰 t_case.project_id 一列，不改 Case 其他字段）：
 * <ul>
 *   <li>POST /{id}/cases/{caseId}：挂接（换挂时新旧项目都重算，AC-F4.2）</li>
 *   <li>DELETE /{id}/cases/{caseId}：解除（任意状态含 done 可解，AC-F8.2 进度回落；全量重算幂等）</li>
 * </ul>
 * 挂接前 projectMapper 走租户拦截器（跨租户 null → 404 不泄露存在性，AC-ISO.2）。</p>
 *
 * <p>权限：读 {@code project:view}、建 {@code project:create}、改/删/挂接 {@code project:edit}。
 * 写操作全审计（project_create/update/delete、case_link/unlink）。</p>
 *
 * <p>L2 关闭时整前缀（含 Case 挂接/解除端点）被 LayerGuardInterceptor 以业务码 43002 拒绝；
 * 已关联项目的存量 Case 走 /api/v1/cases/** 不受影响（AC-F10.3 存量语义）。</p>
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    /** 项目合法状态集 */
    private static final Set<String> STATUSES = Set.of("planning", "in_progress", "delivered", "closed");

    private final ProjectService projectService;
    private final ProgramService programService;
    private final CaseService caseService;
    private final ProjectProgressService progressService;
    private final AuditService auditService;

    /** 分页查询项目，可按项目群（programId）与状态过滤。 */
    @GetMapping
    @RequirePermission("project:view")
    public R<IPage<Project>> page(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "20") long size,
                                  @RequestParam(required = false) Long programId,
                                  @RequestParam(required = false) String status) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .orderByAsc(Project::getPriority)
                .orderByDesc(Project::getId);
        if (programId != null) {
            wrapper.eq(Project::getProgramId, programId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Project::getStatus, status);
        }
        return R.ok(projectService.page(new Page<>(page, size), wrapper));
    }

    /** 创建项目（programId 可空 = 独立项目，AC-F3.1；进度三列服务端强制 0/0/0）。 */
    @PostMapping
    @RequirePermission("project:create")
    public R<Project> create(@RequestBody ProjectSaveRequest req) {
        if (req.getName() == null || req.getName().trim().isEmpty()) {
            return R.fail(400, "name 不能为空");
        }
        String err = validateStatusAndPriority(req);
        if (err != null) return R.fail(400, err);
        if (req.getProgramId() != null && programService.getById(req.getProgramId()) == null) {
            return R.fail(404, "所属项目群不存在: " + req.getProgramId());
        }
        Project p = new Project();
        applyRequest(p, req);
        if (p.getStatus() == null || p.getStatus().isEmpty()) p.setStatus("planning");
        if (p.getPriority() == null) p.setPriority(5);
        // AC-F3.2：进度三列服务端强制初值（列 DEFAULT 0 + 显式赋值回显一致）
        p.setProgress(0);
        p.setCaseTotal(0);
        p.setCaseDone(0);
        projectService.save(p);
        auditService.log("project_create", "project", String.valueOf(p.getId()),
                "{\"name\":\"" + safeJson(p.getName()) + "\",\"programId\":"
                        + (p.getProgramId() == null ? "null" : p.getProgramId()) + "}");
        return R.ok(p);
    }

    /** 项目详情：基础字段 + 进度三列（只读）+ 已绑定原则清单（AC-F3.3 展示态）。 */
    @GetMapping("/{id}")
    @RequirePermission("project:view")
    public R<ProjectDetailVo> get(@PathVariable Long id) {
        return R.ok(projectService.detail(id));
    }

    /** 编辑项目：请求中的 progress/case_total/case_done 一律忽略（AC-F3.2）。 */
    @PutMapping("/{id}")
    @RequirePermission("project:edit")
    public R<Project> update(@PathVariable Long id, @RequestBody ProjectSaveRequest req) {
        Project existing = projectService.getById(id);
        if (existing == null) return R.fail(404, "项目不存在: " + id);
        String err = validateStatusAndPriority(req);
        if (err != null) return R.fail(400, err);
        boolean programChanged = req.getProgramId() != null
                && !req.getProgramId().equals(existing.getProgramId());
        if (programChanged && programService.getById(req.getProgramId()) == null) {
            return R.fail(404, "所属项目群不存在: " + req.getProgramId());
        }
        // 进度三列不在此触碰（实体 updateStrategy=NEVER + 此处不拷贝双保险）
        applyRequest(existing, req);
        projectService.updateById(existing);
        auditService.log("project_update", "project", String.valueOf(id),
                "{\"name\":\"" + safeJson(existing.getName()) + "\"}");
        return R.ok(existing);
    }

    /** 逻辑删：成员 Case.project_id 置空（Case 保留回落场景C）+ 原则绑定行清理后删。 */
    @DeleteMapping("/{id}")
    @RequirePermission("project:edit")
    public R<Void> delete(@PathVariable Long id) {
        projectService.deleteWithUnlinkCase(id);
        auditService.log("project_delete", "project", String.valueOf(id));
        return R.ok();
    }

    // ------------------------------------------------------------------
    // Case 挂接/解除（T17，Q7 最小更新：仅改 t_case.project_id 一列）
    // ------------------------------------------------------------------

    /**
     * 挂接 Case 到项目（AC-F4.2；换挂时新旧项目都触发进度重算）。
     *
     * <p>不加事务（SE 决策 D-2 口径）：单条 update 自动提交，异步重算在挂接落库之后
     * 提交，读到的必然是提交后真值。</p>
     */
    @PostMapping("/{id}/cases/{caseId}")
    @RequirePermission("project:edit")
    public R<CaseLinkResult> linkCase(@PathVariable Long id, @PathVariable String caseId) {
        Project project = projectService.getById(id);
        if (project == null) return R.fail(404, "项目不存在: " + id);
        Case c = loadCase(caseId);
        if (c == null) return R.fail(404, "Case 不存在: " + caseId);

        Long oldProjectId = c.getProjectId();
        // 最小更新：仅 SET project_id 一列（Q7 裁决：不开放 Case 其他字段编辑）
        caseService.update(new LambdaUpdateWrapper<Case>()
                .eq(Case::getId, c.getId())
                .set(Case::getProjectId, id));
        // 新旧项目都重算（AC-F4.2/F8.2：挂接 total+1；换挂旧项目 total-1）
        Long tenantId = TenantContext.get();
        progressService.recalculateAsync(id, tenantId);
        if (oldProjectId != null && !oldProjectId.equals(id)) {
            progressService.recalculateAsync(oldProjectId, tenantId);
        }
        auditService.log("case_link", "case", c.getCaseId(),
                "{\"projectId\":" + id + ",\"oldProjectId\":"
                        + (oldProjectId == null ? "null" : oldProjectId) + "}");
        CaseLinkResult result = new CaseLinkResult();
        result.setCaseId(c.getCaseId());
        result.setProjectId(id);
        return R.ok(result);
    }

    /** 解除 Case 挂接（任意状态含 done 均可解，AC-F8.2：解除 done Case 验证进度回落；全量重算幂等）。 */
    @DeleteMapping("/{id}/cases/{caseId}")
    @RequirePermission("project:edit")
    public R<CaseLinkResult> unlinkCase(@PathVariable Long id, @PathVariable String caseId) {
        Project project = projectService.getById(id);
        if (project == null) return R.fail(404, "项目不存在: " + id);
        Case c = loadCase(caseId);
        if (c == null) return R.fail(404, "Case 不存在: " + caseId);
        if (c.getProjectId() == null || !c.getProjectId().equals(id)) {
            return R.fail(400, "该 Case 未挂接到此项目");
        }
        caseService.update(new LambdaUpdateWrapper<Case>()
                .eq(Case::getId, c.getId())
                .set(Case::getProjectId, null));
        progressService.recalculateAsync(id, TenantContext.get());
        auditService.log("case_unlink", "case", c.getCaseId(), "{\"projectId\":" + id + "}");
        CaseLinkResult result = new CaseLinkResult();
        result.setCaseId(c.getCaseId());
        result.setProjectId(null);
        return R.ok(result);
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 项目创建/编辑请求（进度三列不出现在请求模型 = 结构性忽略，AC-F3.2） */
    @Data
    public static class ProjectSaveRequest {
        private String name;
        /** 可空 = 独立项目（AC-F3.1） */
        private Long programId;
        /** 项目描述/约束（非空时下行注入 Case 编排，F7） */
        private String description;
        /** planning / in_progress / delivered / closed */
        private String status;
        /** 1(高)-9(低)，默认 5 */
        private Integer priority;
    }

    /** 挂接/解除结果（进度异步重算，数值以项目详情查询为准） */
    @Data
    public static class CaseLinkResult {
        private String caseId;
        private Long projectId;
    }

    /** status/priority 枚举与取值校验 */
    private String validateStatusAndPriority(ProjectSaveRequest req) {
        if (req.getStatus() != null && !STATUSES.contains(req.getStatus())) {
            return "状态非法: " + req.getStatus() + "（应为 planning/in_progress/delivered/closed）";
        }
        if (req.getPriority() != null && (req.getPriority() < 1 || req.getPriority() > 9)) {
            return "priority 取值 1-9";
        }
        return null;
    }

    private void applyRequest(Project p, ProjectSaveRequest req) {
        if (req.getName() != null && !req.getName().trim().isEmpty()) p.setName(req.getName().trim());
        if (req.getProgramId() != null) p.setProgramId(req.getProgramId());
        if (req.getDescription() != null) p.setDescription(req.getDescription());
        if (req.getStatus() != null) p.setStatus(req.getStatus());
        if (req.getPriority() != null) p.setPriority(req.getPriority());
    }

    /** caseId(VARCHAR 业务键) → Case（跨租户被拦截器过滤 → null → 404，AC-ISO.2） */
    private Case loadCase(String caseId) {
        return caseService.getOne(new LambdaQueryWrapper<Case>().eq(Case::getCaseId, caseId));
    }

    /** JSON 字符串转义（审计 detail 防注入） */
    private static String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
