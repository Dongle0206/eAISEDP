package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.LlmAdapter.LlmOptions;
import com.eaiselp.adapter.spi.LlmAdapter.LlmResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlmLlmAdapter 单元测试 —— 方案 C MockWebServer 全覆盖（SE §9.5.5）。
 *
 * <p>手写 HTTP 的最大红利：RestClient builder 原生支持 .baseUrl(localhost)，
 * 测试用 MockWebServer 起本地 HTTP，验证完整链路（请求体构造、Bearer 鉴权头、
 * 响应解析、usage 映射、429 错误处理、超时），零真实 API 调用（不烧钱，R7）。
 *
 * <p>字段注入：apiKey/baseUrl 是 @Value private 字段，单测无 Spring 容器，
 * 用反射注入指向 MockWebServer 的 baseUrl + 测试 apiKey。
 *
 * <p>M2 SP-6 重构（P8 解耦层）：原 TC-1 resolveModel 映射测试已删除——档位→具体模型映射
 * 移到 ModelRoutingService（读 t_model_routing），本适配器不再做 tier 翻译。
 * invoke 的 model 参数现在直接是具体 GLM 模型名（由路由解析后传入），测试按此新契约验证。
 */
class GlmLlmAdapterTest {

    private GlmLlmAdapter adapter;
    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new GlmLlmAdapter();
        server = new MockWebServer();
        server.start();
        // 注入 private 字段：baseUrl 指向 MockWebServer，apiKey 用测试占位（不碰真实 key，R3）
        setField(adapter, "baseUrl", server.url("/").toString());
        setField(adapter, "apiKey", "test-api-key-placeholder");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // ============================ TC-2: 成功调用（SE §9.5.5 点3）============================
    // M2 SP-6：model 参数即具体 GLM 模型名（不再 tier 映射）

