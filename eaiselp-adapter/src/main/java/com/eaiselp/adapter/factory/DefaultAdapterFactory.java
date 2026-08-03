package com.eaiselp.adapter.factory;

import com.eaiselp.adapter.routing.entity.ModelRouting;
import com.eaiselp.adapter.routing.service.ModelRoutingService;
import com.eaiselp.adapter.spi.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认适配器工厂。
 *
 * <p>M2 SP-6（P8 解耦层）重构：LLM 选型从"固定返回 GlmLlmAdapter"改为"按 tier 查 t_model_routing 路由表选 provider 对应 Adapter"。
 * ModelRoutingService 放 adapter 模块内部（同模块注入，无跨模块反向依赖，ES-003 §9.2/P3）。
 *
 * <p>多 provider 支持：注入所有 LlmAdapter Bean（glm 始终装配；deepseek 条件装配），
 * 按 {@link LlmAdapter#getProvider()} 建索引。选型优先级：路由表 priority → adapter.isAvailable() 兜底。
 */
@Slf4j
@Component
public class DefaultAdapterFactory implements AdapterFactory {

    /** 默认档位（无参 getLlmAdapter 用），= 复杂决策档，保证旧调用方拿到高质量模型。 */
    private static final String DEFAULT_TIER = "reasoning";

    private final List<GitAdapter> gitAdapters;
    private final List<DocStoreAdapter> docStoreAdapters;

    /** provider → 该 provider 的 Adapter（按 getProvider() 建索引，便于按路由结果选型）。 */
    private final Map<String, LlmAdapter> llmAdapterByProvider;

    private final ModelRoutingService modelRoutingService;

    /**
     * EA 蓝图 §4.3 企业适配器（Ticket/CICD/IM/MCP）——可选注入。
     *
     * <p>用 {@code @Autowired(required=false)}：这 4 类是企业可选能力，stub 默认实现靠
     * {@code @ConditionalOnProperty(enabled=true)} 控制装配，多数环境装配量为 0。
     * 此处容忍 null，{@code get*Adapter()} 返回 null 让上层走降级路径。
     */
    @Autowired(required = false)
    private List<TicketAdapter> ticketAdapters;
    @Autowired(required = false)
    private List<CICDAdapter> cicdAdapters;
    @Autowired(required = false)
    private List<IMAdapter> imAdapters;
    @Autowired(required = false)
    private List<MCPAdapter> mcpAdapters;

    public DefaultAdapterFactory(List<GitAdapter> g, List<LlmAdapter> l, List<DocStoreAdapter> d,
                                 ModelRoutingService modelRoutingService) {
        this.gitAdapters = g;
        this.docStoreAdapters = d;
        this.modelRoutingService = modelRoutingService;
        // 建 provider 索引（保留插入顺序，便于兜底时稳定取第一个）
        Map<String, LlmAdapter> idx = new LinkedHashMap<>();
        for (LlmAdapter a : l) {
            idx.putIfAbsent(a.getProvider(), a);
        }
        this.llmAdapterByProvider = idx;
    }

    @Override public GitAdapter getGitAdapter() {
        return gitAdapters.stream().filter(GitAdapter::isAvailable).findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用 GitAdapter"));
    }

    /** 无参版：默认档位（reasoning）。 */
    @Override public LlmAdapter getLlmAdapter() {
        return getLlmAdapter(DEFAULT_TIER);
    }

    /** 按 tier 查路由表选 provider 的 Adapter；路由表无命中或对应 Adapter 不可用时兜底取任意可用 Adapter。 */
    @Override public LlmAdapter getLlmAdapter(String tier) {
        ModelRouting routing = modelRoutingService.findBestByTier(tier);
        if (routing != null) {
            LlmAdapter adapter = llmAdapterByProvider.get(routing.getProvider());
            if (adapter != null) {
                return adapter;
            }
            log.warn("[AdapterFactory] tier={} 路由命中 provider={} 但无对应 Adapter Bean（可能未装配/未启用），走兜底", tier, routing.getProvider());
        } else {
            log.warn("[AdapterFactory] tier={} 路由表无可用记录，走兜底", tier);
        }
        // 兜底：任意 isAvailable 的 LlmAdapter（保证派生不中断）
        return llmAdapterByProvider.values().stream()
                .filter(LlmAdapter::isAvailable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用 LlmAdapter（tier=" + tier + " 路由无命中且无可用 provider）"));
    }

    /** 按 tier + 显式 provider 选 Adapter（管理后台切换/灰度）。 */
    @Override public LlmAdapter getLlmAdapter(String tier, String provider) {
        LlmAdapter adapter = llmAdapterByProvider.get(provider);
        if (adapter != null) {
            return adapter;
        }
        log.warn("[AdapterFactory] 指定 provider={} 无对应 Adapter，回退按 tier={} 路由", provider, tier);
        return getLlmAdapter(tier);
    }

    /** 按 tier 查路由表取具体模型名；无命中原样透传（向后兼容，调用方兜底）。 */
    @Override public String resolveModel(String tier) {
        ModelRouting routing = modelRoutingService.findBestByTier(tier);
        if (routing != null) {
            return routing.getModel();
        }
        log.warn("[ModelRouting] tier={} 路由表无命中，resolveModel 原样透传（可能是已具体模型名或未知档位）", tier);
        return tier;
    }

    @Override public DocStoreAdapter getDocStoreAdapter() {
        return docStoreAdapters.stream().filter(DocStoreAdapter::isAvailable).findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用 DocStoreAdapter"));
    }

    /**
     * EA 蓝图 §4.3 企业适配器：返回首个 isAvailable 的 Bean；无可用或未装配返回 null
     * （区别于 Git/Llm/DocStore 的抛异常——这 4 个是企业可选能力，上层容忍 null 走降级）。
     */
    @Override public TicketAdapter getTicketAdapter() {
        return pick(ticketAdapters, "TicketAdapter");
    }

    @Override public CICDAdapter getCICDAdapter() {
        return pick(cicdAdapters, "CICDAdapter");
    }

    @Override public IMAdapter getIMAdapter() {
        return pick(imAdapters, "IMAdapter");
    }

    @Override public MCPAdapter getMCPAdapter() {
        return pick(mcpAdapters, "MCPAdapter");
    }

    /** 从可选注入的 Adapter 列表里挑首个 isAvailable 的；列表为空或全不可用返回 null。 */
    private <T extends Adapter> T pick(List<T> adapters, String name) {
        if (adapters == null || adapters.isEmpty()) {
            return null;
        }
        return adapters.stream()
                .filter(Adapter::isAvailable)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("[AdapterFactory] {} 已装配但全部 isAvailable=false（如 stub 未配 enabled）", name);
                    return null;
                });
    }
}
