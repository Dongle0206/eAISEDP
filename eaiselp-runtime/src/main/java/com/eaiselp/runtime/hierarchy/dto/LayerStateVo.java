package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

/**
 * 租户分层开关状态 VO（PRJ-002 T28，SE §8.2 GET/PUT /api/v1/tenant/layers 响应契约）。
 *
 * <p>GET 全角色可读（登录即可，菜单渲染需要，T29）；PUT 需 tenant:layer:edit。
 * 两开关默认 true（AC-F10.4 存量全开）；关闭仅影响入口可见性，数据保留可逆（AC-F10.3）。</p>
 */
@Data
public class LayerStateVo {

    /** L3 战略层开关（false → /api/v1/strategies/** 返回 43001） */
    private Boolean strategyEnabled;

    /** L2 项目群+项目层一体开关（PRD Q8；false → /api/v1/programs/**、/api/v1/projects/** 返回 43002） */
    private Boolean programProjectEnabled;
}
