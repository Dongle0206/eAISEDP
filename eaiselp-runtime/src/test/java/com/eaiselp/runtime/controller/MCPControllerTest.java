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
}
