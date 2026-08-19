package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.hierarchy.AdrService;
import com.eaiselp.runtime.hierarchy.ArchitecturePrinciple;
import com.eaiselp.runtime.hierarchy.PrincipleService;
import com.eaiselp.runtime.hierarchy.dto.AdrVo;
import com.eaiselp.runtime.hierarchy.dto.BoundProjectVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 架构原则管理 REST API（L1 治理配置，PRJ-002 T26 + 批4 项目绑定管理，
 * 路径前缀 /api/v1/principles）。
 *
 * <p>契约对齐 SE §8.1：CRUD + 启停 + 原则维度项目绑定管理
 * （GET /{id}/projects 查绑定、PUT /{id}/projects 全量覆盖绑定——删旧插新）。
 * code 租户内唯一冲突 → 409（AC-F5.1）；启停即时影响新编排注入、绑定关系保留
 * （AC-F5.2）；删除同步清理 t_project_principle 绑定（DBA D2 反向清理）。</p>
 *
 * <p>原则属 L1 治理配置：不判分层开关（关 L2 的场景B 客户仍需原则注入），LayerGuardInterceptor
 * 不拦本前缀（SE §7.3）。</p>
 *
 * <p>权限（V4 r3 seed 1038~1040）：读 {@code principle:view}、建 {@code principle:create}、
 * 改/删/绑定 {@code principle:edit}（project_manager 只读）。写操作全审计
 * （principle_create/update/enabled/delete/bind）。</p>
 */
@RestController
@RequestMapping("/api/v1/principles")
@RequiredArgsConstructor
public class PrincipleController {

    /** 原则类型合法集 */
    private static final Set<String> TYPES = Set.of("tech", "data", "security", "governance");

    /** 执行级别合法集（must 违反将被 Reviewer 门禁拦截；8000 截断按 must&gt;should&gt;may） */
    private static final Set<String> LEVELS = Set.of("must", "should", "may");

    private final PrincipleService principleService;
    private final AdrService adrService;
    private final AuditService auditService;

    /** 全量列表（按 code 排序，含停用原则——停用绑定关系保留，需要展示）。 */
    @GetMapping
    @RequirePermission("principle:view")
    public R<List<ArchitecturePrinciple>> list() {
        return R.ok(principleService.list(new LambdaQueryWrapper<ArchitecturePrinciple>()
                .orderByAsc(ArchitecturePrinciple::getCode)));
    }

    /** 创建原则（code 租户内唯一冲突 → 409，AC-F5.1）。 */
    @PostMapping
    @RequirePermission("principle:create")
    public R<ArchitecturePrinciple> create(@RequestBody PrincipleSaveRequest req) {
        if (req.getCode() == null || req.getCode().trim().isEmpty()) {
            return R.fail(400, "code 不能为空");
        }
        if (req.getTitle() == null || req.getTitle().trim().isEmpty()) {
            return R.fail(400, "title 不能为空");
        }
        String err = validateEnums(req);
        if (err != null) return R.fail(400, err);
        principleService.checkCodeAvailable(req.getCode().trim(), null);
        ArchitecturePrinciple ap = new ArchitecturePrinciple();
        applyRequest(ap, req);
        if (ap.getEnabled() == null) ap.setEnabled(1);
        principleService.save(ap);
        auditService.log("principle_create", "principle", String.valueOf(ap.getId()),
                "{\"code\":\"" + ap.getCode() + "\",\"enforceLevel\":\"" + ap.getEnforceLevel() + "\"}");
        return R.ok(ap);
    }

    /** 原则详情。 */
    @GetMapping("/{id}")
    @RequirePermission("principle:view")
    public R<ArchitecturePrinciple> get(@PathVariable Long id) {
        ArchitecturePrinciple ap = principleService.getById(id);
        if (ap == null) return R.fail(404, "原则不存在: " + id);
        return R.ok(ap);
    }

    /** 编辑原则（code 变更同样走租户内唯一校验；null 字段视为不变）。 */
    @PutMapping("/{id}")
    @RequirePermission("principle:edit")
    public R<ArchitecturePrinciple> update(@PathVariable Long id, @RequestBody PrincipleSaveRequest req) {
        ArchitecturePrinciple existing = principleService.getById(id);
        if (existing == null) return R.fail(404, "原则不存在: " + id);
        String err = validateEnums(req);
        if (err != null) return R.fail(400, err);
        if (req.getCode() != null && !req.getCode().trim().equals(existing.getCode())) {
            principleService.checkCodeAvailable(req.getCode().trim(), id);
        }
        applyRequest(existing, req);
        principleService.updateById(existing);
        auditService.log("principle_update", "principle", String.valueOf(id),
                "{\"code\":\"" + existing.getCode() + "\"}");
        return R.ok(existing);
    }

