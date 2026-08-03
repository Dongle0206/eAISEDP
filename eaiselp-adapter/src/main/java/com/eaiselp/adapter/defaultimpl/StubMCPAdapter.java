package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.MCPAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link MCPAdapter} 的 stub 默认实现。
 *
 * <p>用途：未对接 MCP server 时占位，保证 SPI 链路完整、工厂可选注入不报"无 Bean"。
 * 所有方法记录 warn 后返回 null/false/空表，不真实注册或调用工具。
 *
 * <p>条件装配：仅当 {@code eaiselp.adapter.mcp.enabled=true} 时生效（默认不启用，
 * 企业接入真实 MCP server 时配 enabled=true 或直接提供自研 Bean 覆盖）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.adapter.mcp.enabled", havingValue = "true", matchIfMissing = false)
public class StubMCPAdapter implements MCPAdapter {

    @Override public String getType() { return "mcp"; }
    @Override public String getProvider() { return "stub"; }
    @Override public boolean isAvailable() { return false; }

    @Override
    public boolean registerTool(String name, String description, Map<String, Object> schema) {
        log.warn("[MCPAdapter-Stub] registerTool 未实现: name={}", name);
        return false;
    }

    @Override
    public Object invokeTool(String name, Map<String, Object> params) {
        log.warn("[MCPAdapter-Stub] invokeTool 未实现: name={}", name);
        return null;
    }

    @Override
    public List<ToolInfo> listTools() {
        log.warn("[MCPAdapter-Stub] listTools 未实现");
        return Collections.emptyList();
    }
}
