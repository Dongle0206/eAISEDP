package com.eaiselp.runtime.controller;

import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.hierarchy.TechRadarItem;
import com.eaiselp.runtime.hierarchy.TechRadarService;
import com.eaiselp.runtime.hierarchy.dto.TechRadarVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 技术雷达 REST API（case-20260818 T18，路径前缀 /api/v1/tech-radar，契约=api-contracts §5）。
 *
 * <p><b>不限层</b>（AC-SWITCH.2）：不注册 LayerGuard——任何层开关组合下恒可用。
 * 薄控制器：象限/环四值枚举校验、同名唯一冲突、环移动审计（radar_update 记
 * fromRing→toRing）、待复审标记全在 {@link TechRadarService}（批A T13）。</p>
 *
 * <p>权限（V5 seed 1056~1058）：读 {@code radar:view}、建 {@code radar:create}、
 * 改/删 {@code radar:edit}。写审计（radar_create/update/delete）在 Service。</p>
 */
@RestController
@RequestMapping("/api/v1/tech-radar")
@RequiredArgsConstructor
public class TechRadarController {

    private final TechRadarService techRadarService;

    /** 列表（quadrant/ring 可组合筛选，含 pendingReview 派生标记，按 reviewedAt 倒序）。 */
    @GetMapping
    @RequirePermission("radar:view")
    public R<List<TechRadarVo>> list(@RequestParam(required = false) String quadrant,
                                     @RequestParam(required = false) String ring) {
        return R.ok(techRadarService.list(quadrant, ring));
    }

    /**
     * 四象限分组（techniques/tools/platforms/languages → 各自条目列表）：
     * 恒含四键、空象限为空列表（前端 SVG 扇区渲染零判空）；ring 可选过滤。
     */
    @GetMapping("/quadrants")
    @RequirePermission("radar:view")
    public R<Map<String, List<TechRadarVo>>> quadrants(@RequestParam(required = false) String ring) {
        return R.ok(techRadarService.quadrantGroups(ring));
    }

    /** 创建技术项（一技术一当前态；同名重复 400 提示"编辑既有项"，AC-F5.1）。 */
    @PostMapping
    @RequirePermission("radar:create")
    public R<TechRadarVo> create(@RequestBody TechRadarSaveRequest req) {
        TechRadarItem created = techRadarService.create(toEntity(req));
        return R.ok(techRadarService.toVo(created));
    }

    /** 详情（含 pendingReview 派生标记）；跨租户/不存在 → 404。 */
    @GetMapping("/{id}")
    @RequirePermission("radar:view")
    public R<TechRadarVo> get(@PathVariable Long id) {
        return R.ok(techRadarService.detailVo(id));
    }

    /** 编辑（改 ring 时 Service 写环移动审计，AC-F5.3 唯一留痕）。 */
    @PutMapping("/{id}")
    @RequirePermission("radar:edit")
    public R<TechRadarVo> update(@PathVariable Long id, @RequestBody TechRadarSaveRequest req) {
        TechRadarItem updated = techRadarService.edit(id, toEntity(req));
        return R.ok(techRadarService.toVo(updated));
    }

    /** 逻辑删。 */
    @DeleteMapping("/{id}")
    @RequirePermission("radar:edit")
    public R<Void> delete(@PathVariable Long id) {
        techRadarService.remove(id);
        return R.ok();
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 技术项创建/编辑请求（API 语义名 name/reviewedAt ↔ V5 列 tech_name/reviewed_at，C4）。 */
    @Data
    public static class TechRadarSaveRequest {
        private String name;
        /** techniques / tools / platforms / languages */
        private String quadrant;
        /** adopt / trial / assess / hold */
        private String ring;
        private String reason;
        /** ISO yyyy-MM-dd（必填；距今>180天 → pendingReview 角标） */
        private LocalDate reviewedAt;
        private String remark;
    }

    /** 请求 → 实体（语义名→V5 列名换名）。 */
    private static TechRadarItem toEntity(TechRadarSaveRequest req) {
        TechRadarItem item = new TechRadarItem();
        item.setTechName(req.getName());
        item.setQuadrant(req.getQuadrant());
        item.setRing(req.getRing());
        item.setReason(req.getReason());
        item.setReviewedAt(req.getReviewedAt());
        item.setRemark(req.getRemark());
        return item;
    }
}