    /** 启停原则（即时影响新编排注入清单，绑定关系保留，AC-F5.2）。 */
    @PutMapping("/{id}/enabled")
    @RequirePermission("principle:edit")
    public R<ArchitecturePrinciple> toggleEnabled(@PathVariable Long id, @RequestBody EnabledRequest req) {
        if (req.getEnabled() == null) return R.fail(400, "enabled 不能为空");
        ArchitecturePrinciple existing = principleService.getById(id);
        if (existing == null) return R.fail(404, "原则不存在: " + id);
        existing.setEnabled(req.getEnabled() ? 1 : 0);
        principleService.updateById(existing);
        auditService.log("principle_enabled", "principle", String.valueOf(id),
                "{\"enabled\":" + req.getEnabled() + "}");
        return R.ok(existing);
    }

    /** 逻辑删：同步清理 t_project_principle 绑定行（DBA D2）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("principle:edit")
    public R<Void> delete(@PathVariable Long id) {
        principleService.deleteWithCleanup(id);
        auditService.log("principle_delete", "principle", String.valueOf(id));
        return R.ok();
    }

    /** 查询该原则已绑定的项目清单（原则维度绑定管理）。 */
    @GetMapping("/{id}/projects")
    @RequirePermission("principle:view")
    public R<List<BoundProjectVo>> boundProjects(@PathVariable Long id) {
        return R.ok(principleService.boundProjects(id));
    }

    /** 原则-项目绑定全量覆盖（删旧插新；目标集为空数组 = 清空该原则全部绑定）。 */
    @PutMapping("/{id}/projects")
    @RequirePermission("principle:edit")
    public R<List<Long>> bindProjects(@PathVariable Long id, @RequestBody BindProjectsRequest req) {
        principleService.bindProjects(id, req.getProjectIds());
        auditService.log("principle_bind", "principle", String.valueOf(id),
                "{\"projectCount\":" + (req.getProjectIds() == null ? 0 : req.getProjectIds().size()) + "}");
        return R.ok(req.getProjectIds() == null ? List.of() : req.getProjectIds());
    }

    /**
     * 原则关联 ADR 反查（case-20260818 T17，api-contracts §4 末端点，AC-F4.3 原则侧聚合）：
     * 按原则 id 载入 code 后反查全部状态 ADR，供原则管理/编辑页"关联的 ADR 列表"区块。
     *
     * <p>ADR 属不限层知识资产（AC-SWITCH.2），但本端点挂 principles 前缀（principle:view，
     * 同为不限层）——与 ADR 端点一样不注册 LayerGuard。</p>
     */
    @GetMapping("/{id}/adrs")
    @RequirePermission("principle:view")
    public R<List<AdrVo>> relatedAdrs(@PathVariable Long id) {
        ArchitecturePrinciple ap = principleService.getById(id);
        if (ap == null) return R.fail(404, "原则不存在: " + id);
        return R.ok(adrService.listByPrincipleCode(ap.getCode()));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 原则创建/编辑请求 */
    @Data
    public static class PrincipleSaveRequest {
        /** 原则编号（如 P11），租户内唯一 */
        private String code;
        private String title;
        /** 原则内容（注入 L1 编排上下文，单条建议 ≤2000 字符，AC-F7.4） */
        private String content;
        /** tech / data / security / governance */
        private String principleType;
        /** must / should / may */
        private String enforceLevel;
        /** 1=启用 0=停用（默认启用） */
        private Boolean enabled;
    }

    @Data
    public static class EnabledRequest {
        private Boolean enabled;
    }

    /** 绑定全量覆盖请求：projectIds 为目标项目 ID 全集 */
    @Data
    public static class BindProjectsRequest {
        private List<Long> projectIds;
    }

    private String validateEnums(PrincipleSaveRequest req) {
        if (req.getPrincipleType() != null && !TYPES.contains(req.getPrincipleType())) {
            return "principleType 非法: " + req.getPrincipleType() + "（应为 tech/data/security/governance）";
        }
        if (req.getEnforceLevel() != null && !LEVELS.contains(req.getEnforceLevel())) {
            return "enforceLevel 非法: " + req.getEnforceLevel() + "（应为 must/should/may）";
        }
        return null;
    }

    private void applyRequest(ArchitecturePrinciple ap, PrincipleSaveRequest req) {
        if (req.getCode() != null && !req.getCode().trim().isEmpty()) ap.setCode(req.getCode().trim());
        if (req.getTitle() != null && !req.getTitle().trim().isEmpty()) ap.setTitle(req.getTitle().trim());
        if (req.getContent() != null) ap.setContent(req.getContent());
        if (req.getPrincipleType() != null) ap.setPrincipleType(req.getPrincipleType());
        if (req.getEnforceLevel() != null) ap.setEnforceLevel(req.getEnforceLevel());
        if (req.getEnabled() != null) ap.setEnabled(req.getEnabled() ? 1 : 0);
    }
}
