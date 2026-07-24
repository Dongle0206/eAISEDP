package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.routing.entity.ModelRouting;
import com.eaiselp.adapter.routing.service.ModelRoutingService;
import com.eaiselp.adapter.spi.LlmAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GLM（智谱 AI）LLM 适配器 —— 方案 C 手写 HTTP 实现。
 *
 * <p>case-20260723-GLM接入：SE §9 裁决放弃 SDK（langchain4j-community-zhipu-ai 在 0.31.0 不存在，
 * 旧 artifact 无 callTimeout，community 1.0.0-alpha1 与项目 langchain4j 0.31.0 版本冲突），
 * 改用 Spring 6.1 {@link RestClient} + Jackson 手写 HTTP 调用智谱 v4 API。
 *
 * <p>零新增 Maven 坐标（mybatis-plus 仅 ModelRouting 持久化用，与本类无关）：RestClient 随
 * spring-boot-starter-web（spring-web 6.1.6）已在 classpath，Jackson 随 starter 间接可用。
 *
 * <p>鉴权：智谱 v4 统一 {@code Authorization: Bearer <完整 api-key>}（非 JWT，SE §9.3 已纠正原方案 §2.1 的错误认知）。
 * 端点：{@code POST {base-url}/chat/completions}（默认 base-url=https://open.bigmodel.cn/api/paas/v4）。
 *
 * <p>M2 SP-6 重构（P8 解耦层）：删除原内联档位映射常量与 resolveModel 方法，
 * 档位→具体模型的映射交给 {@link com.eaiselp.adapter.routing.service.ModelRoutingService}（读 t_model_routing）。
 * 本适配器只负责"拿到具体 GLM 模型名后调用 API"，{@code model} 参数直接是具体模型名（由路由表解析后传入），
 * 不再做 tier 翻译。装配方式从条件装配改为无条件 @Component，由 Factory 按 tier 路由结果选用。
 */
@Slf4j
@Component
public class GlmLlmAdapter implements LlmAdapter {

    @Value("${eaiselp.adapter.llm.glm.api-key:}") private String apiKey;
    @Value("${eaiselp.adapter.llm.glm.base-url:https://open.bigmodel.cn/api/paas/v4}") private String baseUrl;

    /**
     * M2 SP-6：可用模型清单从 t_model_routing 读（只列 provider=glm 的 model 列），
     * 不再硬编码具体 GLM 模型名字面量（ES-003 §2.2/§2.3 门禁 G12）。
     * required=false：单测 new GlmLlmAdapter() 无 Spring 容器，此字段为 null 时 listModels 兜底返回空表。
     */
    @Autowired(required = false)
    private ModelRoutingService modelRoutingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String getType() { return "llm"; }
    @Override public String getProvider() { return "glm"; }
    @Override public boolean isAvailable() { return apiKey != null && !apiKey.isEmpty(); }

    /**
     * 构造 RestClient。protected 便于单测覆写指向 MockWebServer（SE §9.5.5 方案 C 红利）。
     *
     * <p>每次按 timeoutMs 新建（SimpleClientHttpRequestFactory 构造极轻量，M1.0 单并发无性能压力；
     * 这样测试可为不同用例注入不同 readTimeout，覆盖超时路径）。生产环境可后续优化为按 timeout 分桶缓存。
     *
     * @param timeoutMs 本次请求读超时（来自 options.timeoutMs，缺省 60000ms）；connect 固定 10s（SE §9.4）
     */
    protected RestClient getRestClient(long timeoutMs) {
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        ((SimpleClientHttpRequestFactory) factory).setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        ((SimpleClientHttpRequestFactory) factory).setReadTimeout((int) Math.min(timeoutMs, 120000L));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .build();
    }

