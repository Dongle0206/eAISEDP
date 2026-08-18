package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.hierarchy.Strategy;
import com.eaiselp.runtime.hierarchy.StrategyService;
import com.eaiselp.runtime.hierarchy.dto.StrategyBoardVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 战略目标管理 REST API（L3 层，PRJ-002 T23，路径前缀 /api/v1/strategies）。
 *
 * <p>契约对齐 SE §8.1/§8.2：CRUD + 生命周期 transit（draft→active→achieved/archived）
 * + 看板 board 聚合。achieved/archived 后仅 KPI 可编辑（AC-F1.2）；有关联项目群拒删
 * （AC-F1.3，文案锚点"存在关联项目群，请先解除关联"）。</p>
 *
 * <p>权限（AC-RBAC.1 战略层限 executive + platform_admin，V4 r3 seed 1032/1033 +
 * V1 既有 1029）：读 {@code strategy:view}、建 {@code strategy:create}、改/流转/删
 * {@code strategy:edit}。写操作全审计（strategy_create/update/transit/delete）。</p>
 *
 * <p>L3 关闭时整前缀被 LayerGuardInterceptor 以业务码 43001 拒绝（本类无需自查开关）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/strategies")
@RequiredArgsConstructor
public class StrategyController {

    /** 战略生命周期合法状态集（draft 为创建初始态，不接受直接流转目标） */
    private static final Set<String> STATUSES = Set.of("draft", "active", "achieved", "archived");

