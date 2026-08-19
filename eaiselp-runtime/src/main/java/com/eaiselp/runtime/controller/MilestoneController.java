package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.entity.Milestone;
import com.eaiselp.runtime.hierarchy.MilestoneService;
import com.eaiselp.runtime.hierarchy.dto.MilestoneTimelineVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 里程碑管理 REST API（L2 层，case-20260818 T15，路径前缀 /api/v1/milestones，契约=api-contracts §2）。
 *
 * <p>CRUD + transit 统一入口 + 分页时间线（ownerType/ownerId/status 过滤，命中
 * idx_ms_tenant_owner）。薄控制器：归属校验/状态机/编号生成/审计（milestone_create/
 * update/delete/transit）全在 {@link MilestoneService}（批A T10）；400/404 经
 * GlobalExceptionHandler → R.fail 业务码。</p>
 *
 * <p>权限（V5 seed 1046~1048）：读 {@code milestone:view}、建 {@code milestone:create}、
 * 改/删/流转 {@code milestone:edit}。L2 关闭时整前缀被 LayerGuardInterceptor 以业务码
 * 43002 拒绝（T20 注册，AC-SWITCH.1）。</p>
 */
@RestController
@RequestMapping("/api/v1/milestones")
@RequiredArgsConstructor
public class MilestoneController {

    private final MilestoneService milestoneService;

    /** 分页时间线（ownerType/ownerId 两级归属过滤 + status；Vo.overdue 黄角标为展示层派生）。 */
    @GetMapping
    @RequirePermission("milestone:view")
    public R<IPage<MilestoneTimelineVo>> page(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) String ownerType,
                                              @RequestParam(required = false) Long ownerId,
                                              @RequestParam(required = false) String status) {
        return R.ok(milestoneService.pageTimeline(ownerType, ownerId, status, page, size));
    }

    /** 创建里程碑（状态固定 planned；milestoneCode 服务端生成；审计在 Service）。 */
    @PostMapping
    @RequirePermission("milestone:create")
    public R<MilestoneTimelineVo> create(@RequestBody MilestoneSaveRequest req) {
        Milestone created = milestoneService.create(toEntity(req));
        return R.ok(milestoneService.toVo(created));
    }

    /** 里程碑详情（返回同列表行结构的全字段 Vo；跨租户/不存在 → 404）。 */
    @GetMapping("/{id}")
    @RequirePermission("milestone:view")
    public R<MilestoneTimelineVo> get(@PathVariable Long id) {
        return R.ok(milestoneService.toVo(milestoneService.loadOr404(id)));
    }

    /** 编辑里程碑（status/achievedDate 不在此改——状态变更只走 transit；legacy 两列不写；
     *  归属 ownerType/ownerId 不可变更——携带不同值 Service 400 拒绝，缺省保持库值，S2）。 */
    @PutMapping("/{id}")
    @RequirePermission("milestone:edit")
    public R<MilestoneTimelineVo> update(@PathVariable Long id, @RequestBody MilestoneSaveRequest req) {
        Milestone updated = milestoneService.edit(id, toEntity(req));
        return R.ok(milestoneService.toVo(updated));
    }

    /** 逻辑删。 */
    @DeleteMapping("/{id}")
    @RequirePermission("milestone:edit")
    public R<Void> delete(@PathVariable Long id) {
        milestoneService.remove(id);
        return R.ok();
    }

    /**
     * 状态流转统一入口（AC-F2.2/F2.3）：achievedDate 缺省当天（Service 内兜底）、
     * 撤销清空达成日期；非法流转 400（MilestoneStatus 状态机）。
     */
    @PostMapping("/{id}/transit")
    @RequirePermission("milestone:edit")
    public R<MilestoneTimelineVo> transit(@PathVariable Long id, @RequestBody TransitRequest req) {
        if (req.getTarget() == null || req.getTarget().isBlank()) {
            return R.fail(400, "target 不能为空");
        }
        Milestone ms = milestoneService.transit(id, req.getTarget().trim(), req.getAchievedDate());
        return R.ok(milestoneService.toVo(ms));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 里程碑创建/编辑请求（body 同创建，不含 status/achievedDate——状态变更只走 transit）。 */
    @Data
    public static class MilestoneSaveRequest {
        /** program / project */
        private String ownerType;
        private Long ownerId;
        private String title;
        private String description;
        /** ISO yyyy-MM-dd */
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate targetDate;
        /** 负责人 */
        private String owner;
        /** 阻塞说明（手工维护） */
        private String blocker;
        /** 群级涉及项目多选（仅展示，不参与自动判定） */
        private String subprojects;
    }

    /** 状态流转请求：target 必填；achievedDate 在 target=achieved 时缺省当天。 */
    @Data
    public static class TransitRequest {
        /** planned / achieved / delayed */
        private String target;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate achievedDate;
    }

    /** 请求 → 实体（API 语义名与实体列名同形，C4 无换名；status/achievedDate 不映射）。 */
    private static Milestone toEntity(MilestoneSaveRequest req) {
        Milestone ms = new Milestone();
        ms.setOwnerType(req.getOwnerType());
        ms.setOwnerId(req.getOwnerId());
        ms.setTitle(req.getTitle());
        ms.setDescription(req.getDescription());
        ms.setTargetDate(req.getTargetDate());
        ms.setOwner(req.getOwner());
        ms.setBlocker(req.getBlocker());
        ms.setSubprojects(req.getSubprojects());
        return ms;
    }
}
