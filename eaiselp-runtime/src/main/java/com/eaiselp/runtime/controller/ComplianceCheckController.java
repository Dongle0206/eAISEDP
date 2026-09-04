package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.governance.ComplianceCheck;
import com.eaiselp.runtime.governance.ComplianceCheckService;
import com.eaiselp.runtime.governance.dto.ComplianceCheckVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 合规检查 REST API（case-20260821 T3，路径前缀 /api/v1/compliance-checks，契约=api-contracts §2）。
 *
 * <p><b>不限层</b>（AC-SWITCH.1）：不注册 LayerGuard。薄控制器：必填/枚举/custom 联动
 * 校验全在 {@link ComplianceCheckService}。</p>
 *
 * <p>权限（V7 seed 1074~1076）：读 {@code compliance:view}（C1/C3）、建
 * {@code compliance:create}（C2）、改/删 {@code compliance:edit}（C4/C5）。写审计
 * （compliance_create/update/delete）在 Service——update detail 含 oldResult→newResult
 * （覆盖式历史唯一留痕，AC-F1.10）。</p>
 */
@RestController
@RequestMapping("/api/v1/compliance-checks")
@RequiredArgsConstructor
public class ComplianceCheckController {

    private final ComplianceCheckService complianceCheckService;

    /**
     * 列表（C1）：默认 id DESC（PRD 未锁排序）；筛选 framework/result/overdueOnly
     * （recheck_date&lt;今天，na 不豁免，AC-F1.11）/keyword（checkName LIKE）；
     * VO 含服务端判定 overdue（D-10）。
     */
    @GetMapping
    @RequirePermission("compliance:view")
    public R<IPage<ComplianceCheckVo>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size,
                                            @RequestParam(required = false) String framework,
                                            @RequestParam(required = false) String result,
                                            @RequestParam(required = false) Boolean overdueOnly,
                                            @RequestParam(required = false) String keyword) {
        return R.ok(complianceCheckService.pageFilter(framework, result, overdueOnly, keyword, page, size));
    }

    /** 创建检查项（C2，手动登记制；checkDate 缺省服务端取当天；custom↔frameworkName 双向联动校验）。 */
    @PostMapping
    @RequirePermission("compliance:create")
    public R<ComplianceCheckVo> create(@RequestBody CheckSaveRequest req) {
        ComplianceCheck created = complianceCheckService.create(toEntity(req));
        return R.ok(complianceCheckService.toVo(created));
    }

    /** 详情（C3）：全字段 + overdue；跨租户/不存在 → 404。 */
    @GetMapping("/{id}")
    @RequirePermission("compliance:view")
    public R<ComplianceCheckVo> get(@PathVariable Long id) {
        return R.ok(complianceCheckService.detailVo(id));
    }

    /** 编辑（C4）：result 覆盖式单值当前态更新，旧值留痕进审计 detail（AC-F1.10）。 */
    @PutMapping("/{id}")
    @RequirePermission("compliance:edit")
    public R<ComplianceCheckVo> update(@PathVariable Long id, @RequestBody CheckSaveRequest req) {
        ComplianceCheck updated = complianceCheckService.edit(id, toEntity(req));
        return R.ok(complianceCheckService.toVo(updated));
    }

    /** 逻辑删（C5）+ 审计 compliance_delete。 */
    @DeleteMapping("/{id}")
    @RequirePermission("compliance:edit")
    public R<Void> delete(@PathVariable Long id) {
        complianceCheckService.remove(id);
        return R.ok();
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 检查项创建/编辑请求（契约 §2 C2）。 */
    @Data
    public static class CheckSaveRequest {
        /** 检查项名（必填 ≤200，uk 冲突 400） */
        private String checkName;
        /** djba2.0/iso27001/gdpr/custom（非法 400 指名） */
        private String framework;
        /** 自定义框架名（custom 必填/非 custom 必须为空——双向联动 400，AC-F1.9） */
        private String frameworkName;
        /** 条款引用（≤128，如 "ISO27001 A.9.4.1"） */
        private String clauseRef;
        /** 检查描述 */
        private String description;
        /** pass/fail/partial/na（必填，非法 400） */
        private String result;
        /** 证据说明（≤1000） */
        private String evidenceNote;
        /** 检查日期（缺省服务端取当天） */
        private LocalDate checkDate;
        /** 复检日期（可空；逾期红标，na 不豁免） */
        private LocalDate recheckDate;
        /** 检查责任人 */
        private String owner;
    }

    /** 请求 → 实体（无服务端计算列，全字段透传；校验在 Service）。 */
    private static ComplianceCheck toEntity(CheckSaveRequest req) {
        ComplianceCheck c = new ComplianceCheck();
        c.setCheckName(req.getCheckName());
        c.setFramework(req.getFramework());
        c.setFrameworkName(req.getFrameworkName());
        c.setClauseRef(req.getClauseRef());
        c.setDescription(req.getDescription());
        c.setResult(req.getResult());
        c.setEvidenceNote(req.getEvidenceNote());
        c.setCheckDate(req.getCheckDate());
        c.setRecheckDate(req.getRecheckDate());
        c.setOwner(req.getOwner());
        return c;
    }
}
