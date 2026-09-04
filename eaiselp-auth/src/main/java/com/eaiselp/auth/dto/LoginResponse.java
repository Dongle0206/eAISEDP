package com.eaiselp.auth.dto;

import com.eaiselp.common.dto.TrialTipVo;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String token;
    private long expiresIn;     // 有效期秒数（=86400）
    private UserInfo user;

    /**
     * 试用临期提示（case-20260820 F3，T17）：非 trial / 无临期（(expire−now) > 7×24h）时为 null，
     * 序列化时字段不出现（加可空字段=非破坏，不升 v2，G14 版本兼容规则）。
     * 口径由 TenantSubscriptionService.buildTrialTip 统一计算（PRD §4.3.1 唯一口径）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TrialTipVo trialTip;
}
