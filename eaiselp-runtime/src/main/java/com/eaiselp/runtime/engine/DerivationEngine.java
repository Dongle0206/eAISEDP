package com.eaiselp.runtime.engine;

import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.adapter.spi.LlmAdapter;
import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.runtime.context.ContextAssembler;
import com.eaiselp.runtime.context.DerivationContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 派生引擎 —— 平台唯一调度入口。
 * 7 步标准动作：校验 → 选模型 → 装配 prompt → 调 LLM → 提取产出 → 入库 → 埋点。
 */
@Slf4j
@Component
public class DerivationEngine {

    private final AdapterFactory adapterFactory;
    private final ContextAssembler contextAssembler;
    private final DerivationPersistenceService persistenceService;   // M1.2 新增：独立 Bean 承载 @Transactional

    public DerivationEngine(AdapterFactory af, ContextAssembler ca,
                            DerivationPersistenceService persistenceService) {
        this.adapterFactory = af;
        this.contextAssembler = ca;
        this.persistenceService = persistenceService;
    }

    public DerivationResult derive(AgentDefinition agent, String task, String caseId, DerivationContext ctx) {
        long start = System.currentTimeMillis();
        String role = agent.getName();
        log.info("[Derive] 开始派生: role={}, case={}, task长度={}", role, caseId, task.length());

        // 2. 选模型（M2 SP-6 P8 解耦层）：agent.getModel() 是能力档位（opus/sonnet/haiku/reasoning/...），
        //    经 AdapterFactory 路由表解析为具体模型名 + 选 provider 对应 Adapter。换模型/换厂商只改 t_model_routing。
        String tier = agent.getModel() != null ? agent.getModel() : "sonnet";
        String model = adapterFactory.resolveModel(tier);
        // 3. 装配 prompt
        String fullPrompt = contextAssembler.assemble(agent.getPrompt(), ctx);
        // 4. 调 LLM（按 tier 选 Adapter，传入解析后的具体模型名）
        LlmAdapter llm = adapterFactory.getLlmAdapter(tier);
        LlmAdapter.LlmResponse resp = llm.invoke(model, fullPrompt,
                LlmAdapter.LlmOptions.builder().timeoutMs(60000L).build());
        // 5. 提取产出
        String content = resp.getContent();
        String experience = extractExperience(content);
        List<ProducedArtifact> artifacts = extractArtifacts(content, role, caseId);
        // 6. 构建结果（内存对象）
        DerivationResult result = DerivationResult.builder()
                .role(role).caseId(caseId).model(model)
                .output(content).experience(experience).artifacts(artifacts)
                .inputTokens(resp.getInputTokens()).outputTokens(resp.getOutputTokens())
                .durationMs(System.currentTimeMillis() - start)
                .finishedAt(LocalDateTime.now()).status("success").build();
        // 6.1 落库（@Transactional 由独立 Bean DerivationPersistenceService 承载，避免 this 自调用失效）
        //     落库失败 try-catch Throwable 只 log 不重抛（§3.3/§3.4）：高价值派生结果已构建，不能让 DB 抖动拖垮主流程
        try {
            persistenceService.persist(result);
        } catch (Throwable t) {
            // M1.2 决策：捕获 Throwable（含 Error）不重抛 —— 派生结果必须返回给调用方，错误已 log 运维可追
            log.error("[Derive] 落库失败但返回派生结果: role={}, case={}", role, caseId, t);
        }
        // 7. 埋点
        log.info("[Derive] 完成: role={}, case={}, in={}, out={}, 耗时={}ms, 产出{}artifact",
                role, caseId, resp.getInputTokens(), resp.getOutputTokens(), result.getDurationMs(), artifacts.size());
        return result;
    }

    private String extractExperience(String c) {
        if (c == null) return null;
        int i = c.indexOf("## 本次经验沉淀");
        return i < 0 ? null : c.substring(i).trim();
    }

    private List<ProducedArtifact> extractArtifacts(String c, String role, String caseId) {
        if (c == null || c.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(ProducedArtifact.builder()
                .type(guessType(role)).role(role).caseId(caseId).content(c).build());
    }

    private String guessType(String role) {
        if (role == null) return "unknown";
        return switch (role) {
            case "team-po" -> "prd";
            case "team-ux" -> "design";
            case "team-se" -> "tech-design";
            case "team-ba" -> "tasks";
            case "team-dev" -> "code";
            case "team-reviewer", "team-security" -> "review";
            case "team-qa" -> "test";
            case "team-performance" -> "perf";
            case "team-ops" -> "deploy";
            case "team-pm" -> "tracking";
            default -> "other";
        };
    }

    @Data @lombok.Builder
    public static class DerivationResult {
        private String role; private String caseId; private String model;
        private String output; private String experience; private List<ProducedArtifact> artifacts;
        private Integer inputTokens; private Integer outputTokens;
        private Long durationMs; private LocalDateTime finishedAt; private String status;
    }

    @Data @lombok.Builder
    public static class ProducedArtifact {
        private String type; private String role; private String caseId; private String content;
    }
}
