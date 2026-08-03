package com.eaiselp.adapter.spi;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）适配器 SPI。
 *
 * <p>EA 蓝图 §4.3 适配器体系第 7 类：用 Model Context Protocol 标准对接外部
 * 工具/数据源，让体系与任意 MCP server（官方或第三方）即插即用，无需每集成写死。
 *
 * <p>遵循 P3 依赖单向：接口定义在 adapter 模块，企业自研实现按 SPI 装配。
 * 默认 stub 实现 {@code StubMCPAdapter} 默认不启用
 * （{@code eaiselp.adapter.mcp.enabled=true} 才装配）。
 *
 * <p>方法语义对齐 MCP：tool 的 name/description/JSON-Schema 为协议字段。
 */
public interface MCPAdapter extends Adapter {
    /**
     * 注册 MCP 工具。
     *
     * @param name        工具名（MCP 协议字段，唯一标识）
     * @param description 工具描述（LLM 据此决定何时调用）
     * @param schema      参数 JSON-Schema（MCP 协议字段）
     * @return 是否注册成功
     */
    boolean registerTool(String name, String description, Map<String, Object> schema);

    /**
     * 调用 MCP 工具。
     *
     * @param name   工具名
     * @param params 调用参数（与 schema 对齐）
     * @return 工具返回结果（结构由 provider/工具决定）；失败返回 null
     */
    Object invokeTool(String name, Map<String, Object> params);

    /**
     * 列出可用工具。
     *
     * @return 工具清单（无结果返回空表）
     */
    List<ToolInfo> listTools();

    /** MCP 工具元信息（对齐协议字段）。 */
    @Data
    @Builder
    class ToolInfo {
        private String name;
        private String description;
        private Map<String, Object> schema;
    }
}
