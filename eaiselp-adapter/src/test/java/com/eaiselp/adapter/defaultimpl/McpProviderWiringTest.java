package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.MCPAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP provider 条件装配测试（case-20260822-MCPMock 裁决 Q1）。
 *
 * <p>验证 enabled 总开关 + provider 切换的组合装配：
 * <ul>
 *   <li>enabled 缺省（false）：不装配任何 MCPAdapter（生产最小暴露）</li>
 *   <li>enabled=true 且 provider 缺省/mock：装配 MockMCPProvider（不装配 Http）</li>
 *   <li>enabled=true 且 provider=http：装配 HttpMCPAdapter（不装配 Mock）</li>
 *   <li>仅 provider=mock 而 enabled 缺省：不装配（总开关优先）</li>
 * </ul>
 */
class McpProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockMCPProvider.class, HttpMCPAdapter.class, StubMCPAdapter.class);

    @Test
    @DisplayName("enabled 缺省: 不装配任何 MCPAdapter（默认关闭，生产最小暴露）")
    void enabled缺省_不装配() {
        runner.run(ctx -> {
            assertEquals(0, ctx.getBeansOfType(MCPAdapter.class).size(), "未启用不应装配 MCPAdapter");
            assertEquals(0, ctx.getBeansOfType(MockMCPProvider.class).size());
            assertEquals(0, ctx.getBeansOfType(HttpMCPAdapter.class).size());
        });
    }

    @Test
    @DisplayName("enabled=true 且 provider 缺省: 装配 MockMCPProvider（默认 mock），不装配 Http")
    void enabled_true_provider缺省_装配Mock() {
        runner.withPropertyValues("eaiselp.adapter.mcp.enabled=true").run(ctx -> {
            assertEquals(1, ctx.getBeansOfType(MockMCPProvider.class).size(), "provider 缺省视为 mock");
            assertEquals(0, ctx.getBeansOfType(HttpMCPAdapter.class).size(), "不应装配 Http（互斥）");
            assertEquals(1, ctx.getBeansOfType(StubMCPAdapter.class).size(), "Stub 占位仍装配（isAvailable=false）");
        });
    }

    @Test
    @DisplayName("enabled=true + provider=mock: 装配 MockMCPProvider，不装配 Http")
    void enabled_true_provider_mock() {
        runner.withPropertyValues(
                        "eaiselp.adapter.mcp.enabled=true",
                        "eaiselp.adapter.mcp.provider=mock")
                .run(ctx -> {
                    assertEquals(1, ctx.getBeansOfType(MockMCPProvider.class).size());
                    assertEquals(0, ctx.getBeansOfType(HttpMCPAdapter.class).size());
                });
    }

    @Test
    @DisplayName("enabled=true + provider=http: 装配 HttpMCPAdapter，不装配 Mock")
    void enabled_true_provider_http() {
        runner.withPropertyValues(
                        "eaiselp.adapter.mcp.enabled=true",
                        "eaiselp.adapter.mcp.provider=http")
                .run(ctx -> {
                    assertEquals(1, ctx.getBeansOfType(HttpMCPAdapter.class).size());
                    assertEquals(0, ctx.getBeansOfType(MockMCPProvider.class).size(), "mock 与 http 按 provider 互斥");
                });
    }

    @Test
    @DisplayName("仅 provider=mock 而 enabled 缺省: 不装配（总开关优先，默认关闭不变）")
    void 仅provider_mock_enabled缺省_不装配() {
        runner.withPropertyValues("eaiselp.adapter.mcp.provider=mock").run(ctx -> {
            assertEquals(0, ctx.getBeansOfType(MockMCPProvider.class).size(), "总开关未开不装配");
        });
    }

    @Test
    @DisplayName("provider=mock 时装配的 Mock bean isAvailable=true（工厂 pick 可选中）")
    void mock装配后可用() {
        runner.withPropertyValues("eaiselp.adapter.mcp.enabled=true").run(ctx -> {
            MockMCPProvider mock = ctx.getBean(MockMCPProvider.class);
            assertTrue(mock.isAvailable());
            assertEquals("mock", mock.getProvider());
            assertEquals(9, mock.listTools().size());
        });
    }
}
