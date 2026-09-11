package com.eaiselp.data.service.subscription.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Incident;
import com.eaiselp.data.mapper.IncidentMapper;
import com.eaiselp.data.service.subscription.IncidentService;
import com.eaiselp.data.service.subscription.dto.IncidentVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事故台账服务实现（case-20260823-商用化 T11）。口径见接口 Javadoc。
 *
 * <p>t_incident 为租户级表（不进 IGNORE_TABLES）：租户上下文下查询自动过滤（AC-F3.5）；
 * 本类不做任何显式 tenant_id 读写（拦截器承载），与账单域的 SYSTEM 通道（D-5）不同——
 * 事故台账无跨租户批处理诉求。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentServiceImpl implements IncidentService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper OM = new ObjectMapper();

    private final IncidentMapper incidentMapper;
    private final AuditService auditService;

    // ==================== N1 列表 ====================

    @Override
    public IPage<IncidentVo> pageFilter(Boolean recovered, long page, long size) {
        LambdaQueryWrapper<Incident> w = new LambdaQueryWrapper<>();
        if (recovered != null) {
            if (recovered) {
                w.isNotNull(Incident::getRecoveryTime);
            } else {
                w.isNull(Incident::getRecoveryTime);
            }
        }
        w.orderByDesc(Incident::getOccurredTime); // AC-F3.3：8-20 → 8-10 → 8-01
        return incidentMapper.selectPage(new Page<>(page, size), w).convert(this::toVo);
    }

    // ==================== N2 登记 ====================

    @Override
    public IncidentVo create(Incident incident) {
        validate(incident);
        if (incident.getSlaBreach() == null) {
            incident.setSlaBreach(0); // D-4：缺省未违约
        }
        incidentMapper.insert(incident);
        audit("incident_create", incident.getId(), null, incident);
        return toVo(incident);
    }

    // ==================== N3 详情 ====================

    @Override
    public IncidentVo detail(Long id) {
        return toVo(loadOr404(id));
    }

    // ==================== N4 编辑（补记主场景） ====================

    @Override
    public IncidentVo update(Long id, Incident patch) {
        Incident exist = loadOr404(id);
        validate(patch);
        Incident next = new Incident();
        next.setId(id);
        next.setTitle(patch.getTitle());
        next.setOccurredTime(patch.getOccurredTime());
        next.setRecoveryTime(patch.getRecoveryTime());
        next.setImpactDesc(patch.getImpactDesc());
        next.setRootCause(patch.getRootCause());
        next.setSlaBreach(patch.getSlaBreach() != null ? patch.getSlaBreach() : 0);
        incidentMapper.updateById(next);
        audit("incident_update", id, exist, next);
        return detail(id);
    }

    // ==================== 内部 ====================

    private void validate(Incident i) {
        if (i == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        if (i.getTitle() == null || i.getTitle().isBlank()) {
            throw new BizException(ResultCode.BAD_REQUEST, "title 不能为空");
        }
        if (i.getTitle().length() > 200) {
            throw new BizException(400, "title 长度超限（≤200）");
        }
        if (i.getOccurredTime() == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "occurredTime 不能为空");
        }
        if (i.getImpactDesc() == null || i.getImpactDesc().isBlank()) {
            throw new BizException(ResultCode.BAD_REQUEST, "impactDesc 不能为空");
        }
        if (i.getImpactDesc().length() > 1000) {
            throw new BizException(400, "impactDesc 长度超限（≤1000）");
        }
        if (i.getRootCause() != null && i.getRootCause().length() > 1000) {
            throw new BizException(400, "rootCause 长度超限（≤1000）");
        }
        // 倒挂校验（防脏数据，SE 补充）：recovery ≥ occurred
        if (i.getRecoveryTime() != null && i.getRecoveryTime().isBefore(i.getOccurredTime())) {
            throw new BizException(400, "recoveryTime 不能早于 occurredTime（倒挂）: occurred="
                    + i.getOccurredTime().format(FORMATTER) + ", recovery=" + i.getRecoveryTime().format(FORMATTER));
        }
    }

    private Incident loadOr404(Long id) {
        Incident incident = id != null ? incidentMapper.selectById(id) : null;
        if (incident == null) {
            throw new BizException(ResultCode.NOT_FOUND, "事故不存在: " + id);
        }
        return incident;
    }

    private IncidentVo toVo(Incident i) {
        return IncidentVo.builder()
                .id(i.getId())
                .title(i.getTitle())
                .occurredTime(i.getOccurredTime() != null ? i.getOccurredTime().format(FORMATTER) : null)
                .recoveryTime(i.getRecoveryTime() != null ? i.getRecoveryTime().format(FORMATTER) : null)
                .recovered(i.getRecoveryTime() != null)
                .impactDesc(i.getImpactDesc())
                .rootCause(i.getRootCause())
                .slaBreach(i.getSlaBreach() != null ? i.getSlaBreach() : 0)
                .createBy(i.getCreateBy())
                .createTime(i.getCreateTime() != null ? i.getCreateTime().format(FORMATTER) : null)
                .build();
    }

    private void audit(String action, Long id, Incident old, Incident now) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id);
        detail.put("title", now.getTitle());
        detail.put("occurredTime", now.getOccurredTime() != null ? now.getOccurredTime().format(FORMATTER) : null);
        detail.put("recoveryTime", now.getRecoveryTime() != null ? now.getRecoveryTime().format(FORMATTER) : null);
        detail.put("slaBreach", now.getSlaBreach());
        if (old != null) {
            Map<String, Object> oldSnap = new LinkedHashMap<>();
            oldSnap.put("title", old.getTitle());
            oldSnap.put("recoveryTime", old.getRecoveryTime() != null ? old.getRecoveryTime().format(FORMATTER) : null);
            oldSnap.put("rootCause", old.getRootCause());
            detail.put("old", oldSnap);
        }
        detail.put("operator", currentUsername());
        try {
            auditService.log(action, "incident", String.valueOf(id), OM.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("[Incident] 审计序列化失败（不阻塞业务）: {}", e.getMessage());
        }
    }

    private String currentUsername() {
        JwtClaims claims = LoginUser.get();
        return claims != null && claims.getUsername() != null ? claims.getUsername() : "";
    }
}