    private final StrategyService strategyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 分页查询战略，默认 status=active（status=all 查全部）。 */
    @GetMapping
    @RequirePermission("strategy:view")
    public R<IPage<Strategy>> page(@RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "20") long size,
                                   @RequestParam(defaultValue = "active") String status) {
        LambdaQueryWrapper<Strategy> wrapper = new LambdaQueryWrapper<Strategy>()
                .orderByDesc(Strategy::getId);
        if (status != null && !status.isEmpty() && !"all".equals(status)) {
            wrapper.eq(Strategy::getStatus, status);
        }
        return R.ok(strategyService.page(new Page<>(page, size), wrapper));
    }

    /** 创建战略（status=draft；KPI 数组序列化为 JSON 文本落 kpi 列）。 */
    @PostMapping
    @RequirePermission("strategy:create")
    public R<Strategy> create(@RequestBody StrategySaveRequest req) {
        String err = validateSave(req, true);
        if (err != null) return R.fail(400, err);
        Strategy s = new Strategy();
        s.setTitle(req.getTitle().trim());
        s.setDescription(req.getDescription());
        s.setHorizon(req.getHorizon());
        s.setOwner(req.getOwner());
        s.setKpi(toKpiJson(req.getKpi()));
        s.setStatus("draft");
        strategyService.save(s);
        auditService.log("strategy_create", "strategy", String.valueOf(s.getId()),
                "{\"title\":\"" + safeJson(s.getTitle()) + "\",\"horizon\":\"" + s.getHorizon() + "\"}");
        return R.ok(s);
    }

    /** 战略详情。 */
    @GetMapping("/{id}")
    @RequirePermission("strategy:view")
    public R<Strategy> get(@PathVariable Long id) {
        Strategy s = strategyService.getById(id);
        if (s == null) return R.fail(404, "战略不存在: " + id);
        return R.ok(s);
    }

    /**
     * 编辑战略：achieved/archived 状态下仅 KPI 可改（AC-F1.2，改动其他字段返回 400）；
     * 请求字段为 null 视为不变。
     */
    @PutMapping("/{id}")
    @RequirePermission("strategy:edit")
    public R<Strategy> update(@PathVariable Long id, @RequestBody StrategySaveRequest req) {
        Strategy existing = strategyService.getById(id);
        if (existing == null) return R.fail(404, "战略不存在: " + id);

        boolean lifecycleClosed = "achieved".equals(existing.getStatus())
                || "archived".equals(existing.getStatus());
        if (lifecycleClosed && changesBeyondKpi(existing, req)) {
            return R.fail(400, "战略已" + existing.getStatus() + "，仅 KPI 可编辑");
        }
        if (req.getTitle() != null && !req.getTitle().trim().isEmpty()) {
            existing.setTitle(req.getTitle().trim());
        }
        if (req.getDescription() != null) existing.setDescription(req.getDescription());
        if (req.getHorizon() != null) existing.setHorizon(req.getHorizon());
        if (req.getOwner() != null) existing.setOwner(req.getOwner());
        if (req.getKpi() != null) existing.setKpi(toKpiJson(req.getKpi()));
        strategyService.updateById(existing);
        auditService.log("strategy_update", "strategy", String.valueOf(id),
                "{\"status\":\"" + existing.getStatus() + "\",\"kpiOnly\":" + lifecycleClosed + "}");
        return R.ok(existing);
    }

    /** 生命周期流转：active / achieved / archived（draft→active→achieved/archived 校验在 Service）。 */
    @PostMapping("/{id}/transit")
    @RequirePermission("strategy:edit")
    public R<Strategy> transit(@PathVariable Long id, @RequestBody TransitRequest req) {
        if (req.getStatus() == null || !STATUSES.contains(req.getStatus())
                || "draft".equals(req.getStatus())) {
            return R.fail(400, "目标状态非法: " + req.getStatus() + "（应为 active/achieved/archived）");
        }
        Strategy s = strategyService.transit(id, req.getStatus());
        auditService.log("strategy_transit", "strategy", String.valueOf(id),
                "{\"to\":\"" + s.getStatus() + "\"}");
        return R.ok(s);
    }

    /** 逻辑删；存在关联项目群 → 400 拒删（AC-F1.3）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("strategy:edit")
    public R<Void> delete(@PathVariable Long id) {
        strategyService.deleteWithProgramCheck(id);
        auditService.log("strategy_delete", "strategy", String.valueOf(id));
        return R.ok();
    }

    /** 战略看板聚合：KPI + 关联项目群列表（各含项目数/群内进度均值）+ 顶层双层均值（AC-F8.5）。 */
    @GetMapping("/{id}/board")
    @RequirePermission("strategy:view")
    public R<StrategyBoardVo> board(@PathVariable Long id) {
        return R.ok(strategyService.board(id));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 战略创建/编辑请求（SE §8.2：kpi 为数组，落库序列化为 JSON 文本） */
    @Data
    public static class StrategySaveRequest {
        /** 标题（≤200，必填） */
        private String title;
        private String description;
        /** 时间维度: 1q / 1y / 3y */
        private String horizon;
        private String owner;
        private List<KpiItem> kpi;
    }

    /** KPI 条目（Q5 裁决：当前值人工维护，不自动回写） */
    @Data
    public static class KpiItem {
        private String name;
        private String target;
        private String current;
        private String unit;
    }

    @Data
    public static class TransitRequest {
        /** 目标状态：active / achieved / archived */
        private String status;
    }

    private String validateSave(StrategySaveRequest req, boolean forCreate) {
        if (forCreate && (req.getTitle() == null || req.getTitle().trim().isEmpty())) {
            return "title 不能为空";
        }
        if (req.getTitle() != null && req.getTitle().trim().length() > 200) {
            return "title 长度不能超过 200";
        }
        return null;
    }

    /** 判断请求是否试图改动 KPI 以外的字段（与现存值比对；null 视为不变） */
    private boolean changesBeyondKpi(Strategy existing, StrategySaveRequest req) {
        if (req.getTitle() != null && !req.getTitle().trim().equals(existing.getTitle())) return true;
        if (req.getDescription() != null && !req.getDescription().equals(existing.getDescription())) return true;
        if (req.getHorizon() != null && !req.getHorizon().equals(existing.getHorizon())) return true;
        if (req.getOwner() != null && !req.getOwner().equals(existing.getOwner())) return true;
        return false;
    }

    /** KPI 数组 → JSON 文本（序列化失败返回 null，降级不阻塞保存） */
    private String toKpiJson(List<KpiItem> kpi) {
        if (kpi == null) return null;
        try {
            return objectMapper.writeValueAsString(kpi);
        } catch (Exception e) {
            log.warn("[Strategy] KPI 序列化失败，按空处理: {}", e.getMessage());
            return null;
        }
    }

    /** JSON 字符串转义（审计 detail 防注入） */
    private static String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
