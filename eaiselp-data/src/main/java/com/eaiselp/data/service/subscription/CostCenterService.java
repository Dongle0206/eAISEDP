package com.eaiselp.data.service.subscription;

import com.eaiselp.data.service.subscription.dto.CostCenterVo;

/**
 * 费用中心汇总服务（case-20260823-商用化 T10；C1 数据支撑）。
 *
 * <p>C2/C3（租户账单 + draft 隔离）在 {@link InvoiceService}；本服务只承载 C1 聚合读：
 * t_quota 当期行实时水位（<b>仅展示非账单口径</b>，B5）+ 当前套餐卡（t_tenant.plan_code →
 * t_plan）。信息隔离（§8.4）：任何字段禁止承载 t_derivation.cost。</p>
 */
public interface CostCenterService {

    /**
     * C1 本期用量与当前套餐（tenant_id 显式传参，Controller 从 TenantContext 取——G13）。
     * trial/未绑定套餐 → plan=null；t_quota 当期行不存在 → quota=null。
     */
    CostCenterVo summary(Long tenantId);
}
