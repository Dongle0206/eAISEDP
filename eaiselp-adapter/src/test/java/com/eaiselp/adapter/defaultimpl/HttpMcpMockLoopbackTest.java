package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.MCPAdapter.ToolInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON-RPC 2.0 契约回环测试（case-20260822-MCPMock，F3 / AC-10 / AC-11）。
 *
 * <p>结构：HttpMCPAdapter（真实 JSON-RPC 2.0 客户端）→ MockWebServer 本地回环
 * endpoint（最小 MCP Server：initialize / tools/list / tools/call）→ MockMCPProvider
 * （工具数据）。固化请求/响应契约，真实 server 上线时仅改 {@code server-url} 配置
 * 即可零代码改动替换（AC-11 用第二回环 endpoint 验证）。
 *
 * <p>两层断言：
 * <ul>
 *   <li><b>原始报文层</b>（JDK HttpClient 直发）：每个响应符合 JSON-RPC 2.0——
 *       {@code jsonrpc:"2.0"}、id 与请求一致、result 与 error 二选一（AC-10）。</li>
 *   <li><b>适配层</b>（HttpMCPAdapter SPI）：listTools/invokeTool 经完整
 *       HTTP+JSON-RPC 链路往返，Mock 数据无损还原。</li>
 * </ul>
 *
 * <p>实现手法对齐 GlmLlmAdapterTest：MockWebServer + 反射注入 private @Value 字段
 * （serverUrl/timeoutMs），零真实外部依赖。
 */
class HttpMcpMockLoopbackTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private MockMCPProvider provider;
    private MockWebServer server;
    private HttpMCPAdapter adapter;
    private final HttpClient rawClient = HttpClient.newHttpClient();
    private int rpcId = 100;

    @BeforeEach
    void setUp() throws Exception {
        provider = new MockMCPProvider();
        server = new MockWebServer();
        server.setDispatcher(jsonRpcDispatcher(provider));
        server.start();

        adapter = new HttpMCPAdapter();
        setField("serverUrl", server.url("/").toString());
        setField("timeoutMs", 5000L);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // ============================ 原始报文层：JSON-RPC 2.0 契约（AC-10） ============================

    @Test
    @DisplayName("initialize: jsonrpc=2.0 / id 一致 / 有 result 无 error")
    void 原始报文_initialize符合JSONRPC20() throws Exception {
        ObjectNode params = OM.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", OM.createObjectNode());
        ObjectNode clientInfo = OM.createObjectNode();
        clientInfo.put("name", "eaiselp-adapter");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);

        JsonNode resp = rpc("initialize", params);

        assertJsonRpc20(resp);
        assertEquals("2024-11-05", resp.path("result").path("protocolVersion").asText(),
                "initialize result 应含 protocolVersion");
        assertFalse(resp.path("result").path("serverInfo").isMissingNode(),
                "initialize result 应含 serverInfo");
    }

    @Test
    @DisplayName("tools/list: jsonrpc=2.0 / id 一致 / result.tools 恰好 9 个且含 inputSchema")
    void 原始报文_toolsList符合JSONRPC20() throws Exception {
        JsonNode resp = rpc("tools/list", OM.createObjectNode());

        assertJsonRpc20(resp);
        JsonNode tools = resp.path("result").path("tools");
        assertTrue(tools.isArray(), "result.tools 应为数组");
        assertEquals(9, tools.size(), "应恰好 9 个工具");
        for (JsonNode t : tools) {
            assertFalse(t.path("name").asText("").isBlank(), "工具应含 name");
            assertFalse(t.path("inputSchema").isMissingNode(), "工具应含 inputSchema（MCP 协议字段名）");
        }
    }

    @Test
    @DisplayName("tools/call 成功: jsonrpc=2.0 / result 携带 __mock=true 与工具数据")
    void 原始报文_toolsCall成功符合JSONRPC20() throws Exception {
        ObjectNode params = OM.createObjectNode();
        params.put("name", "jira.create_issue");
        params.set("arguments", OM.createObjectNode().put("summary", "契约回环测试"));
        JsonNode resp = rpc("tools/call", params);

        assertJsonRpc20(resp);
        assertTrue(resp.path("result").path("__mock").asBoolean(false), "result 应含 __mock 标识");
        assertTrue(resp.path("result").path("id").asText("").startsWith("JIRA-"), "result 应含工具返回数据");
    }

    @Test
    @DisplayName("tools/call 缺必填参数: error 与 result 二选一取 error（-32602），id 一致")
    void 原始报文_toolsCall缺参返回error对象() throws Exception {
        ObjectNode params = OM.createObjectNode();
        params.put("name", "jira.create_issue");
        params.set("arguments", OM.createObjectNode());
        JsonNode resp = rpc("tools/call", params);

        assertJsonRpc20(resp);
        assertTrue(resp.has("error"), "应返回 error 对象");
        assertFalse(resp.has("result"), "error 与 result 二选一，不得并存");
        assertEquals(MockMCPProvider.CODE_INVALID_PARAMS, resp.path("error").path("code").asInt());
        assertTrue(resp.path("error").path("message").asText().contains("summary"),
                "error.message 应指出缺失参数");
    }

    @Test
    @DisplayName("tools/call 未知工具: error=-32601 且无 result")
    void 原始报文_toolsCall未知工具返回32601() throws Exception {
        ObjectNode params = OM.createObjectNode();
        params.put("name", "no.such_tool");
        params.set("arguments", OM.createObjectNode());
        JsonNode resp = rpc("tools/call", params);

        assertJsonRpc20(resp);
        assertEquals(MockMCPProvider.CODE_TOOL_NOT_FOUND, resp.path("error").path("code").asInt());
        assertFalse(resp.has("result"));
    }

    @Test
    @DisplayName("未知 method: error=-32601 且无 result")
    void 原始报文_未知method返回32601() throws Exception {
        JsonNode resp = rpc("resources/list", OM.createObjectNode());

        assertJsonRpc20(resp);
        assertEquals(MockMCPProvider.CODE_TOOL_NOT_FOUND, resp.path("error").path("code").asInt());
        assertFalse(resp.has("result"));
    }

    // ============================ 适配层：SPI 完整链路往返 ============================

    @Test
    @DisplayName("适配层回环: listTools 经 HTTP 往返返回 9 工具且 schema 解析无损")
    void 适配层_listTools回环() {
        List<ToolInfo> tools = adapter.listTools();

        assertEquals(9, tools.size());
        ToolInfo create = tools.stream()
                .filter(t -> "jira.create_issue".equals(t.getName()))
                .findFirst().orElseThrow();
        assertNotNull(create.getSchema());
        assertNotNull(create.getSchema().get("properties"), "inputSchema 应被解析进 schema 字段");
        assertEquals(List.of("summary"), create.getSchema().get("required"));
    }

    @Test
    @DisplayName("适配层回环: invokeTool 经 tools/call 往返返回 Mock 结果（__mock + 数据）")
    void 适配层_invokeTool回环() {
        Object result = adapter.invokeTool("confluence.create_page", Map.of(
                "title", "回环页", "content", "经 HttpMCPAdapter 的内容"));

        assertInstanceOf(Map.class, result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("__mock"));
        assertEquals("回环页", map.get("title"));
        assertNotNull(map.get("id"));
    }

    @Test
    @DisplayName("适配层回环: tools/call 错误时 invokeTool 返回 null（适配器既有降级语义）")
    void 适配层_invokeTool错误返回null() {
        assertNull(adapter.invokeTool("jira.create_issue", Map.of()), "缺必填 → JSON-RPC error → 适配器降级 null");
        assertNull(adapter.invokeTool("no.such_tool", Map.of()), "未知工具 → 同上");
    }

    // ============================ AC-11 配置化 target 零改动替换 ============================

    @Test
    @DisplayName("AC-11 仅改 target 配置切换到第二回环 endpoint：适配层零改动，同一断言仍通过")
    void 切换第二endpoint_仅改配置_契约仍通过() throws Exception {
        // 第二个"符合契约的 endpoint"：独立 MockWebServer + 独立 MockMCPProvider 实例
        MockWebServer server2 = new MockWebServer();
        server2.setDispatcher(jsonRpcDispatcher(new MockMCPProvider()));
        server2.start();
        try {
            // 模拟"仅修改配置"：只换注入的 server-url（等价 MCP_SERVER_URL 配置项），不动任何代码
            setField("serverUrl", server2.url("/").toString());

            assertEquals(9, adapter.listTools().size(), "第二 endpoint：listTools 契约仍成立");
            Object result = adapter.invokeTool("gitlab.create_mr", Map.of(
                    "title", "第二endpoint的MR", "source_branch", "b2", "target_branch", "main"));
            assertInstanceOf(Map.class, result);
            assertEquals(Boolean.TRUE, ((Map<?, ?>) result).get("__mock"));
            assertEquals("b2", ((Map<?, ?>) result).get("source_branch"));
        } finally {
            server2.shutdown();
            // 还原，防污染其他用例（dispatcher 各自独立 provider，数据无交叉，仅防 url 残留）
            setField("serverUrl", server.url("/").toString());
        }
    }

    // ============================ 辅助：最小 MCP 回环 Server（测试侧契约靶） ============================

    /**
     * 构造最小 MCP JSON-RPC 2.0 回环 dispatcher：把 MCP 协议方法桥接到
     * {@link MockMCPProvider}（invokeTool 返回值即 result 字段；返回含 error 子对象时
     * 转为 JSON-RPC error 响应）。这就是"Mock 作为 HttpMCPAdapter 本地回环 target"
     * 的契约固化——真实 server 接入时按同一契约实现即可替换。
     */
    static Dispatcher jsonRpcDispatcher(MockMCPProvider provider) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    JsonNode req = OM.readTree(request.getBody().readUtf8());
                    JsonNode id = req.path("id");
                    String method = req.path("method").asText("");
                    JsonNode params = req.path("params");
                    switch (method) {
                        case "initialize" -> {
                            ObjectNode result = OM.createObjectNode();
                            result.put("protocolVersion", "2024-11-05");
                            result.set("capabilities", OM.createObjectNode().set("tools", OM.createObjectNode()));
                            result.set("serverInfo", OM.createObjectNode()
                                    .put("name", "mock-mcp-loopback").put("version", "1.0.0"));
                            return envelope(id, result);
                        }
                        case "tools/list" -> {
                            ObjectNode result = OM.createObjectNode();
                            result.set("tools", OM.valueToTree(provider.listTools().stream()
                                    .map(t -> Map.of(
                                            "name", t.getName(),
                                            "description", t.getDescription() == null ? "" : t.getDescription(),
                                            "inputSchema", t.getSchema() == null ? Map.of() : t.getSchema()))
                                    .toList()));
                            return envelope(id, result);
                        }
                        case "tools/call" -> {
                            Map<String, Object> args = params.has("arguments") && params.path("arguments").isObject()
                                    ? OM.convertValue(params.path("arguments"), Map.class)
                                    : Map.of();
                            Object r = provider.invokeTool(params.path("name").asText(""), args);
                            JsonNode resultNode = OM.valueToTree(r);
                            if (resultNode.isObject() && resultNode.has("error")) {
                                // Mock 的结构化错误 → JSON-RPC error 响应（result 与 error 二选一）
                                JsonNode err = resultNode.path("error");
                                ObjectNode errNode = OM.createObjectNode();
                                errNode.put("code", err.path("code").asInt());
                                errNode.put("message", err.path("message").asText(""));
                                return envelopeError(id, errNode);
                            }
                            return envelope(id, resultNode);
                        }
                        default -> {
                            return envelopeError(id, OM.createObjectNode()
                                    .put("code", MockMCPProvider.CODE_TOOL_NOT_FOUND)
                                    .put("message", "未知 JSON-RPC method: " + method));
                        }
                    }
                } catch (Exception e) {
                    return envelopeError(OM.nullNode(), OM.createObjectNode()
                            .put("code", -32603)
                            .put("message", "回环 server 内部错误: " + e.getMessage()));
                }
            }
        };
    }

    private static MockResponse envelope(JsonNode id, JsonNode result) {
        ObjectNode resp = OM.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.set("result", result);
        return json(resp);
    }

    private static MockResponse envelopeError(JsonNode id, ObjectNode error) {
        ObjectNode resp = OM.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.set("error", error);
        return json(resp);
    }

    private static MockResponse json(ObjectNode body) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body.toString());
    }

    /** 直发一次 JSON-RPC 请求（原始报文断言用，绕过 HttpMCPAdapter 以校验契约本身）。 */
    private JsonNode rpc(String method, ObjectNode params) throws Exception {
        ObjectNode body = OM.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.set("params", params);
        int sentId = rpcId++;
        body.put("id", sentId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(server.url("/").toString()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = rawClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = OM.readTree(resp.body());
        assertEquals(sentId, root.path("id").asInt(-1), "响应 id 必须与请求一致");
        return root;
    }

    /** AC-10 核心断言：jsonrpc=2.0 + result 与 error 二选一。 */
    private static void assertJsonRpc20(JsonNode resp) {
        assertEquals("2.0", resp.path("jsonrpc").asText(), "jsonrpc 字段必须为 \"2.0\"");
        assertTrue(resp.has("id"), "响应必须含 id");
        boolean hasResult = resp.has("result") && !resp.path("result").isNull();
        boolean hasError = resp.has("error") && !resp.path("error").isNull();
        assertNotEquals(hasResult, hasError, "result 与 error 必须二选一（互斥）: " + resp);
        if (hasError) {
            assertFalse(resp.path("error").path("code").isMissingNode(), "error 应含 code");
            assertFalse(resp.path("error").path("message").isMissingNode(), "error 应含 message");
        }
    }

    /** 反射注入 HttpMCPAdapter 的 private @Value 字段（对齐 GlmLlmAdapterTest 手法）。 */
    private void setField(String name, Object value) throws Exception {
        Field f = HttpMCPAdapter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(adapter, value);
    }
}
