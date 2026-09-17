package com.eaiselp.runtime.controller;

import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.adapter.spi.MCPAdapter;
import com.eaiselp.common.result.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MCPController 单测。
 *
 * <p>核心验证：</p>
 * <ul>
 *   <li>MCP 未配置（getMCPAdapter 返回 null）→ 降级 enabled=false，不 500</li>
 *   <li>MCP 配置但 isAvailable=false → 同样降级</li>
 *   <li>listTools 正常返回工具清单</li>
 *   <li>invoke 参数校验：name 为空 → 400</li>
 *   <li>invoke 工具返回 null → success=false 降级</li>
 *   <li>invoke 工具正常 → success=true + result</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MCPControllerTest {

    @Mock AdapterFactory adapterFactory;

    @InjectMocks MCPController controller;

    // ===== tools() =====

    @Test
    void tools_MCP未配置_降级返回enabled_false() {
        when(adapterFactory.getMCPAdapter()).thenReturn(null);

        R<Map<String, Object>> result = controller.tools();

        assertEquals(0, result.getCode());
        assertEquals(false, result.getData().get("enabled"));
        assertNotNull(result.getData().get("message"), "应返回降级提示文案");
    }

    @Test
    void tools_MCP配置但不可用_降级返回() {
        MCPAdapter mcp = mock(MCPAdapter.class);
        when(adapterFactory.getMCPAdapter()).thenReturn(mcp);
        when(mcp.isAvailable()).thenReturn(false);

        R<Map<String, Object>> result = controller.tools();

        assertEquals(false, result.getData().get("enabled"));
    }

    @Test
    void tools_MCP可用_返回工具清单() {
        MCPAdapter mcp = mock(MCPAdapter.class);
        when(adapterFactory.getMCPAdapter()).thenReturn(mcp);
        when(mcp.isAvailable()).thenReturn(true);
        when(mcp.getProvider()).thenReturn("http-mcp");
        when(mcp.listTools()).thenReturn(List.of(
                MCPAdapter.ToolInfo.builder().name("search").description("搜索").build()
        ));

        R<Map<String, Object>> result = controller.tools();

        assertEquals(true, result.getData().get("enabled"));
        assertEquals("http-mcp", result.getData().get("provider"));
        @SuppressWarnings("unchecked")
        List<MCPAdapter.ToolInfo> tools = (List<MCPAdapter.ToolInfo>) result.getData().get("tools");
        assertEquals(1, tools.size());
        assertEquals("search", tools.get(0).getName());
    }

    // ===== invoke() 参数校验 =====

    @Test
    void invoke_请求为null_返回400() {
        R<Map<String, Object>> result = controller.invoke(null);

        assertEquals(400, result.getCode());
    }

    @Test
    void invoke_name为空_返回400() {
        MCPController.InvokeRequest req = new MCPController.InvokeRequest();
        req.setName("");

        R<Map<String, Object>> result = controller.invoke(req);

        assertEquals(400, result.getCode());
    }

    @Test
    void invoke_name为空格_返回400() {
        MCPController.InvokeRequest req = new MCPController.InvokeRequest();
        req.setName("   ");

        R<Map<String, Object>> result = controller.invoke(req);

        assertEquals(400, result.getCode());
    }

    // ===== invoke() 降级 =====

    @Test
    void invoke_MCP未配置_降级返回() {
        when(adapterFactory.getMCPAdapter()).thenReturn(null);
        MCPController.InvokeRequest req = new MCPController.InvokeRequest();
        req.setName("search");

        R<Map<String, Object>> result = controller.invoke(req);

        assertEquals(false, result.getData().get("enabled"));
    }

    // ===== invoke() 正常 =====

    @Test
    void invoke_工具返回null_降级success_false() {
        MCPAdapter mcp = mock(MCPAdapter.class);
        when(adapterFactory.getMCPAdapter()).thenReturn(mcp);
        when(mcp.isAvailable()).thenReturn(true);
        when(mcp.getProvider()).thenReturn("http-mcp");
        when(mcp.invokeTool(eq("search"), any())).thenReturn(null);

        MCPController.InvokeRequest req = new MCPController.InvokeRequest();
        req.setName("search");

        R<Map<String, Object>> result = controller.invoke(req);

        assertEquals(true, result.getData().get("enabled"));
        assertEquals(false, result.getData().get("success"));
    }

    @Test
    void invoke_工具正常返回_success_true() {
        MCPAdapter mcp = mock(MCPAdapter.class);
        when(adapterFactory.getMCPAdapter()).thenReturn(mcp);
        when(mcp.isAvailable()).thenReturn(true);
        when(mcp.getProvider()).thenReturn("http-mcp");
        when(mcp.invokeTool(eq("search"), any())).thenReturn(Map.of("hits", 42));

        MCPController.InvokeRequest req = new MCPController.InvokeRequest();
        req.setName("search");
        req.setParams(Map.of("query", "test"));

        R<Map<String, Object>> result = controller.invoke(req);

        assertEquals(true, result.getData().get("enabled"));
        assertEquals(true, result.getData().get("success"));
        assertEquals(Map.of("hits", 42), result.getData().get("result"));
    }

    // ===== invoke() 参数单值 10KB 上限（case-20260824 T9，MCP-S1） =====

    @Test
    void T9_参数单值超10KB_结构化错误32602_不触达适配器() {
        MCPController.InvokeRequest req = new MCPController.InvokeRequest();
        req.setName("confluence.create_page");
        req.setParams(Map.of("content", "x".repeat(10 * 1024 + 1))); // 恰超 10240 字节

        R<Map<String, Object>> result = controller.invoke(req);

        assertEquals(0, result.getCode(), "R 层正常（业务级结构化错误，非 HTTP 500）");
        assertEquals(false, result.getData().get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) result.getData().get("error");
        assertNotNull(err, "JSON-RPC 结构化错误对象");
        assertEquals(-32602, err.get("code"), "invalid params 错误码 -32602");
        assertTrue(String.valueOf(err.get("message")).contains("content"), "指名超限参数");
        // fail-fast：超限校验先于适配器获取——不消耗适配器/外部调用
        verifyNoInteractions(adapterFactory);
    }

    @Test
    void T9_参数恰等10KB_合法放行() {
        MCPAdapter mcp = mock(MCPAdapter.class);
        when(adapterFactory.getMCPAdapter()).thenReturn(mcp);
        when(mcp.isAvailable()).thenReturn(true);
        when(mcp.getProvider()).thenReturn("http-mcp");
        when(mcp.invokeTool(anyString(), any())).thenReturn(Map.of("ok", true));

        MCPController.InvokeRequest req = new MCPController.InvokeRequest();
        req.setName("confluence.create_page");
        req.setParams(Map.of("content", "x".repeat(10 * 1024))); // 恰等上限

        R<Map<String, Object>> result = controller.invoke(req);

        assertEquals(true, result.getData().get("success"), "恰等 10KB 不拒（> 才拒）");
        verify(mcp).invokeTool(eq("confluence.create_page"), any());
    }

    @Test
    void T9_非字符串超限值_JSON序列化字节数判定() {
        // 非 String 值（List）按 JSON 序列化后 UTF-8 字节数判——大负载同样受限
        java.util.List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            chunks.add("0123456789".repeat(7)); // 每条 70 字符 + 引号/逗号 → 合计 > 10KB
        }
        MCPController.InvokeRequest req = new MCPController.InvokeRequest();
        req.setName("jira.create_issue");
        req.setParams(Map.of("description", chunks));

        R<Map<String, Object>> result = controller.invoke(req);

        assertEquals(false, result.getData().get("success"), "非 String 值按 JSON 字节数同判");
        verifyNoInteractions(adapterFactory);
    }

    @Test
    void T9_null与空params_恒合法() {
        assertNull(MCPController.findOversizedParam(null));
        assertNull(MCPController.findOversizedParam(Map.of()));
        java.util.Map<String, Object> withNullValue = new java.util.HashMap<>();
        withNullValue.put("a", null);
        withNullValue.put("b", "短值");
        assertNull(MCPController.findOversizedParam(withNullValue), "null 值参数不判超限（跳过）");
    }
}
