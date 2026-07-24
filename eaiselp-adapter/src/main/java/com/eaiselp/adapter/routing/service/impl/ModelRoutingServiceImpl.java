package com.eaiselp.adapter.routing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.adapter.routing.entity.ModelRouting;
import com.eaiselp.adapter.routing.mapper.ModelRoutingMapper;
import com.eaiselp.adapter.routing.service.ModelRoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型路由服务实现（P8 解耦层）。
 *
 * <p>放 adapter 模块内部（非 data），遵循 ES-003 §9.2 / P3：避免 adapter→data 反向依赖。
 *
 * <p>查询走 MyBatis-Plus LambdaQueryWrapper；t_model_routing 为系统级表（已加 IGNORE_TABLES），
 * 多租户拦截器不会注入 tenant_id 条件。M2 暂不做缓存（ES-003 §9.2 验收点6 提到 M2 简单
 * ConcurrentHashMap+TTL 可后续加；当前单 case 派生调用频率不高，直查 DB 可接受）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRoutingServiceImpl implements ModelRoutingService {

    private final ModelRoutingMapper modelRoutingMapper;

    @Override
    public ModelRouting findBestByTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return null;
        }
        List<ModelRouting> list = modelRoutingMapper.selectList(
                new LambdaQueryWrapper<ModelRouting>()
                        .eq(ModelRouting::getTier, tier)
                        .eq(ModelRouting::getEnabled, 1)
                        .orderByAsc(ModelRouting::getPriority)
                        .last("LIMIT 1"));
        if (list == null || list.isEmpty()) {
            log.warn("[ModelRouting] tier={} 无可用路由（enabled=1 记录为空），调用方需兜底降级", tier);
            return null;
        }
        return list.get(0);
    }

    @Override
    public ModelRouting findByTierAndProvider(String tier, String provider) {
        if (tier == null || tier.isBlank() || provider == null || provider.isBlank()) {
            return null;
        }
        List<ModelRouting> list = modelRoutingMapper.selectList(
                new LambdaQueryWrapper<ModelRouting>()
                        .eq(ModelRouting::getTier, tier)
                        .eq(ModelRouting::getProvider, provider)
                        .eq(ModelRouting::getEnabled, 1)
                        .orderByAsc(ModelRouting::getPriority)
                        .last("LIMIT 1"));
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    @Override
    public List<ModelRouting> findAll() {
        return modelRoutingMapper.selectList(
                new LambdaQueryWrapper<ModelRouting>()
                        .orderByAsc(ModelRouting::getTier)
                        .orderByAsc(ModelRouting::getPriority));
    }
}