    @Override
    public LlmResponse invoke(String model, String prompt, LlmOptions options) {
        long start = System.currentTimeMillis();
        if (!isAvailable()) {
            throw new IllegalStateException("GLM API Key 未配置 (eaiselp.adapter.llm.glm.api-key / 环境变量 GLM_API_KEY)");
        }
        // M2 SP-6：model 参数即具体 GLM 模型名（由 ModelRoutingService 按 tier 解析后传入），null/空兜底到 glm-4
        final String resolvedModel = (model == null || model.isBlank()) ? "glm-4" : model;
        long timeoutMs = options != null && options.getTimeoutMs() != null ? options.getTimeoutMs() : 60000L;
        double temperature = options != null && options.getTemperature() != null ? options.getTemperature() : 0.7;
        int maxTokens = options != null && options.getMaxTokens() != null ? options.getMaxTokens() : 4096;

        // 构造请求体（Map 拼，Jackson 序列化，避免写 POJO）。SE §9.3：不传 response_format（R11 兼容性踩坑）。
        Map<String, Object> requestBody = Map.of(
                "model", resolvedModel,
                "messages", new Object[]{Map.of("role", "user", "content", prompt)},
                "temperature", temperature,
                "max_tokens", maxTokens
        );

        log.info("[LlmAdapter-GLM] 调用: model={}, prompt长度={}, timeoutMs={}", resolvedModel, prompt.length(), timeoutMs);
        try {
            String jsonResp = getRestClient(timeoutMs).post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    // 4xx/5xx（含 429 限流）先读 body 提取 error.code/message，再包装成 RuntimeException 冒泡（SE §9.5.3.3 异常处理要点）
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(), (req, resp) -> {
                        String errBody = new String(resp.getBody().readAllBytes());
                        log.error("[LlmAdapter-GLM] HTTP错误 model={}, status={}, body={}", resolvedModel, resp.getStatusCode().value(), errBody);
                        throw new RuntimeException("GLM HTTP 错误 [model=" + resolvedModel
                                + ", status=" + resp.getStatusCode().value() + "]: " + errBody);
                    })
                    .body(String.class);

            // 健壮解析：choices/usage 任一缺失不 NPE（JsonNode.path() 链式取值，SE §9.5.3.3）
            JsonNode root = objectMapper.readTree(jsonResp);
            JsonNode choices = root.path("choices");
            String content = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("message").path("content").asText("") : "";
            String finishReason = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("finish_reason").asText("stop") : "stop";
            JsonNode usage = root.path("usage");
            // 智谱用下划线 prompt_tokens / completion_tokens（SE §9.3 字段名差异纠正）；缺失置 null 不伪造
            Integer inputTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
            Integer outputTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;

            return LlmResponse.builder()
                    .content(content)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .model(resolvedModel)
                    .durationMs(System.currentTimeMillis() - start)
                    .finishReason(finishReason)
                    .build();
        } catch (RuntimeException e) {
            // onStatus 抛出的 RuntimeException 直接透传（已含 model + status 上下文）
            throw e;
        } catch (Exception e) {
            // Jackson 解析异常、网络 IO 异常（含 SocketTimeoutException 超时）等
            log.error("[LlmAdapter-GLM] 调用失败 model={}, err={}", resolvedModel, e.getMessage(), e);
            throw new RuntimeException("GLM 调用失败 [model=" + resolvedModel + "]: " + e.getMessage(), e);
        }
    }

    @Override public List<String> listModels() {
        // M2 SP-6：从 t_model_routing 读 provider=glm 的可用模型（ES-003 §2.2），
        // 不再硬编码具体 GLM 模型名字面量（门禁 G12）。
        // 单测无 Spring 容器（modelRoutingService=null）时返回空表，不影响 invoke 主路径单测。
        if (modelRoutingService == null) {
            return Collections.emptyList();
        }
        return modelRoutingService.findAll().stream()
                .filter(r -> "glm".equalsIgnoreCase(r.getProvider()))
                .map(ModelRouting::getModel)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override public boolean validateModel(String model) { return listModels().contains(model); }
}
