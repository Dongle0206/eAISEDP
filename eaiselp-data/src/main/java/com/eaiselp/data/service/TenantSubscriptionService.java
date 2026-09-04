package com.eaiselp.data.service;

import com.eaiselp.common.dto.TrialTipVo;
import com.eaiselp.data.entity.Tenant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 租户订阅判定核心（case-20260820 F3，T18；SE 方案 §4.1 / D-4）。
 *
 * <p><b>唯一口径</b>：到期判定/临期提示全部按 PRD §4.3.1 实现，禁止自造口径——</p>
 * <ul>
 *   <li>到期判定：{@code edition=trial 且 expire_time 非空 且 now ≥ expire_time}（时刻精确，含等于）
 *       → 抛 {@code BizException(40003, "试用已到期…")}；</li>
 *   <li>豁免：edition ≠ trial 直接放行（expire_time 完全忽略，AC-F3.3）；</li>
 *   <li>NULL 防御：trial 且 expire_time=NULL → WARN 日志后放行（Q4 脏数据防御，AC-F3.4）；</li>
 *   <li>提示窗口：未到期且 {@code (expire − now) ≤ 7×24h}；剩余天数 {@code N = ceil((expire−now)/24h)}
 *       向上取整；三档 critical（N=1，红色优先）/ warning（2≤N≤3）/ normal（4≤N≤7）。</li>
 * </ul>
 *
 * <p><b>不做任何缓存</b>：恢复路径（改 edition/expire_time）后下一次登录/派生即生效
 * （AC-F3.6），主键单查对 P95 影响可忽略（PRD §6.5）。</p>
 *
 * <p><b>放置位置</b>：eaiselp-data（library）——auth 与 runtime 两个 service 模块共同消费
 * （SE D-4：auth 不依赖 runtime，data 是双方共同下游；PermissionService 先例）。</p>
 */
public interface TenantSubscriptionService {

    /** U2 入参合法 edition 值集（trial/pro/enterprise/starter，SE §4.4）。 */
    Set<String> SUPPORTED_EDITIONS = Set.of("trial", "pro", "enterprise", "starter");

    /**
     * 到期校验（登录/派生入口调用）：内部 selectById(t_tenant) 主键单查，无缓存。
     *
     * @param tenantId 租户 ID（登录链路传用户实际租户；派生入口传 TenantContext）
     */
    void assertNotExpired(Long tenantId);

    /**
     * 到期校验（已查出的 Tenant 行直传，避免登录链路二次查询——原⑥租户查询已提前合并）。
     * tenant 为 null 时防御放行（登录链路 tenant 缺失另有兜底语义，不因校验加严锁死）。
     */
    void assertNotExpired(Tenant tenant);

    /**
     * 登录成功路径的临期提示。非 trial / expire NULL / (expire−now) &gt; 7×24h / 已到期 → 返回 null。
     */
    TrialTipVo buildTrialTip(Tenant tenant);

    /**
     * 订阅状态快照（U1 数据源）：daysLeft/expired 与登录口径同源（本接口唯一口径）。
     *
     * @param tenantId 当前租户（TenantContext）
     * @throws com.eaiselp.common.exception.BizException 40400 租户不存在
     */
    SubscriptionStatus getSubscriptionStatus(Long tenantId);

    /**
     * 修改租户订阅（U2 恢复路径，platform_admin 专属——角色校验在 Controller 应用层）。
     * null=不变支持单字段更新；expireTime 传空串=置空（Q4"未设置"语义）；
     * 非法 edition / 非法时间格式 → 400/40000；租户不存在 → 40400。
     * 写审计 tenant_edition_change（detail 含 old→new 与操作者）。
     *
     * @return 修改后回显（同 U1 结构）
     */
    SubscriptionStatus updateSubscription(Long tenantId, String edition, String expireTime);

    /** edition 展示名（U1 editionName）：不在 SUPPORTED_EDITIONS 内返回原值。 */
    String editionDisplayName(String edition);

    /** 订阅状态（U1/U2 出参结构，契约 AC-U1）。 */
    @Data
    @Builder
    @AllArgsConstructor
    class SubscriptionStatus {
        private String edition;
        private String editionName;
        /** yyyy-MM-dd HH:mm:ss 或 null（未设置） */
        private String expireTime;
        /** 剩余天数 ceil 口径；仅 trial+未到期+expire 非空时有值（已到期=0，非 trial=null） */
        private Integer daysLeft;
        /** 是否已到期（仅 trial 语义；非 trial 恒 false） */
        private boolean expired;
        /** edition 是否为 trial */
        private boolean trial;
    }
}
