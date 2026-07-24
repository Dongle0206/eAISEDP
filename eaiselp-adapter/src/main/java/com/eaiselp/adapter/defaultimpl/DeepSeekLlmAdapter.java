package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.routing.entity.ModelRouting;
import com.eaiselp.adapter.routing.service.ModelRoutingService;
import com.eaiselp.adapter.spi.LlmAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * DeepSeek LLM 适配器 —— 多 provider 路由预留实现（M2 SP-6，P8 解耦层）。
 *
 * <p>DeepSeek API 兼容 OpenAI 格式：端点 {@code POST {base-url}/chat/completions}，
 * 鉴权 {@code Authorization: Bearer <api-key>}，请求/响应结构与 OpenAI 一致
 * （choices[0].message.content / usage.prompt_tokens / usage.completion_tokens）。
 *
 * <p>实现手法同 {@link GlmLlmAdapter}：Spring 6.1 {@link RestClient} + Jackson 手写 HTTP，零新增 Maven 坐标。
 *
 * <p>条件装配：仅当 {@code eaiselp.adapter.llm.deepseek.enabled=true} 时生效。
 * M2 不强制配 Key，但代码就绪——配好 {@code DEEPSEEK_API_KEY} 并在路由表启用对应行即可切换厂商。
 * 由 {@link com.eaiselp.adapter.factory.DefaultAdapterFactory} 按 t_model_routing 路由结果选用。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.adapter.llm.deepseek.enabled", havingValue = "true")
public class DeepSeekLlmAdapter implements LlmAdapter {

    @Value("${eaiselp.adapter.llm.deepseek.api-key:}") private String apiKey;
    @Value("${eaiselp.adapter.llm.deepseek.base-url:https://api.deepseek.com/v1}") private String baseUrl;

    /**
     * 可用模型清单从 t_model_routing 读（provider=deepseek 的 model 列），不硬编码模型名。
     * required=false：单测无 Spring 容器时为 null，listModels 兜底返回空表。
     */
    @Autowired(required = false)
    private ModelRoutingService modelRoutingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String getType() { return "llm"; }
    @Override public String getProvider() { return "deepseek"; }
    @Override public boolean isAvailable() { return apiKey != null && !apiKey.isEmpty(); }

    /**
     * 构造 RestClient。protected 便于单测覆写指向 MockWebServer。
     * connect 固定 10s；read 按 timeoutMs（上限 120s）。
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
            throw new IllegalStateException("DeepSeek API Key 未配置 (eaiselp.adapter.llm.deepseek.api-key / 环境变量 DEEPSEEK_API_KEY)");
        }
        // model 参数即具体 DeepSeek 模型名（由 ModelRoutingService 按 tier 解析后传入）；null/空兜底到 deepseek-chat（OpenAI 兼容默认）
        final String resolvedModel = (model == null || model.isBlank()) ? "deepseek-chat" : model;
        long timeoutMs = options != null && options.getTimeoutMs() != null ? options.getTimeoutMs() : 60000L;
        double temperature = options != null && options.getTemperature() != null ? options.getTemperature() : 0.7;
        int maxTokens = options != null && options.getMaxTokens() != null ? options.getMaxTokens() : 4096;

        // 构造请求体（OpenAI 兼容格式，Map 拼 + Jackson 序列化，避免写 POJO）
        Map<String, Object> requestBody = Map.of(
                "model", resolvedModel,
                "messages", new Object[]{Map.of("role", "user", "content", prompt)},
                "temperature", temperature,
                "max_tokens", maxTokens
        );

        log.info("[LlmAdapter-DeepSeek] 调用: model={}, prompt长度={}, timeoutMs={}", resolvedModel, prompt.length(), timeoutMs);
        try {
            String jsonResp = getRestClient(timeoutMs).post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(), (req, resp) -> {
                        String errBody = new String(resp.getBody().readAllBytes());
                        log.error("[LlmAdapter-DeepSeek] HTTP错误 model={}, status={}, body={}", resolvedModel, resp.getStatusCode().value(), errBody);
                        throw new RuntimeException("DeepSeek HTTP 错误 [model=" + resolvedModel
                                + ", status=" + resp.getStatusCode().value() + "]: " + errBody);
                    })
                    .body(String.class);

            // 健壮解析：choices/usage 任一缺失不 NPE（JsonNode.path() 链式取值，OpenAI 兼容字段名）
            JsonNode root = objectMapper.readTree(jsonResp);
            JsonNode choices = root.path("choices");
            String content = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("message").path("content").asText("") : "";
            String finishReason = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("finish_reason").asText("stop") : "stop";
            JsonNode usage = root.path("usage");
            // OpenAI 兼容：prompt_tokens / completion_tokens；缺失置 null 不伪造
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
            throw e;
        } catch (Exception e) {
            log.error("[LlmAdapter-DeepSeek] 调用失败 model={}, err={}", resolvedModel, e.getMessage(), e);
            throw new RuntimeException("DeepSeek 调用失败 [model=" + resolvedModel + "]: " + e.getMessage(), e);
        }
    }

    @Override public List<String> listModels() {
        // 从 t_model_routing 读 provider=deepseek 的可用模型，不硬编码模型名
        if (modelRoutingService == null) {
            return Collections.emptyList();
        }
        return modelRoutingService.findAll().stream()
                .filter(r -> "deepseek".equalsIgnoreCase(r.getProvider()))
                .map(ModelRouting::getModel)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override public boolean validateModel(String model) { return listModels().contains(model); }
}