    @Test
    @DisplayName("invoke 成功: content + usage + finishReason 正确映射，且请求体/鉴权头正确")
    void invoke_success_mapsContentAndUsage() throws Exception {
        String respJson = "{"
                + "\"id\":\"test-id\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"这是 GLM 生成的 PRD\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":128,\"completion_tokens\":256,\"total_tokens\":384}"
                + "}";
        server.enqueue(new MockResponse().setBody(respJson).setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        // model 参数直接是具体 GLM 模型名（M2 SP-6 新契约）
        LlmResponse resp = adapter.invoke("glm-4-plus", "写登录功能 PRD",
                LlmOptions.builder().temperature(0.5).maxTokens(2048).timeoutMs(10000L).build());

        // 响应映射验证
        assertEquals("这是 GLM 生成的 PRD", resp.getContent());
        assertEquals(128, resp.getInputTokens());
        assertEquals(256, resp.getOutputTokens());
        assertEquals("glm-4-plus", resp.getModel());   // 原样回传（不再映射）
        assertEquals("stop", resp.getFinishReason());
        assertNotNull(resp.getDurationMs());
        assertTrue(resp.getDurationMs() >= 0);

        // 请求验证：发出去的请求体 + 鉴权头
        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        assertEquals("/chat/completions", req.getPath());
        assertEquals("Bearer test-api-key-placeholder", req.getHeader("Authorization"));
        String body = req.getBody().readUtf8();
        // 请求体字段验证（SE §9.3，分段断言避免依赖 Jackson 字段顺序）
        assertTrue(body.contains("\"model\":\"glm-4-plus\""), "请求体应含 model");
        assertTrue(body.contains("\"role\":\"user\""), "请求体应含 role=user");
        assertTrue(body.contains("\"content\":\"写登录功能 PRD\""), "请求体应含原始 prompt content");
        assertTrue(body.contains("\"temperature\":0.5"), "请求体应含 temperature");
        assertTrue(body.contains("\"max_tokens\":2048"), "请求体应含 max_tokens");
        assertFalse(body.contains("response_format"), "请求体不应含 response_format（R11 兼容性踩坑）");
    }

    @Test
    @DisplayName("invoke 成功: options 缺省时 temperature=0.7 / max_tokens=4096")
    void invoke_success_defaultOptions() throws Exception {
        String respJson = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        server.enqueue(new MockResponse().setBody(respJson).setResponseCode(200));

        LlmResponse resp = adapter.invoke("glm-4", "hello", null);
        assertEquals("ok", resp.getContent());
        assertEquals(10, resp.getInputTokens());
        assertEquals(5, resp.getOutputTokens());
        assertEquals("glm-4", resp.getModel());

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"temperature\":0.7"), "缺省 temperature=0.7");
        assertTrue(body.contains("\"max_tokens\":4096"), "缺省 max_tokens=4096");
    }

    // ============================ TC-2b: model null/空 兜底到 glm-4（M2 SP-6 新增）============================

    @Test
    @DisplayName("invoke model=null/空: 兜底到 glm-4，不 NPE")
    void invoke_nullModel_fallsBackToDefault() throws Exception {
        String respJson = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        server.enqueue(new MockResponse().setBody(respJson).setResponseCode(200));

        LlmResponse resp = adapter.invoke(null, "hello", null);
        assertEquals("ok", resp.getContent());
        assertEquals("glm-4", resp.getModel(), "model=null 兜底到 glm-4");
    }

    @Test
    @DisplayName("invoke model=空白: 兜底到 glm-4")
    void invoke_blankModel_fallsBackToDefault() throws Exception {
        String respJson = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        server.enqueue(new MockResponse().setBody(respJson).setResponseCode(200));

        LlmResponse resp = adapter.invoke("   ", "hello", null);
        assertEquals("glm-4", resp.getModel(), "model 空白兜底到 glm-4");
    }

    // ============================ TC-5: usage 缺失（SE §9.5.5 点4）============================

    @Test
    @DisplayName("invoke usage 缺失: inputTokens/outputTokens 为 null（不伪造）")
    void invoke_missingUsage_returnsNullTokens() throws Exception {
        String respJson = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"no usage\"},\"finish_reason\":\"stop\"}]}";
        server.enqueue(new MockResponse().setBody(respJson).setResponseCode(200));

        LlmResponse resp = adapter.invoke("glm-4-flash", "test", null);
        assertEquals("no usage", resp.getContent());
        assertEquals("glm-4-flash", resp.getModel());
        assertEquals("stop", resp.getFinishReason());
        assertNull(resp.getInputTokens(), "usage 缺失时 inputTokens 应为 null，不伪造");
        assertNull(resp.getOutputTokens(), "usage 缺失时 outputTokens 应为 null，不伪造");
    }

    @Test
    @DisplayName("invoke choices 缺失: content 空，不 NPE")
    void invoke_missingChoices_returnsEmptyContent() throws Exception {
        String respJson = "{\"id\":\"x\"}";   // 无 choices 无 usage
        server.enqueue(new MockResponse().setBody(respJson).setResponseCode(200));

        LlmResponse resp = adapter.invoke("glm-4", "test", null);
        assertEquals("", resp.getContent());
        assertEquals("stop", resp.getFinishReason());
        assertNull(resp.getInputTokens());
        assertNull(resp.getOutputTokens());
    }

    // ============================ TC-3: 429 限流（SE §9.5.5 点5）============================

    @Test
    @DisplayName("invoke HTTP 429: 抛 RuntimeException 含 status + error.code + model")
    void invoke_http429_throwsWithStatusAndCode() {
        String errJson = "{\"error\":{\"code\":\"1113\",\"message\":\"请求过于频繁，请稍后再试\"}}";
        server.enqueue(new MockResponse().setBody(errJson).setResponseCode(429)
                .addHeader("Content-Type", "application/json"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.invoke("glm-4-plus", "trigger rate limit", null));
        String msg = ex.getMessage();
        assertTrue(msg.contains("status=429"), "异常 message 应含 HTTP status=429");
        assertTrue(msg.contains("1113"), "异常 message 应含限流错误码 1113");
        assertTrue(msg.contains("glm-4-plus"), "异常 message 应含 model 名");
    }

    @Test
    @DisplayName("invoke HTTP 500: 抛 RuntimeException 含 status")
    void invoke_http500_throwsWithStatus() {
        server.enqueue(new MockResponse().setBody("{\"error\":{\"code\":\"1304\",\"message\":\"服务内部错误\"}}").setResponseCode(500));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.invoke("glm-4", "trigger 500", null));
        assertTrue(ex.getMessage().contains("status=500"));
        assertTrue(ex.getMessage().contains("glm-4"));
    }

    // ============================ TC-4: 超时（SE §9.5.5 点6）============================

    @Test
    @DisplayName("invoke 超时: readTimeout 先于响应触发，抛 RuntimeException 含超时语义")
    void invoke_readTimeout_throwsWrapped() {
        // 服务端延迟 2s 才返回，但客户端 readTimeout=300ms，必超时
        server.enqueue(new MockResponse().setBody("{\"choices\":[]}")
                .setBodyDelay(2, TimeUnit.SECONDS).setResponseCode(200));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.invoke("glm-4-flash", "will timeout",
                        LlmOptions.builder().timeoutMs(300L).build()));
        // SimpleClientHttpRequestFactory 超时抛 SocketTimeoutException，经 Spring RestClient 包装为
        // ResourceAccessException（NestedRuntimeException，被 invoke 的 catch(RuntimeException) 透传）。
        // 两种合法路径都接受：① message/cause 链含 SocketTimeoutException ② message 含 "timed out"。
        boolean isTimeout = hasCauseType(ex, "java.net.SocketTimeoutException")
                || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out"));
        assertTrue(isTimeout, "超时异常应含 SocketTimeoutException 或 'timed out' 语义，实际: "
                + ex.getClass().getName() + " / " + ex.getMessage());
    }

    /** 递归检查异常 cause 链是否含指定类型名（避免测试硬依赖具体异常类的 import）。 */
    private static boolean hasCauseType(Throwable t, String typeName) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 20) {
            if (cur.getClass().getName().equals(typeName)) return true;
            cur = cur.getCause();
        }
        return false;
    }

    // ============================ TC-6: api-key 空 isAvailable=false（SE §9.5.5 点7）============================

    @Test
    @DisplayName("isAvailable: apiKey 非空 true / 空 false / null false")
    void isAvailable_flipsWithApiKey() throws Exception {
        // setUp 注入了 test-api-key-placeholder
        assertTrue(adapter.isAvailable());

        setField(adapter, "apiKey", "");
        assertFalse(adapter.isAvailable(), "apiKey 空串应 unavailable");

        setField(adapter, "apiKey", null);
        assertFalse(adapter.isAvailable(), "apiKey null 应 unavailable");
    }

    @Test
    @DisplayName("invoke apiKey 空: 抛 IllegalStateException（不连真实网络）")
    void invoke_emptyApiKey_throwsIllegalState() throws Exception {
        setField(adapter, "apiKey", "");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adapter.invoke("glm-4-plus", "should not hit network", null));
        assertTrue(ex.getMessage().contains("GLM API Key 未配置"));
    }

    @Test
    @DisplayName("invoke apiKey null: 抛 IllegalStateException")
    void invoke_nullApiKey_throwsIllegalState() throws Exception {
        setField(adapter, "apiKey", null);
        assertThrows(IllegalStateException.class,
                () -> adapter.invoke("glm-4-plus", "should not hit network", null));
    }

    // ============================ 辅助方法 ============================

    /** 反射注入 private 字段（@Value 字段单测无 Spring 容器，需手动注入）。 */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = GlmLlmAdapter.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
