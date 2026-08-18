package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.TenantMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TenantLayerService 单测（AC-F10 分层开关核心语义，PRD 验收基线）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>读取默认 true 三重兜底：租户不存在 / 开关列为 null（旧库未加列）/ 读取异常 → 一律视为全开
 *       （AC-F10.4 存量租户升级语义 + DBA §5 回滚兼容承诺）</li>
 *   <li>正常读取返回配置值 + 本地缓存（同一租户重复读只查一次库）</li>
 *   <li>setLayerEnabled 只 UPDATE 开关列（数据保留可逆，AC-F10.3）+ 写后缓存失效立即生效</li>
 *   <li>非法入参防御（tenantId 空 / 未知 layer 标识）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TenantLayerServiceTest {

    @Mock TenantMapper tenantMapper;

    @InjectMocks
    TenantLayerService service;

    @BeforeAll
    static void initLambdaCache() {
        // LambdaUpdateWrapper<Tenant> 的 set 列解析需 TableInfo（纯 Mockito 无 Spring）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Tenant.class);
    }

    // ===== 默认 true 三重兜底 =====

    @Test
    void 租户不存在_两开关兜底全开() {
        when(tenantMapper.selectById(1L)).thenReturn(null);

        assertTrue(service.isStrategyEnabled(1L), "租户不存在 → L3 兜底为启用");
        assertTrue(service.isProgramProjectEnabled(1L), "租户不存在 → L2 兜底为启用");
    }

    @Test
    void 开关列为null_视为启用() {
        Tenant t = new Tenant();   // strategyEnabled/programProjectEnabled 均为 null（旧库未加列/回滚后）
        when(tenantMapper.selectById(1L)).thenReturn(t);

        assertTrue(service.isStrategyEnabled(1L), "列 null → L3 兜底为启用（DBA §5 兼容承诺）");
        assertTrue(service.isProgramProjectEnabled(1L), "列 null → L2 兜底为启用");
    }

    @Test
    void 读取异常_兜底全开() {
        when(tenantMapper.selectById(1L)).thenThrow(new RuntimeException("mock: db down"));

        assertAll(
                () -> assertTrue(service.isStrategyEnabled(1L), "读取异常 → L3 兜底为启用"),
                () -> assertTrue(service.isProgramProjectEnabled(1L), "读取异常 → L2 兜底为启用"));
    }

    // ===== 正常读取 + 缓存 =====

    @Test
    void 正常读取返回配置值_本地缓存重复读只查一次库() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(false, true));

        assertFalse(service.isStrategyEnabled(1L), "读到的真实配置：L3 关");
        assertTrue(service.isProgramProjectEnabled(1L), "读到的真实配置：L2 开");

        assertFalse(service.isStrategyEnabled(1L), "第二次读走缓存，值不变：L3 仍为关");
        // CacheAside：同租户重复读只查一次库
        verify(tenantMapper, times(1)).selectById(1L);
    }

    // ===== setLayerEnabled（AC-F10.3 开关可逆、数据保留） =====

    @Test
    void setLayerEnabled_L3开关_只更新开关列_缓存失效后立即生效() {
        // 第一次读到 strategy=true（已缓存）；关闭后 evict，第二次读到 DB 新值 false
        when(tenantMapper.selectById(1L)).thenReturn(tenant(true, true), tenant(false, true));

        assertTrue(service.isStrategyEnabled(1L), "改前：L3 开");

        service.setLayerEnabled(1L, TenantLayerService.LAYER_STRATEGY, false);

        ArgumentCaptor<LambdaUpdateWrapper<Tenant>> captor = wrapperCaptor();
        verify(tenantMapper, times(1)).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("strategy_enabled"), "UPDATE 必须命中开关列");
        assertFalse(sqlSet.contains("program_project_enabled"), "只动本层开关列，不误伤另一层");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(false), "set 值=false");

        assertFalse(service.isStrategyEnabled(1L), "写后主动失效缓存 → 改完立即可见");
        verify(tenantMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(tenantMapper, never()).delete(any());   // 数据保留：关层不做任何清理
    }

    @Test
    void setLayerEnabled_L2开关_命中program_project_enabled列() {
        service.setLayerEnabled(1L, TenantLayerService.LAYER_PROGRAM_PROJECT, false);

        ArgumentCaptor<LambdaUpdateWrapper<Tenant>> captor = wrapperCaptor();
        verify(tenantMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("program_project_enabled"));
    }

    // ===== 入参防御 =====

    @Test
    void setLayerEnabled_未知layer标识_拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setLayerEnabled(1L, "program", false),
                "未知标识必须拒绝（防拼写错误静默无效）");
        verifyNoInteractions(tenantMapper);
    }

    @Test
    void setLayerEnabled_tenantId为空_拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setLayerEnabled(null, TenantLayerService.LAYER_STRATEGY, false));
        verifyNoInteractions(tenantMapper);
    }

    // ===== 辅助 =====

    private Tenant tenant(boolean strategy, boolean programProject) {
        Tenant t = new Tenant();
        t.setId(1L);
        t.setStrategyEnabled(strategy);
        t.setProgramProjectEnabled(programProject);
        return t;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<Tenant>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }
}
