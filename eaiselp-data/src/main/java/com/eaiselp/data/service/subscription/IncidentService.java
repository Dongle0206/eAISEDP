package com.eaiselp.data.service.subscription;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.data.entity.Incident;
import com.eaiselp.data.service.subscription.dto.IncidentVo;

/**
 * 事故台账服务（case-20260823-商用化 T11；N1~N4，契约 api-contracts.md §5）。
 *
 * <p><b>轻量无状态机</b>（PRD §4.8 裁决）：恢复态为展示口径（recovery_time 非空=已恢复）；
 * 编辑校验 recovery_time ≥ occurred_time（倒挂 400，SE 防脏数据补充）。
 * slaBreach 登记时人工违约判定（可选缺省 0，纯台账不触发计算，D-4）。
 * 无 DELETE 端点（D-14 台账留痕）。</p>
 *
 * <p><b>租户隔离由拦截器承载</b>（AC-F3.5）：Service 查询按 TenantContext 自动过滤；
 * 写入 tenant_id 由拦截器 INSERT 注入（Controller 从 TenantContext/LoginUser 取，G13）。</p>
 */
public interface IncidentService {

    /**
     * N1 列表：默认 occurred_time DESC（AC-F3.3 倒序断言）；recovered 筛选
     * （true=已恢复 recovery_time 非空 / false=未恢复 IS NULL）。
     */
    IPage<IncidentVo> pageFilter(Boolean recovered, long page, long size);

    /**
     * N2 登记：title/occurredTime/impactDesc 必填（缺 400）；recoveryTime 非空时须
     * ≥ occurredTime（倒挂 400）；审计 incident_create。
     */
    IncidentVo create(Incident incident);

    /** N3 详情（他租户/不存在 40400——拦截器隔离之外的直查兜底）。 */
    IncidentVo detail(Long id);

    /**
     * N4 全量编辑（补记 recoveryTime/rootCause 主场景，AC-F3.2：补记前"未恢复"/
     * 补记后"已恢复"由 recovered 承载）；审计 incident_update detail 含 old→new。
     */
    IncidentVo update(Long id, Incident patch);
}
