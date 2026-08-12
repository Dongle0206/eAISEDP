package com.eaiselp.adapter.routing.service.impl;

import com.eaiselp.adapter.routing.entity.ModelRouting;
import com.eaiselp.adapter.routing.mapper.ModelRoutingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ModelRoutingServiceImpl 单测（P8 解耦层：tier→具体模型路由）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>findBestByTier：正常返回优先级最高 / 空结果返回 null / tier 空值防护</li>
 *   <li>findByTierAndProvider：精确匹配 / 空值防护</li>
 *   <li>findAll：全量返回</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ModelRoutingServiceImplTest {

    @Mock ModelRoutingMapper modelRoutingMapper;

    @InjectMocks ModelRoutingServiceImpl routingService;

    private ModelRouting routing(String tier, String provider, String model, int priority, int enabled) {
        ModelRouting r = new ModelRouting();
        r.setTier(tier);
        r.setProvider(provider);
        r.setModel(model);
        r.setPriority(priority);
        r.setEnabled(enabled);
        return r;
    }

    // ===== findBestByTier =====

    @Test
    void findBestByTier_正常返回优先级最高的() {
        when(modelRoutingMapper.selectList(any())).thenReturn(List.of(
                routing("opus", "glm", "glm-4-plus", 1, 1),
                routing("opus", "deepseek", "deepseek-chat", 2, 1)
        ));

        ModelRouting best = routingService.findBestByTier("opus");

        assertNotNull(best);
        assertEquals("glm", best.getProvider());
        assertEquals("glm-4-plus", best.getModel());
        assertEquals(1, best.getPriority(), "应返回 priority 最小的（最高优先级）");
    }

    @Test
    void findBestByTier_无可用路由_返回null() {
        when(modelRoutingMapper.selectList(any())).thenReturn(Collections.emptyList());

        ModelRouting best = routingService.findBestByTier("unknown-tier");
        assertNull(best);
    }

    @Test
    void findBestByTier_mapper返回null_返回null() {
        when(modelRoutingMapper.selectList(any())).thenReturn(null);

        assertNull(routingService.findBestByTier("opus"));
    }

    @Test
    void findBestByTier_tier为null_返回null() {
        assertNull(routingService.findBestByTier(null));
    }

    @Test
    void findBestByTier_tier为空字符串_返回null() {
        assertNull(routingService.findBestByTier(""));
    }

    @Test
    void findBestByTier_tier为纯空格_返回null() {
        assertNull(routingService.findBestByTier("   "));
    }

    // ===== findByTierAndProvider =====

    @Test
    void findByTierAndProvider_精确匹配_返回结果() {
        when(modelRoutingMapper.selectList(any())).thenReturn(List.of(
                routing("sonnet", "glm", "glm-4-air", 1, 1)
        ));

        ModelRouting r = routingService.findByTierAndProvider("sonnet", "glm");

        assertNotNull(r);
        assertEquals("glm-4-air", r.getModel());
    }

    @Test
    void findByTierAndProvider_无匹配_返回null() {
        when(modelRoutingMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertNull(routingService.findByTierAndProvider("sonnet", "nonexistent"));
    }

    @Test
    void findByTierAndProvider_tier为空_返回null() {
        assertNull(routingService.findByTierAndProvider("", "glm"));
    }

    @Test
    void findByTierAndProvider_provider为空_返回null() {
        assertNull(routingService.findByTierAndProvider("sonnet", ""));
    }

    @Test
    void findByTierAndProvider_都为null_返回null() {
        assertNull(routingService.findByTierAndProvider(null, null));
    }

    // ===== findAll =====

    @Test
    void findAll_正常返回全量列表() {
        when(modelRoutingMapper.selectList(any())).thenReturn(List.of(
                routing("haiku", "glm", "glm-4-flash", 1, 1),
                routing("opus", "glm", "glm-4-plus", 1, 1),
                routing("sonnet", "glm", "glm-4-air", 1, 1)
        ));

        List<ModelRouting> all = routingService.findAll();

        assertEquals(3, all.size());
    }

    @Test
    void findAll_无数据_返回空列表() {
        when(modelRoutingMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<ModelRouting> all = routingService.findAll();

        assertNotNull(all);
        assertTrue(all.isEmpty());
    }
}
