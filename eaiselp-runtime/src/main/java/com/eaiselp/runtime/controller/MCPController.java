package com.eaiselp.runtime.controller;

import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.adapter.spi.MCPAdapter;
import com.eaiselp.common.ratelimit.RateLimit;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）工具调用 REST API（M4-2）。
 *
 * <p>暴露平台对接的外部 MCP Server 工具能力，前端/上游可经统一 REST 接口列出与调用
 * MCP 工具，无需直接对接 JSON-RPC。底层经 {@link MCPAdapter} SPI 走 HTTP + JSON-RPC 2.0。
 *
 * <p><b>接口</b>：
 * <ul>
 *   <li>{@code GET  /api/v1/mcp/tools}  → 列出 MCP Server 可用工具（{@link MCPAdapter#listTools}）。</li>
 *   <li>{@code POST /api/v1/mcp/invoke} → 调用指定 MCP 工具（{@link MCPAdapter#invokeTool}）。</li>
 * </ul>
 *
 * <p><b>MCP 未配置时的降级</b>：当 {@code eaiselp.adapter.mcp.enabled=false}（默认）或
 * server-url 未配时，{@link AdapterFactory#getMCPAdapter()} 返回 null。本 Controller
 * 不抛 500，而是返回 {@code R.ok} 带 {@code enabled=false} 占位 + 提示文案，让前端正常渲染
 * "未配置 MCP" 态（与 {@code AdapterController#infoOptional} 口径一致）。
 *
 * <p><b>权限</b>：需 {@code adapter:view}（与适配器查看口径一致；MCP 工具调用属于适配器能力范畴）。
 *
 * <p><b>限流</b>：invoke 为外部 HTTP 调用（可能触发 LLM/检索，开销大），按用户维度限 30 次/分
 * （SE §4.2.3 通用配置；外部调用比本地 DB 更贵，限流更紧）。listTools 是轻量读，限 60 次/分。
 *
 * <p>多租户隔离（ES-003 §9.3 P11）：MCP 工具调用经适配器 SPI，租户隔离由具体 MCP Server
 * 端实现（平台不伪造 tenant_id 注入 JSON-RPC，遵循 P7 唯一调度入口）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
public class MCPController {

    /**
     * 适配器工厂（M1 模块化单体：同进程 bean 注入，ES-001 §4.4）。
     * 通过工厂选首个 {@code isAvailable()} 的 MCPAdapter，避免直接注入多个 MCPAdapter Bean
     * 产生歧义（Stub + Http 共存时由工厂按可用性裁决）。
     */
    private final AdapterFactory adapterFactory;

    /**
     * 列出 MCP Server 可用工具。
     *
     * @return 工具清单（name/description/schema）；MCP 未配置时返回空表 + enabled=false 占位。
     */
    @GetMapping("/tools")
    @RequirePermission("adapter:view")
    @RateLimit(name = "mcp-list", key = RateLimit.KeyType.USER,
            capacity = 60, refillPerMin = 60,
            message = "MCP 工具列表请求过于频繁，请稍后再试")
    public R<Map<String, Object>> tools() {
        MCPAdapter mcp = adapterFactory.getMCPAdapter();
        if (mcp == null || !mcp.isAvailable()) {
            return R.ok(unavailable("未配置 MCP（eaiselp.adapter.mcp.enabled=false 或 server-url 为空）", Collections.emptyList()));
        }
        List<MCPAdapter.ToolInfo> tools = mcp.listTools();
        return R.ok(Map.of(
                "enabled", true,
                "provider", mcp.getProvider(),
                "tools", tools
        ));
    }

    /**
     * 调用 MCP 工具。
     *
     * @param req 工具名 + 参数（{@link InvokeRequest#name} 必填，{@link InvokeRequest#params} 可空）
     * @return 工具返回结果（结构由具体工具决定）；MCP 未配置或调用失败时返回降级态。
     */
    @PostMapping("/invoke")
    @RequirePermission("adapter:view")
    @RateLimit(name = "mcp-invoke", key = RateLimit.KeyType.USER,
            capacity = 30, refillPerMin = 30,
            message = "MCP 工具调用请求过于频繁，请稍后再试")
    public R<Map<String, Object>> invoke(@RequestBody InvokeRequest req) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            return R.fail(400, "工具名 name 不能为空");
        }
        MCPAdapter mcp = adapterFactory.getMCPAdapter();
        if (mcp == null || !mcp.isAvailable()) {
            return R.ok(unavailable("未配置 MCP（eaiselp.adapter.mcp.enabled=false 或 server-url 为空）", null));
        }
        Object result = mcp.invokeTool(req.getName(), req.getParams());
        if (result == null) {
            // invokeTool 失败返回 null（适配器内部已 log），前端按 success=false 提示
            return R.ok(Map.of(
                    "enabled", true,
                    "provider", mcp.getProvider(),
                    "name", req.getName(),
                    "success", false,
                    "message", "工具调用失败或返回空（详见服务端日志）"
            ));
        }
        return R.ok(Map.of(
                "enabled", true,
                "provider", mcp.getProvider(),
                "name", req.getName(),
                "success", true,
                "result", result
        ));
    }

    /** 构造 MCP 未配置/不可用时的降级响应体（不让接口 500）。 */
    private Map<String, Object> unavailable(String message, Object fallback) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", false);
        m.put("message", message);
        m.put("tools", fallback);
        return m;
    }

    /**
     * 工具调用入参。
     *
     * <p>{@code params} 对齐 MCP {@code tools/call} 的 {@code arguments} 字段，
     * 结构由工具的 inputSchema 约束（前端据 listTools 返回的 schema 渲染表单）。
     */
    @Data
    public static class InvokeRequest {
        /** 工具名（必填，来自 listTools）。 */
        private String name;
        /** 调用参数（可空；缺省时适配器传空 Map）。 */
        private Map<String, Object> params;
    }
}
