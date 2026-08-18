package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 分层开关服务（PRJ-002 T12，F10）：读写 t_tenant 的 L3/L2 两开关列（SE 决策 D-4：开关是租户
 * 属性，t_tenant 加列而非独立配置表；L1 恒开不设列）。
 *
 * <p><b>读取语义（默认 true 兜底）</b>：租户不存在 / 列为 null（旧库未加列）/ 读取异常，
 * 一律视为该层启用——对应 DBA §5 回滚兼容承诺"读不到列 → 视为全开"，保证开关永不因数据问题
 * 误伤 L2/L3 功能入口。</p>
 *
 * <p><b>写入语义（AC-F10.3 开关可逆数据保留）</b>：{@link #setLayerEnabled} 只 UPDATE 开关列本身，
 * 不做任何数据清理/级联删除——关闭仅影响入口可见性（菜单隐藏 + API 业务码 43001/43002），
 * 重新开启后既有战略/项目群/项目/Case 关联数据原样恢复，进度无断档。</p>
 *
 * <p><b>缓存（SE §7.3）</b>：ConcurrentHashMap CacheAside、无 TTL、写后主动失效——开关读写比极低，
 * 管理员改完（PUT 成功即 evict）立即可见，无需过期时间。</p>
 *
 * <p>t_tenant 在 EaiselpTenantHandler.IGNORE_TABLES 中：selectById/update 按主键直查直改，
 * 天然无租户拦截器越权面；调用方显式传 tenantId，不依赖 TenantContext。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantLayerService {

    /** L3 战略层标识（对应 t_tenant.strategy_enabled） */
    public static final String LAYER_STRATEGY = "strategy";

    /** L2 项目群+项目层标识（一体开关，对应 t_tenant.program_project_enabled，PRD Q8） */
    public static final String LAYER_PROGRAM_PROJECT = "program_project";

    private final TenantMapper tenantMapper;

    /** 开关本地缓存：tenantId → 快照（CacheAside，写后失效，无 TTL） */
    private final ConcurrentHashMap<Long, LayerFlags> cache = new ConcurrentHashMap<>();

    /**
     * 查询租户 L3 战略层是否启用（默认 true 兜底，AC-F10.4 存量全开语义）。
     *
     * @param tenantId 租户 ID
     * @return true=启用；租户不存在/列 null/读取失败时同样返回 true（降级全开）
     */
    public boolean isStrategyEnabled(Long tenantId) {
        return loadFlags(tenantId).strategyEnabled();
    }

    /**
     * 查询租户 L2 项目群+项目层是否启用（默认 true 兜底）。
     *
     * @param tenantId 租户 ID
     * @return true=启用；降级语义同 {@link #isStrategyEnabled}
     */
    public boolean isProgramProjectEnabled(Long tenantId) {
        return loadFlags(tenantId).programProjectEnabled();
    }

    /**
     * 设置分层开关（AC-F10.3：只改开关列，数据全保留，可逆）。
     *
     * <p>写后主动失效本地缓存，管理员改完立即可见（LayerGuardInterceptor/菜单渲染即时生效）。
     * 关层语义 = 入口拒绝（业务码 43001/43002 由 Controller/拦截器层输出），不做数据清理。</p>
     *
     * @param tenantId 租户 ID
     * @param layer    层标识：{@link #LAYER_STRATEGY} / {@link #LAYER_PROGRAM_PROJECT}
     * @param enabled  true=启用 false=关闭
     * @throws IllegalArgumentException layer 不认识时（防拼写错误静默无效）
     */
    public void setLayerEnabled(Long tenantId, String layer, boolean enabled) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        LambdaUpdateWrapper<Tenant> uw = new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, tenantId);
        switch (layer == null ? "" : layer) {
            case LAYER_STRATEGY -> uw.set(Tenant::getStrategyEnabled, enabled);
            case LAYER_PROGRAM_PROJECT -> uw.set(Tenant::getProgramProjectEnabled, enabled);
            default -> throw new IllegalArgumentException("未知的分层开关标识: " + layer
                    + "（应为 " + LAYER_STRATEGY + " 或 " + LAYER_PROGRAM_PROJECT + "）");
        }
        tenantMapper.update(null, uw);
        cache.remove(tenantId);   // 写后主动失效：下次读回源 DB，保证改完立即可见
        log.info("[Layer] 分层开关更新 tenantId={}, layer={}, enabled={}（数据保留，可逆）",
                tenantId, layer, enabled);
    }

    /**
     * 读取（并缓存）租户开关快照：miss 回源 selectById，任何异常/缺失降级为全开。
     *
     * <p>注：t_tenant 在 IGNORE_TABLES 中免租户过滤，本查询按主键直查、与 TenantContext 无关。</p>
     */
    private LayerFlags loadFlags(Long tenantId) {
        if (tenantId == null) {
            return LayerFlags.ALL_ON;
        }
        return cache.computeIfAbsent(tenantId, id -> {
            try {
                Tenant t = tenantMapper.selectById(id);
                if (t == null) {
                    log.warn("[Layer] 租户不存在，分层开关降级为全开 tenantId={}", id);
                    return LayerFlags.ALL_ON;
                }
                // 列 null（旧库未加列/回滚后）同样视为启用——DBA §5 兼容兜底
                return new LayerFlags(
                        t.getStrategyEnabled() == null || t.getStrategyEnabled(),
                        t.getProgramProjectEnabled() == null || t.getProgramProjectEnabled());
            } catch (Exception e) {
                log.warn("[Layer] 分层开关读取失败，降级为全开 tenantId={}: {}", id, e.getMessage());
                return LayerFlags.ALL_ON;
            }
        });
    }

    /** 一次读库的两开关快照（不可变；ALL_ON 为降级兜底值）。 */
    private record LayerFlags(boolean strategyEnabled, boolean programProjectEnabled) {
        static final LayerFlags ALL_ON = new LayerFlags(true, true);
    }
}
