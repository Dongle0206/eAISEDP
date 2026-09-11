package com.eaiselp.data.service.subscription;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.data.service.subscription.dto.TenantItemVo;

/**
 * 平台侧租户列表查询（case-20260823-商用化 编排者追加；GET /api/v1/tenants 数据支撑）。
 *
 * <p>t_tenant 在 EaiselpTenantHandler.IGNORE_TABLES（按 id 直查先例）——本服务查询不受租户
 * 拦截器改写，天然跨租户（平台管理通道，与 U2 同源）；<b>只读服务，审计只读不打点</b>
 * （编排者追加裁决）。daysLeft 与 {@link com.eaiselp.data.service.TenantSubscriptionService}
 * 登录提示同 ceil 口径（N = ceil((expire−now)/24h)，仅 trial+未到期+expire 非空）。</p>
 */
public interface TenantQueryService {

    /**
     * 租户分页：keyword 模糊匹配企业名（tenant_name）/编码（tenant_code），默认 id DESC。
     *
     * @param keyword 关键字（null/空白 = 不筛选）
     */
    IPage<TenantItemVo> pageTenants(String keyword, long page, long size);
}
