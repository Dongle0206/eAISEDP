package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.MCPAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link MCPAdapter} 的真实 HTTP 实现（M4-2）。
 *
 * <p>通过 HTTP + JSON-RPC 2.0 对接外部 MCP（Model Context Protocol）Server，替代空壳
 * {@link StubMCPAdapter}。让平台与任意符合 MCP 标准的 server（官方或第三方）即插即用，
 * 无需每个集成都写死（EA 蓝图 §4.3 适配器体系第 7 类）。
 *
 * <p><b>协议映射</b>：
 * <ul>
 *   <li>{@link #listTools()} → JSON-RPC {@code tools/list}，解析 {@code result.tools} 数组。</li>
 *   <li>{@link #invokeTool(String, Map)} → JSON-RPC {@code tools/call}，参数 {@code {name, arguments}}，
 *       返回 {@code result}（结构由具体工具决定）。</li>
 *   <li>{@link #registerTool(String, String, Map)} → JSON-RPC {@code tools/register}，
 *       平台扩展方法（标准 MCP 无此方法，对接支持动态注册的 MCP server 时生效）。</li>
 * </ul>
 *
 * <p>请求体：{@code {"jsonrpc":"2.0","method":"<m>","params":{...},"id":<n>}}。
 * JSON-RPC id 用 {@link AtomicInteger} 自增（请求隔离，不依赖服务端分配）。
 *
 * <p><b>实现手法</b>：Spring 6.1 {@link RestClient} + Jackson 手写 HTTP，零新增 Maven 坐标
 * （与 {@link DeepSeekLlmAdapter} 一致；spring-boot-starter-web 间接引入 RestClient）。
 *
 * <p><b>条件装配</b>：仅当 {@code eaiselp.adapter.mcp.enabled=true} 时生效（默认不启用，
 * 企业接入真实 MCP server 时配 {@code MCP_ENABLED=true} + {@code MCP_SERVER_URL}）。
 * 与 {@link StubMCPAdapter} 互斥（同一 {@code enabled} 开关，二者仅一个真实生效——
 * Stub 的 {@code isAvailable()} 恒 false，HttpMCPAdapter 在 server-url 非空时可用，
 * {@code DefaultAdapterFactory#pick} 按 {@code isAvailable()} 选首个可用者）。
 *
 * <p>遵循 ES-003 §9.7 P7：新代码调外部工具必经 Adapter SPI，不绕过直接 HTTP 散落业务层。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.adapter.mcp.enabled", havingValue = "true")
public class HttpMCPAdapter implements MCPAdapter {

    /** MCP Server URL（如 {@code http://localhost:3001/mcp}）。空则 {@link #isAvailable()} 返回 false。 */
    @Value("${eaiselp.adapter.mcp.server-url:}")
    private String serverUrl;

    /** JSON-RPC 请求超时（毫秒）；MCP 工具调用可能较慢（如检索/计算），默认 60s。 */
    @Value("${eaiselp.adapter.mcp.timeout-ms:60000}")
    private long timeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** JSON-RPC id 自增器（请求隔离，进程内唯一即可，不与服务端状态耦合）。 */
    private final AtomicInteger requestId = new AtomicInteger(0);

    @Override public String getType() { return "mcp"; }
    @Override public String getProvider() { return "http-mcp"; }

    @Override
    public boolean isAvailable() {
        return serverUrl != null && !serverUrl.isBlank();
    }

    /**
     * 构造 RestClient（每次请求新建，便于超时/URL 注入；MCP 调用低频，无连接池复用开销）。
     * connect 固定 10s；read 按 {@link #timeoutMs}（上限 120s，防误配打爆线程）。
     */
    protected RestClient getRestClient() {
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        ((SimpleClientHttpRequestFactory) factory).setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        ((SimpleClientHttpRequestFactory) factory).setReadTimeout((int) Math.min(timeoutMs, 120000L));
        return RestClient.builder()
                .baseUrl(serverUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .build();
    }

    @Override
    public boolean registerTool(String name, String description, Map<String, Object> schema) {
        if (!isAvailable()) {
            log.warn("[MCPAdapter-Http] registerTool 跳过：server-url 未配置 (name={})", name);
            return false;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("description", description);
        params.put("schema", schema == null ? Collections.emptyMap() : schema);
        try {
            JsonNode resp = postJsonRpc("tools/register", params);
            // 注册类请求无标准 result 字段，无 error 即视为成功（error 非空时 postJsonRpc 已抛）
            boolean ok = resp != null && !resp.has("error");
            if (ok) {
                log.info("[MCPAdapter-Http] registerTool 成功: name={}", name);
            }
            return ok;
        } catch (Exception e) {
            log.error("[MCPAdapter-Http] registerTool 失败 name={}, err={}", name, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Object invokeTool(String name, Map<String, Object> params) {
        if (!isAvailable()) {
            log.warn("[MCPAdapter-Http] invokeTool 跳过：server-url 未配置 (name={})", name);
            return null;
        }
        Map<String, Object> rpcParams = new LinkedHashMap<>();
        rpcParams.put("name", name);
        rpcParams.put("arguments", params == null ? Collections.emptyMap() : params);
        try {
            JsonNode resp = postJsonRpc("tools/call", rpcParams);
            if (resp == null) {
                return null;
            }
            // MCP tools/call 响应：result 为工具返回（结构由工具决定），用 treeToValue 还原为 Object
            JsonNode result = resp.path("result");
            if (result.isMissingNode() || result.isNull()) {
                return null;
            }
            return objectMapper.treeToValue(result, Object.class);
        } catch (Exception e) {
            log.error("[MCPAdapter-Http] invokeTool 失败 name={}, err={}", name, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public List<ToolInfo> listTools() {
        if (!isAvailable()) {
            log.warn("[MCPAdapter-Http] listTools 跳过：server-url 未配置");
            return Collections.emptyList();
        }
        try {
            JsonNode resp = postJsonRpc("tools/list", Collections.emptyMap());
            if (resp == null) {
                return Collections.emptyList();
            }
            // MCP tools/list 响应：result.tools 数组，每项 {name, description, inputSchema}
            JsonNode tools = resp.path("result").path("tools");
            if (!tools.isArray() || tools.isEmpty()) {
                return Collections.emptyList();
            }
            List<ToolInfo> list = new ArrayList<>(tools.size());
            for (JsonNode t : tools) {
                String name = t.path("name").asText("");
                String description = t.path("description").asText("");
                // MCP 协议字段为 inputSchema（参数 JSON-Schema）；缺失置空 Map
                JsonNode schemaNode = t.path("inputSchema");
                Map<String, Object> schema = schemaNode.isMissingNode() || schemaNode.isNull()
                        ? new HashMap<>()
                        : objectMapper.treeToValue(schemaNode, Map.class);
                list.add(ToolInfo.builder()
                        .name(name)
                        .description(description)
                        .schema(schema)
                        .build());
            }
            log.info("[MCPAdapter-Http] listTools 成功: count={}", list.size());
            return list;
        } catch (Exception e) {
            log.error("[MCPAdapter-Http] listTools 失败 err={}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 发起一次 JSON-RPC 2.0 请求并返回解析后的响应根节点。
     *
     * <p>健壮解析：HTTP 4xx/5xx 抛 RuntimeException（带响应体便于排错）；
     * JSON-RPC {@code error} 非空时抛 RuntimeException（暴露服务端错误码+消息，不静默吞错）。
     *
     * @param method JSON-RPC method（tools/list / tools/call / tools/register）
     * @param params JSON-RPC params（可为空 Map）
     * @return 响应根节点；HTTP 调用异常时由调用方 catch
     */
    private JsonNode postJsonRpc(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        body.put("id", requestId.incrementAndGet());

        String jsonResp = getRestClient().post()
                .body(body)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(), (req, resp) -> {
                    String errBody = new String(resp.getBody().readAllBytes());
                    log.error("[MCPAdapter-Http] HTTP错误 method={}, status={}, body={}",
                            method, resp.getStatusCode().value(), errBody);
                    throw new RuntimeException("MCP Server HTTP 错误 [method=" + method
                            + ", status=" + resp.getStatusCode().value() + "]: " + errBody);
                })
                .body(String.class);

        JsonNode root = objectMapper.readTree(jsonResp);
        // JSON-RPC error 字段非空 → 抛异常（不静默返回，让上层 catch 走降级）
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            int code = error.path("code").asInt(-1);
            String message = error.path("message").asText("unknown MCP error");
            log.error("[MCPAdapter-Http] JSON-RPC error method={}, code={}, message={}", method, code, message);
            throw new RuntimeException("MCP JSON-RPC 错误 [method=" + method + ", code=" + code + "]: " + message);
        }
        return root;
    }
}
