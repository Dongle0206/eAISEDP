package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.eaiselp.common.dto.TrialTipVo;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.TenantSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 租户订阅判定实现（case-20260820 F3，T18）。口径见接口 Javadoc 与 PRD §4.3.1（唯一权威）。
 *
 * <p><b>零缓存</b>：所有判定直查 t_tenant（主键单查，t_tenant 在租户拦截器 IGNORE_TABLES，
 * 登录链路无 TenantContext 也可查）——恢复路径改库后下次判定即生效（AC-F3.6）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantSubscriptionServiceImpl implements TenantSubscriptionService {

    private final TenantMapper tenantMapper;
    private final AuditService auditService;

    private static final String EDITION_TRIAL = "trial";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 提示窗口 7×24h（PRD §4.3.1：(expire−now) > 7×24h 无提示） */
    private static final long TIP_WINDOW_MILLIS = 7L * 24 * 3600 * 1000;
    private static final long ONE_DAY_MILLIS = 24L * 3600 * 1000;

    /** edition 展示名（领域数据字典，P6 裁决允许的应用层常量集） */
    private static final Map<String, String> EDITION_NAMES = Map.of(
            "trial", "试用版",
            "pro", "专业版",
            "enterprise", "企业版",
            "starter", "入门版");

    // ==================== 到期校验（登录/派生入口） ====================

    @Override
    public void assertNotExpired(Long tenantId) {
        if (tenantId == null) {
            // 防御：无租户上下文不因本校验加严而拒绝（登录链路 tenant 缺失另有兜底）
            log.warn("[Subscription] 到期校验缺 tenantId，跳过（防御放行）");
            return;
        }
        assertNotExpired(tenantMapper.selectById(tenantId));
    }

    @Override
    public void assertNotExpired(Tenant tenant) {
        if (tenant == null) {
            log.warn("[Subscription] 租户不存在，跳过到期校验（防御放行）");
            return;
        }
        // 豁免：仅 trial 执行到期校验，expire_time 完全忽略（AC-F3.3）
        if (!EDITION_TRIAL.equalsIgnoreCase(tenant.getEdition())) {
            return;
        }
        // NULL 防御：trial 且 expire=NULL 视为未设置到期，不拦截（Q4，AC-F3.4）
        if (tenant.getExpireTime() == null) {
            log.warn("[Subscription] trial 租户 expire_time 为 NULL（脏数据防御，Q4），放行: tenantId={}",
                    tenant.getId());
            return;
        }
        // 到期判定：now ≥ expire（时刻精确，含等于）
        if (!LocalDateTime.now().isBefore(tenant.getExpireTime())) {
            throw new BizException(ResultCode.TRIAL_EXPIRED,
                    "试用已到期，请联系平台管理员升级（platform_admin 可通过订阅管理接口延期/转正）");
        }
    }

    // ==================== 临期提示（登录成功路径） ====================

    @Override
    public TrialTipVo buildTrialTip(Tenant tenant) {
        if (tenant == null
                || !EDITION_TRIAL.equalsIgnoreCase(tenant.getEdition())
                || tenant.getExpireTime() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expire = tenant.getExpireTime();
        // 已到期：登录链路已被 assertNotExpired 拦截，此处防御不提示
        if (!now.isBefore(expire)) {
            return null;
        }
        long millisLeft = Duration.between(now, expire).toMillis();
        // (expire−now) > 7×24h → 无提示（AC-F3.2 T8 例）
        if (millisLeft > TIP_WINDOW_MILLIS) {
            return null;
        }
        // N = ceil((expire−now)/24h)，向上取整（不满一天算 1 天）
        int daysLeft = (int) Math.ceil(millisLeft / (double) ONE_DAY_MILLIS);
        String level;
        if (daysLeft == 1) {
            level = TrialTipVo.LEVEL_CRITICAL;   // N=1 红色优先（同时落在 (1,3]，红色优先）
        } else if (daysLeft <= 3) {
            level = TrialTipVo.LEVEL_WARNING;    // 2≤N≤3 黄
        } else {
            level = TrialTipVo.LEVEL_NORMAL;     // 4≤N≤7 蓝
        }
        return TrialTipVo.builder()
                .daysLeft(daysLeft)
                .level(level)
                .expireTime(expire.format(FORMATTER))
                .build();
    }

    // ==================== 订阅状态查询（U1） ====================

    @Override
    public SubscriptionStatus getSubscriptionStatus(Long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        return buildStatus(tenant);
    }

    // ==================== 订阅修改（U2 恢复路径） ====================

    @Override
    public SubscriptionStatus updateSubscription(Long tenantId, String edition, String expireTime) {
        if (edition == null && expireTime == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "edition 与 expireTime 至少提供一个（null=不变）");
        }
        if (edition != null && !SUPPORTED_EDITIONS.contains(edition)) {
            throw new BizException(400, "edition 非法: " + edition + "，合法值: " + SUPPORTED_EDITIONS);
        }
        // expireTime：null=不变；空串=置空（Q4"未设置"）；否则解析 yyyy-MM-dd HH:mm:ss
        boolean clearExpire = false;
        LocalDateTime newExpire = null;
        if (expireTime != null) {
            if (expireTime.isBlank()) {
                clearExpire = true;
            } else {
                try {
                    newExpire = LocalDateTime.parse(expireTime.trim(), FORMATTER);
                } catch (DateTimeParseException e) {
                    throw new BizException(ResultCode.BAD_REQUEST,
                            "expireTime 格式必须为 yyyy-MM-dd HH:mm:ss: " + expireTime);
                }
            }
        }

        Tenant old = requireTenant(tenantId);

        // 精确列级更新（expire 置空必须走 UpdateWrapper——MP updateById 默认忽略 null 字段）
        LambdaUpdateWrapper<Tenant> uw = new LambdaUpdateWrapper<Tenant>().eq(Tenant::getId, tenantId);
        if (edition != null) {
            uw.set(Tenant::getEdition, edition);
        }
        if (clearExpire) {
            uw.set(Tenant::getExpireTime, null);
        } else if (newExpire != null) {
            uw.set(Tenant::getExpireTime, newExpire);
        }
        tenantMapper.update(null, uw);

        // 审计 tenant_edition_change（AC-F3.6：detail 含 old→new 与操作者）
        String operator = currentUsername();
        String newEditionVal = edition != null ? edition : old.getEdition();
        String newExpireVal = clearExpire ? "" :
                (newExpire != null ? newExpire.format(FORMATTER)
                        : (old.getExpireTime() != null ? old.getExpireTime().format(FORMATTER) : ""));
        String oldExpireVal = old.getExpireTime() != null ? old.getExpireTime().format(FORMATTER) : "";
        auditService.log("tenant_edition_change", "tenant", String.valueOf(tenantId),
                "{\"oldEdition\":\"" + safeJson(old.getEdition()) + "\",\"newEdition\":\"" + safeJson(newEditionVal)
                        + "\",\"oldExpireTime\":\"" + oldExpireVal
                        + "\",\"newExpireTime\":\"" + newExpireVal
                        + "\",\"operator\":\"" + safeJson(operator) + "\"}");
        log.info("[Subscription] 租户订阅变更: tenantId={}, {} -> {}, expire {} -> {}, operator={}",
                tenantId, old.getEdition(), newEditionVal, oldExpireVal, newExpireVal, operator);

        Tenant updated = new Tenant();
        updated.setId(tenantId);
        updated.setEdition(newEditionVal);
        updated.setExpireTime(clearExpire ? null : (newExpire != null ? newExpire : old.getExpireTime()));
        return buildStatus(updated);
    }

    @Override
    public String editionDisplayName(String edition) {
        if (edition == null) return null;
        return EDITION_NAMES.getOrDefault(edition, edition);
    }

    // ==================== 内部 ====================

    private Tenant requireTenant(Long tenantId) {
        Tenant tenant = tenantId != null ? tenantMapper.selectById(tenantId) : null;
        if (tenant == null) {
            throw new BizException(ResultCode.NOT_FOUND, "租户不存在: " + tenantId);
        }
        return tenant;
    }

    private SubscriptionStatus buildStatus(Tenant tenant) {
        boolean trial = EDITION_TRIAL.equalsIgnoreCase(tenant.getEdition());
        boolean expired = false;
        Integer daysLeft = null;
        if (trial && tenant.getExpireTime() != null) {
            LocalDateTime now = LocalDateTime.now();
            expired = !now.isBefore(tenant.getExpireTime());
            // daysLeft 与 buildTrialTip 同 ceil 口径（U1 契约：与登录口径同源）
            daysLeft = expired ? 0 : (int) Math.ceil(
                    Duration.between(now, tenant.getExpireTime()).toMillis() / (double) ONE_DAY_MILLIS);
        }
        return SubscriptionStatus.builder()
                .edition(tenant.getEdition())
                .editionName(editionDisplayName(tenant.getEdition()))
                .expireTime(tenant.getExpireTime() != null ? tenant.getExpireTime().format(FORMATTER) : null)
                .daysLeft(daysLeft)
                .expired(expired)
                .trial(trial)
                .build();
    }

    private String currentUsername() {
        JwtClaims claims = LoginUser.get();
        return claims != null && claims.getUsername() != null ? claims.getUsername() : "";
    }

    /** 转义 JSON 字符串（审计 detail 防注入，同 AuthController.safeJson）。 */
    private String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
