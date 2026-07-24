package com.eaiselp.adapter.routing.service;

import com.eaiselp.adapter.routing.entity.ModelRouting;

import java.util.List;

/**
 * 模型路由服务（P8 解耦层 / model-registry skill 落地）。
 *
 * <p>放 adapter 模块内部（非 data），遵循 ES-003 §9.2 / P3：避免 adapter→data 反向依赖。
 * 由 {@link com.eaiselp.adapter.factory.DefaultAdapterFactory} 调用，按 tier 选 provider + model。
 *
 * <p>换模型/换厂商流程：UPDATE t_model_routing 一行 → 刷新缓存/重启 → 平台代码零改动（ES-003 §2.6）。
 */
public interface ModelRoutingService {

    /**
     * 按能力档位查最优路由：enabled=1 的记录按 priority 升序取第一条。
     *
     * @param tier 能力档位：reasoning / structured / mechanical / code
     * @return 优先级最高的可用路由；该档位无可用记录时返回 null（由调用方兜底降级）
     */
    ModelRouting findBestByTier(String tier);

    /**
     * 按档位 + 指定 provider 查路由（用于显式指定厂商的场景，如管理后台切换/灰度）。
     *
     * @param tier     能力档位
     * @param provider LLM 厂商：glm / deepseek / qwen / ...
     * @return 匹配且 enabled=1 的路由；无匹配返回 null
     */
    ModelRouting findByTierAndProvider(String tier, String provider);

    /**
     * 查全部路由（管理界面展示用）。
     *
     * @return 全部路由记录（按 tier、priority 升序）
     */
    List<ModelRouting> findAll();
}
