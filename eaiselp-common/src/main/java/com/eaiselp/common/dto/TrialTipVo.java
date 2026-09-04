package com.eaiselp.common.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 试用临期提示（case-20260820 F3，T17）。
 *
 * <p>登录成功响应 {@code LoginResponse.trialTip} 的载体，口径见 PRD §4.3.1（唯一权威）：
 * 仅 trial 版且未到期且 {@code (expire_time − now) ≤ 7×24h} 时返回，否则为 null（序列化时字段不出现）。</p>
 *
 * <p><b>放置位置（BA 拆解声明 3 定稿）</b>：落 eaiselp-common 而非 eaiselp-auth——
 * {@code TenantSubscriptionService}（eaiselp-data）返回本类型，auth（LoginResponse）与 data 双方消费，
 * 共同下游只有 common；放 auth 会导致 data→auth 反向依赖，违反 ADR-001 P3。</p>
 */
@Data
@Builder
public class TrialTipVo {

    /** 剩余天数 N = ceil((expire_time − now) / 24h)，向上取整（不满一天算 1 天） */
    private int daysLeft;

    /** 三档：critical（N=1，红色优先）/ warning（2≤N≤3）/ normal（4≤N≤7） */
    private String level;

    /** 到期时间原值回显，格式 yyyy-MM-dd HH:mm:ss */
    private String expireTime;

    public static final String LEVEL_NORMAL = "normal";
    public static final String LEVEL_WARNING = "warning";
    public static final String LEVEL_CRITICAL = "critical";
}
