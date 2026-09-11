package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.entity.Incident;
import com.eaiselp.data.service.subscription.IncidentService;
import com.eaiselp.data.service.subscription.dto.IncidentVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 事故台账 REST API（case-20260823-商用化 T11；路径 /api/v1/incidents，契约 N1~N4，
 * api-contracts.md §5）。view 全五角色（COMMON_MENUS 只读）；create/edit =
 * platform_admin/tenant_admin/project_manager（engineer、executive 403，AC-F3.4——
 * 权限矩阵 V8 seed 1087~1089 承载）。
 *
 * <p><b>不限层</b>（AC-G3）：不注册 LayerGuard。<b>薄层</b>：时间格式解析（40000）+
 * Service 承载必填/倒挂校验。<b>租户隔离由拦截器承载</b>（AC-F3.5——tenant_id 不入参，
 * G13）。无 DELETE 端点（D-14 台账留痕）。</p>
 */
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IncidentService incidentService;

    /** N1: 事故分页列表（默认 occurred_time DESC；recovered 筛选 AC-F3.3）。 */
    @GetMapping
    @RequirePermission("incident:view")
    public R<IPage<IncidentVo>> page(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "20") long size,
                                     @RequestParam(required = false) Boolean recovered) {
        return R.ok(incidentService.pageFilter(recovered, page, size));
    }

    /** N3: 事故详情（拦截器保证租户隔离——他租户 404，AC-F3.5）。 */
    @GetMapping("/{id}")
    @RequirePermission("incident:view")
    public R<IncidentVo> get(@PathVariable Long id) {
        return R.ok(incidentService.detail(id));
    }

    /** N2: 登记事故（title/occurredTime/impactDesc 必填；slaBreach 可选缺省 0，V8 增列 D-4）。 */
    @PostMapping
    @RequirePermission("incident:create")
    public R<IncidentVo> create(@RequestBody IncidentSaveRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank()
                || req.getImpactDesc() == null || req.getImpactDesc().isBlank()) {
            return R.fail(40000, "title/impactDesc 不能为空");
        }
        LocalDateTime occurred = parseTime(req.getOccurredTime(), "occurredTime");
        if (occurred == null) {
            return R.fail(40000, "occurredTime 必填且格式为 yyyy-MM-dd HH:mm:ss");
        }
        return R.ok(incidentService.create(toEntity(req, occurred, parseTime(req.getRecoveryTime(), "recoveryTime"))));
    }

    /** N4: 编辑事故（补记 recoveryTime/rootCause 主场景，AC-F3.2；审计 incident_update old→new）。 */
    @PutMapping("/{id}")
    @RequirePermission("incident:edit")
    public R<IncidentVo> update(@PathVariable Long id, @RequestBody IncidentSaveRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank()
                || req.getImpactDesc() == null || req.getImpactDesc().isBlank()) {
            return R.fail(40000, "title/impactDesc 不能为空");
        }
        LocalDateTime occurred = parseTime(req.getOccurredTime(), "occurredTime");
        if (occurred == null) {
            return R.fail(40000, "occurredTime 必填且格式为 yyyy-MM-dd HH:mm:ss");
        }
        return R.ok(incidentService.update(id, toEntity(req, occurred, parseTime(req.getRecoveryTime(), "recoveryTime"))));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与工具
    // ------------------------------------------------------------------

    /** 事故登记/编辑请求（契约 §5 N2；全量编辑——补记场景传 recoveryTime/rootCause）。 */
    @Data
    public static class IncidentSaveRequest {
        private String title;
        /** yyyy-MM-dd HH:mm:ss 必填 */
        private String occurredTime;
        private String impactDesc;
        /** 可选 null=未恢复；非空须 ≥ occurredTime（倒挂 400，Service 校验） */
        private String recoveryTime;
        /** 可选，恢复后补记 */
        private String rootCause;
        /** 可选缺省 0（V8 增列：登记时人工违约判定，纯台账 D-4） */
        private Boolean slaBreach;
    }

    private static Incident toEntity(IncidentSaveRequest req, LocalDateTime occurred, LocalDateTime recovery) {
        Incident i = new Incident();
        i.setTitle(req.getTitle().trim());
        i.setOccurredTime(occurred);
        i.setImpactDesc(req.getImpactDesc().trim());
        i.setRecoveryTime(recovery);
        i.setRootCause(req.getRootCause());
        i.setSlaBreach(Boolean.TRUE.equals(req.getSlaBreach()) ? 1 : 0);
        return i;
    }

    /** 时间解析：null 返回 null（可选字段）；格式非法返回 null 由调用方指名 40000（契约错误码表）。 */
    private static LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
