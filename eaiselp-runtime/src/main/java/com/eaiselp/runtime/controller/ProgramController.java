package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.hierarchy.Program;
import com.eaiselp.runtime.hierarchy.ProgramService;
import com.eaiselp.runtime.hierarchy.MilestoneService;
import com.eaiselp.runtime.hierarchy.Strategy;
import com.eaiselp.runtime.hierarchy.StrategyService;
import com.eaiselp.runtime.hierarchy.dto.MilestoneTimelineVo;
import com.eaiselp.runtime.hierarchy.dto.ProgramVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 项目群管理 REST API（L2 层，PRJ-002 T24，路径前缀 /api/v1/programs）。
 *
 * <p>契约对齐 SE §8.1/§8.2：CRUD + 章程（charter）编辑 + 按战略过滤列表 + 实时聚合
 * （项目数/群内进度均值）。strategyId 可空 = 场景B 从 L2 接入（AC-F2.1/P13）；
 * 删除时成员项目 program_id 置空后本群逻辑删（AC-F2.3，二次确认由前端承担）。</p>
 *
 * <p>权限（V4 r3/V1 seed）：读 {@code program:view}、建 {@code program:create}、
 * 改/删 {@code program:edit}。写操作全审计（program_create/update/delete）。</p>
 *
 * <p>L2 关闭时整前缀被 LayerGuardInterceptor 以业务码 43002 拒绝（本类无需自查开关）。</p>
 */
@RestController
@RequestMapping("/api/v1/programs")
@RequiredArgsConstructor
public class ProgramController {

    /** 项目群合法状态集 */
    private static final Set<String> STATUSES = Set.of("planning", "active", "suspended", "closed");

    private final ProgramService programService;
    private final StrategyService strategyService;
    private final MilestoneService milestoneService;
    private final AuditService auditService;

    /** 分页查询项目群（含项目数/进度均值实时聚合），可按状态与关联战略过滤。 */
    @GetMapping
    @RequirePermission("program:view")
    public R<IPage<ProgramVo>> page(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long strategyId) {
        if (status != null && !status.isEmpty() && !STATUSES.contains(status)) {
            return R.fail(400, "状态非法: " + status + "（应为 planning/active/suspended/closed）");
        }
        return R.ok(programService.pageWithProgress(page, size, status, strategyId));
    }

    /** 创建项目群（strategyId 可空 = 场景B，AC-F2.1）。 */
    @PostMapping
    @RequirePermission("program:create")
    public R<Program> create(@RequestBody ProgramSaveRequest req) {
        if (req.getName() == null || req.getName().trim().isEmpty()) {
            return R.fail(400, "name 不能为空");
        }
        if (req.getStatus() != null && !STATUSES.contains(req.getStatus())) {
            return R.fail(400, "状态非法: " + req.getStatus() + "（应为 planning/active/suspended/closed）");
        }
        // strategyId 非空时校验战略存在（跨租户被拦截器过滤 → 404 不泄露存在性，AC-ISO.2）
        if (req.getStrategyId() != null && strategyService.getById(req.getStrategyId()) == null) {
            return R.fail(404, "关联战略不存在: " + req.getStrategyId());
        }
        Program p = new Program();
        applyRequest(p, req);
        if (p.getStatus() == null || p.getStatus().isEmpty()) {
            p.setStatus("planning");
        }
        programService.save(p);
        auditService.log("program_create", "program", String.valueOf(p.getId()),
                "{\"name\":\"" + safeJson(p.getName()) + "\",\"strategyId\":"
                        + (p.getStrategyId() == null ? "null" : p.getStrategyId()) + "}");
        return R.ok(p);
    }

    /** 项目群详情：章程全文 + 项目列表 + 项目数 + 进度均值（AC-F2.2）。 */
    @GetMapping("/{id}")
    @RequirePermission("program:view")
    public R<ProgramVo> get(@PathVariable Long id) {
        return R.ok(programService.detail(id));
    }

    /** 编辑项目群（名称/章程/状态/起止/负责人/关联战略；null 字段视为不变，AC-F2.2）。 */
    @PutMapping("/{id}")
    @RequirePermission("program:edit")
    public R<Program> update(@PathVariable Long id, @RequestBody ProgramSaveRequest req) {
        Program existing = programService.getById(id);
        if (existing == null) return R.fail(404, "项目群不存在: " + id);
        if (req.getStatus() != null && !STATUSES.contains(req.getStatus())) {
            return R.fail(400, "状态非法: " + req.getStatus() + "（应为 planning/active/suspended/closed）");
        }
        if (req.getStrategyId() != null && !req.getStrategyId().equals(existing.getStrategyId())
                && strategyService.getById(req.getStrategyId()) == null) {
            return R.fail(404, "关联战略不存在: " + req.getStrategyId());
        }
        applyRequest(existing, req);
        programService.updateById(existing);
        auditService.log("program_update", "program", String.valueOf(id),
                "{\"status\":\"" + existing.getStatus() + "\",\"charterChanged\":"
                        + (req.getCharter() != null) + "}");
        return R.ok(existing);
    }

    /** 逻辑删：成员项目 program_id 置空后删（AC-F2.3）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("program:edit")
    public R<Void> delete(@PathVariable Long id) {
        programService.deleteWithUnlink(id);
        auditService.log("program_delete", "program", String.valueOf(id));
        return R.ok();
    }

    /**
     * 群聚合里程碑时间线（case-20260818 T15，api-contracts §2 末端点，AC-F2.6）：
     * 群直属 + 成员项目全部里程碑合并，按 targetDate 排序，ownerLevel 区分层级。
     *
     * <p>挂 programs 前缀 → L2 关闭天然 43002（C6 收敛：群聚合下钻语义保留挂靠端点）；
     * 权限用 {@code milestone:view}（里程碑读原子而非 program:view，与 milestones 前缀同源）。</p>
     */
    @GetMapping("/{id}/milestone-timeline")
    @RequirePermission("milestone:view")
    public R<List<MilestoneTimelineVo>> milestoneTimeline(@PathVariable Long id) {
        return R.ok(milestoneService.programTimeline(id));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 项目群创建/编辑请求（SE §8.2；strategyId 可空 = 不关联战略；日期为 ISO yyyy-MM-dd，由 Jackson jsr310 解析） */
    @Data
    public static class ProgramSaveRequest {
        private String name;
        /** 章程（下行约束：目标/边界） */
        private String charter;
        private Long strategyId;
        private String pgmManager;
        /** planning / active / suspended / closed */
        private String status;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    /** 请求字段 → 实体（null 视为不变；仅本方法触碰实体字段，避免漏改/误改） */
    private void applyRequest(Program p, ProgramSaveRequest req) {
        if (req.getName() != null && !req.getName().trim().isEmpty()) p.setName(req.getName().trim());
        if (req.getCharter() != null) p.setCharter(req.getCharter());
        if (req.getStrategyId() != null) p.setStrategyId(req.getStrategyId());
        if (req.getPgmManager() != null) p.setPgmManager(req.getPgmManager());
        if (req.getStatus() != null) p.setStatus(req.getStatus());
        if (req.getStartDate() != null) p.setStartDate(req.getStartDate());
        if (req.getEndDate() != null) p.setEndDate(req.getEndDate());
    }

    /** JSON 字符串转义（审计 detail 防注入） */
    private static String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
