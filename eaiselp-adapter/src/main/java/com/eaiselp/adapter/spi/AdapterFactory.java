package com.eaiselp.adapter.spi;

public interface AdapterFactory {
    GitAdapter getGitAdapter();
    /** 无参版：取默认档位（reasoning）的 LlmAdapter（向后兼容旧调用方）。 */
    LlmAdapter getLlmAdapter();
    DocStoreAdapter getDocStoreAdapter();

    /**
     * M2 SP-6（P8 解耦层）新增：按能力档位选 LlmAdapter。
     * 实现按 tier 查 t_model_routing 路由表，返回对应 provider 的 Adapter。
     * 默认实现回退到无参版，保持 SPI 向后兼容（其他 Factory 实现可不重写）。
     *
     * @param tier 能力档位：reasoning / structured / mechanical / code
     */
    default LlmAdapter getLlmAdapter(String tier) {
        return getLlmAdapter();
    }

    /**
     * M2 SP-6 新增：按档位 + 显式 provider 选 LlmAdapter（管理后台切换/灰度场景）。
     * 默认实现回退到 {@link #getLlmAdapter(String)}。
     *
     * @param tier     能力档位
     * @param provider LLM 厂商：glm / deepseek / qwen / ...
     */
    default LlmAdapter getLlmAdapter(String tier, String provider) {
        return getLlmAdapter(tier);
    }

    /**
     * M2 SP-6 新增：把能力档位解析为具体模型名（读 t_model_routing 路由表）。
     * 调用方（如 DerivationEngine）拿到具体模型名后传给 {@link LlmAdapter#invoke}。
     * 默认实现原样透传（其他 Factory 实现可不重写），保证向后兼容。
     *
     * @param tier 能力档位：reasoning / structured / mechanical / code（也兼容历史 opus/sonnet/haiku）
     * @return 具体模型名；路由表无匹配时原样返回 tier（由调用方兜底）
     */
    default String resolveModel(String tier) {
        return tier;
    }

    /**
     * EA 蓝图 §4.3 适配器体系扩展：新增 4 个企业适配器（Ticket/CICD/IM/MCP）。
     *
     * <p>均以 default 方法提供 SPI 框架扩展，默认实现抛 {@link UnsupportedOperationException}
     * 保持向后兼容——只有需要对应能力的 Factory 实现（如 {@code DefaultAdapterFactory}）才重写，
     * 其他 Factory 实现可继续不感知这些新适配器。
     *
     * <p>语义：返回首个 {@code isAvailable()} 的适配器；无可用时返回 null（区别于 Git/Llm/DocStore 的抛异常——
     * 这 4 个是企业可选能力，未接入时上层应能容忍 null 并走降级路径，而非中断派生）。
     */
    default TicketAdapter getTicketAdapter() {
        throw new UnsupportedOperationException("TicketAdapter 未装配");
    }

    default CICDAdapter getCICDAdapter() {
        throw new UnsupportedOperationException("CICDAdapter 未装配");
    }

    default IMAdapter getIMAdapter() {
        throw new UnsupportedOperationException("IMAdapter 未装配");
    }

    default MCPAdapter getMCPAdapter() {
        throw new UnsupportedOperationException("MCPAdapter 未装配");
    }
}
