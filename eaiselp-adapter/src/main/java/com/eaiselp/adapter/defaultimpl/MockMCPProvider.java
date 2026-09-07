package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.MCPAdapter;
import com.eaiselp.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link MCPAdapter} 的本地 Mock 实现（case-20260822-MCPMock，F1）。
 *
 * <p>预置 Jira / Confluence / GitLab 三源共 9 个工具的内存模拟，让"编排调外部工具"在
 * 无真实外部系统时可端到端演示与联调（PRG-005 前置演示层）。同时作为
 * {@link HttpMCPAdapter} 本地回环测试靶，固化 JSON-RPC 2.0 契约（F3）。
 *
 * <p><b>装配</b>（编排者裁决 Q1）：{@code eaiselp.adapter.mcp.enabled=true}（总开关，
 * 默认 false 不变）且 {@code eaiselp.adapter.mcp.provider=mock}（默认 mock，
 * {@code matchIfMissing=true}）时装配。provider=http 时装配 {@link HttpMCPAdapter}，
 * 二者按 provider 互斥（{@link ProviderMockCondition} = AllNestedConditions 组合两个
 * {@code @ConditionalOnProperty}，AND 语义对齐既有 enabled 单开关写法）。
 *
 * <p><b>租户隔离</b>（AC-05 / ES-003 §9.3 P11）：数据按 {@link TenantContext} 取
 * tenant_id 分桶（{@code ConcurrentHashMap<Long, TenantStore>}），列表只读本租户桶，
 * 按 id 直取跨租户返回 NOT_FOUND 类结构化错误且不携带他租户任何字段。
 *
 * <p><b>id 全局唯一</b>：各实体 id 序列为<b>进程级</b>（跨租户不重号），配合分桶保证
 * "他租户的 id 在本租户桶内必然不存在"——隔离语义不依赖数据巧合。
 *
 * <p><b>存储边界</b>（AC-06）：纯内存态，无表 / 无 DDL / 无迁移脚本，应用重启即清空
 * （list 类工具返回空列表）。
 *
 * <p><b>mock 标识</b>（裁决 Q2）：所有响应顶层携带 {@code "__mock": true}，
 * 真实 server 响应无该字段——前端/测试据此区分 mock 与真实。
 *
 * <p><b>错误结构</b>（AC-07）：对齐 JSON-RPC 2.0 错误对象 {@code {code, message}}，
 * 不抛异常、不返回半成品成功对象。错误码约定：
 * <ul>
 *   <li>{@code -32601} TOOL_NOT_FOUND——工具名不存在（对齐 JSON-RPC method not found）</li>
 *   <li>{@code -32602} INVALID_PARAMS——缺失/非法必填参数（对齐 JSON-RPC invalid params）</li>
 *   <li>{@code -32603} INTERNAL——处理器内部异常（对齐 JSON-RPC internal error）</li>
 *   <li>{@code -32004} NOT_FOUND——id 在<b>本租户</b>内不存在（JSON-RPC server error 区间）</li>
 * </ul>
 *
 * <p><b>工具 schema 附录</b>（裁决 Q3 定稿：最小原则，每工具 1~3 个必填字符串参数 +
 * 可选 description；F2 动态表单与 F3 契约测试共用本表）：
 *
 * <table border="1">
 *   <caption>预置 9 工具入参 schema</caption>
 *   <tr><th>工具名</th><th>必填（string）</th><th>可选（string）</th></tr>
 *   <tr><td>jira.create_issue</td><td>summary</td><td>description</td></tr>
 *   <tr><td>jira.list_issues</td><td>（无）</td><td>（无）</td></tr>
 *   <tr><td>jira.get_issue</td><td>id</td><td>（无）</td></tr>
 *   <tr><td>confluence.create_page</td><td>title, content</td><td>description</td></tr>
 *   <tr><td>confluence.get_page</td><td>id</td><td>（无）</td></tr>
 *   <tr><td>confluence.search_pages</td><td>keyword</td><td>（无）</td></tr>
 *   <tr><td>gitlab.create_mr</td><td>title, source_branch, target_branch</td><td>description</td></tr>
 *   <tr><td>gitlab.list_mrs</td><td>（无）</td><td>（无）</td></tr>
 *   <tr><td>gitlab.create_issue</td><td>title</td><td>description</td></tr>
 * </table>
 *
 * <p>id 生成：进程级每实体独立自增序列（跨租户唯一不重号），形如 {@code JIRA-1} /
 * {@code PAGE-1} / {@code MR-1} / {@code GL-1}。
 */
@Slf4j
@Component
@Conditional(MockMCPProvider.ProviderMockCondition.class)
public class MockMCPProvider implements MCPAdapter {

    /**
     * 装配条件（AND）：enabled=true 且 provider=mock（provider 缺省视为 mock，裁决 Q1）。
     * 用 {@link AllNestedConditions} 组合两个 {@code @ConditionalOnProperty}
     * （Boot 3.2 该注解不可重复，嵌套条件类是标准替代写法）。
     */
    static class ProviderMockCondition extends AllNestedConditions {
        ProviderMockCondition() { super(ConfigurationPhase.REGISTER_BEAN); }

        @ConditionalOnProperty(name = "eaiselp.adapter.mcp.enabled", havingValue = "true")
        static class Enabled { }

        @ConditionalOnProperty(name = "eaiselp.adapter.mcp.provider", havingValue = "mock", matchIfMissing = true)
        static class ProviderMock { }
    }

    /** mock 标识字段（裁决 Q2）：所有响应顶层携带 {@code "__mock": true}。 */
    public static final String MOCK_FLAG = "__mock";

    /** JSON-RPC 2.0 保留错误码：method not found（工具名不存在）。 */
    public static final int CODE_TOOL_NOT_FOUND = -32601;
    /** JSON-RPC 2.0 保留错误码：invalid params（缺失/非法必填参数）。 */
    public static final int CODE_INVALID_PARAMS = -32602;
    /** JSON-RPC 2.0 保留错误码：internal error（处理器内部异常兜底）。 */
    public static final int CODE_INTERNAL = -32603;
    /** JSON-RPC server error 区间（-32000~-32099）：id 在本租户内不存在。 */
    public static final int CODE_NOT_FOUND = -32004;

    /** 工具注册表（启动时预置 9 工具；registerTool 可追加，name 唯一）。 */
    private final Map<String, ToolInfo> tools = new ConcurrentHashMap<>();

    /** 租户分桶：tenant_id → 该租户全部 mock 数据（AC-05 隔离边界）。 */
    private final ConcurrentHashMap<Long, TenantStore> tenants = new ConcurrentHashMap<>();

    /** 实体 id 进程级序列（跨租户唯一，AC-05 语义前提）。 */
    private final AtomicLong issueSeq = new AtomicLong();
    private final AtomicLong pageSeq = new AtomicLong();
    private final AtomicLong mrSeq = new AtomicLong();
    private final AtomicLong gitlabIssueSeq = new AtomicLong();

    /** 构造时注册预置 9 工具（裁决技术要点 1：启动时注册）。 */
    public MockMCPProvider() {
        registerTool("jira.create_issue", "创建 Jira Issue（mock）",
                schema(Map.of(
                        "summary", "Issue 标题（必填）",
                        "description", "Issue 描述（可选）"), "summary"));
        registerTool("jira.list_issues", "列出本租户全部 Jira Issue（mock）", schema(Map.of()));
        registerTool("jira.get_issue", "按 id 获取 Jira Issue（mock）",
                schema(Map.of("id", "Issue id，如 JIRA-1（必填）"), "id"));
        registerTool("confluence.create_page", "创建 Confluence 页面（mock）",
                schema(Map.of(
                        "title", "页面标题（必填）",
                        "content", "页面内容（必填）",
                        "description", "页面说明（可选）"), "title", "content"));
        registerTool("confluence.get_page", "按 id 获取 Confluence 页面（mock）",
                schema(Map.of("id", "页面 id，如 PAGE-1（必填）"), "id"));
        registerTool("confluence.search_pages", "按关键字搜索本租户页面（标题/内容匹配，mock）",
                schema(Map.of("keyword", "搜索关键字（必填）"), "keyword"));
        registerTool("gitlab.create_mr", "创建 GitLab Merge Request（mock）",
                schema(Map.of(
                        "title", "MR 标题（必填）",
                        "source_branch", "源分支（必填）",
                        "target_branch", "目标分支（必填）",
                        "description", "MR 描述（可选）"), "title", "source_branch", "target_branch"));
        registerTool("gitlab.list_mrs", "列出本租户全部 Merge Request（mock）", schema(Map.of()));
        registerTool("gitlab.create_issue", "创建 GitLab Issue（mock，与 jira.create_issue 同语义）",
                schema(Map.of(
                        "title", "Issue 标题（必填）",
                        "description", "Issue 描述（可选）"), "title"));
        log.info("[MCPAdapter-Mock] 预置工具注册完成: count=9 (jira=3, confluence=3, gitlab=3)");
    }

    @Override public String getType() { return "mcp"; }
    @Override public String getProvider() { return "mock"; }

    /** Mock 无外部依赖（无 server-url），装配即可用。 */
    @Override public boolean isAvailable() { return true; }

    @Override
    public boolean registerTool(String name, String description, Map<String, Object> schema) {
        if (name == null || name.isBlank()) {
            log.warn("[MCPAdapter-Mock] registerTool 拒绝：name 为空");
            return false;
        }
        ToolInfo info = ToolInfo.builder()
                .name(name)
                .description(description == null ? "" : description)
                .schema(schema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(schema))
                .build();
        boolean ok = tools.putIfAbsent(name, info) == null;
        if (ok) {
            log.info("[MCPAdapter-Mock] registerTool 成功: name={}", name);
        } else {
            log.warn("[MCPAdapter-Mock] registerTool 重复: name={}（保持原注册）", name);
        }
        return ok;
    }

    @Override
    public List<ToolInfo> listTools() {
        List<ToolInfo> list = new ArrayList<>(tools.values());
        list.sort(Comparator.comparing(ToolInfo::getName));
        return list;
    }

    @Override
    public Object invokeTool(String name, Map<String, Object> params) {
        if (name == null || name.isBlank()) {
            return error(CODE_INVALID_PARAMS, "工具名 name 不能为空");
        }
        ToolInfo tool = tools.get(name);
        if (tool == null) {
            return error(CODE_TOOL_NOT_FOUND, "未知的 MCP 工具: " + name);
        }
        List<String> missing = missingRequired(tool.getSchema(), params);
        if (!missing.isEmpty()) {
            return error(CODE_INVALID_PARAMS, "缺少必填参数: " + String.join(", ", missing)
                    + "（工具 " + name + "）");
        }
        try {
            return dispatch(name, params == null ? Map.of() : params);
        } catch (Exception e) {
            // 兜底：处理器异常转结构化错误（AC-07 不抛 500）
            log.error("[MCPAdapter-Mock] invokeTool 内部异常 name={}, err={}", name, e.getMessage(), e);
            return error(CODE_INTERNAL, "mock 工具内部错误: " + e.getMessage());
        }
    }

    // ============================ 工具分发（固定预置逻辑，PRD §2 F1） ============================

    private Object dispatch(String name, Map<String, Object> params) {
        TenantStore st = store();
        switch (name) {
            case "jira.create_issue": return jiraCreateIssue(st, str(params, "summary"), str(params, "description"));
            case "jira.list_issues": return jiraListIssues(st);
            case "jira.get_issue": return jiraGetIssue(st, str(params, "id"));
            case "confluence.create_page": return confluenceCreatePage(st, str(params, "title"), str(params, "content"), str(params, "description"));
            case "confluence.get_page": return confluenceGetPage(st, str(params, "id"));
            case "confluence.search_pages": return confluenceSearchPages(st, str(params, "keyword"));
            case "gitlab.create_mr": return gitlabCreateMr(st, str(params, "title"), str(params, "source_branch"), str(params, "target_branch"), str(params, "description"));
            case "gitlab.list_mrs": return gitlabListMrs(st);
            case "gitlab.create_issue": return gitlabCreateIssue(st, str(params, "title"), str(params, "description"));
            default: return error(CODE_TOOL_NOT_FOUND, "未实现的 mock 工具: " + name);
        }
    }

    private Object jiraCreateIssue(TenantStore st, String summary, String description) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", "JIRA-" + issueSeq.incrementAndGet());
        rec.put("summary", summary);
        rec.put("description", description);
        rec.put("status", "open");
        synchronized (st.jiraIssues) { st.jiraIssues.add(rec); }
        return withMock(rec);
    }

    private Object jiraListIssues(TenantStore st) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        synchronized (st.jiraIssues) { st.jiraIssues.forEach(r -> snapshot.add(new LinkedHashMap<>(r))); }
        return listResult("issues", snapshot);
    }

    private Object jiraGetIssue(TenantStore st, String id) {
        Map<String, Object> rec = findById(st.jiraIssues, id);
        if (rec == null) {
            return error(CODE_NOT_FOUND, "issue 未找到: " + id);
        }
        return withMock(rec);
    }

    private Object confluenceCreatePage(TenantStore st, String title, String content, String description) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", "PAGE-" + pageSeq.incrementAndGet());
        rec.put("title", title);
        rec.put("content", content);
        rec.put("description", description);
        synchronized (st.pages) { st.pages.add(rec); }
        return withMock(rec);
    }

    private Object confluenceGetPage(TenantStore st, String id) {
        Map<String, Object> rec = findById(st.pages, id);
        if (rec == null) {
            return error(CODE_NOT_FOUND, "page 未找到: " + id);
        }
        return withMock(rec);
    }

    private Object confluenceSearchPages(TenantStore st, String keyword) {
        List<Map<String, Object>> hits = new ArrayList<>();
        synchronized (st.pages) {
            for (Map<String, Object> p : st.pages) {
                String title = String.valueOf(p.getOrDefault("title", ""));
                String content = String.valueOf(p.getOrDefault("content", ""));
                if (title.contains(keyword) || content.contains(keyword)) {
                    hits.add(new LinkedHashMap<>(p));
                }
            }
        }
        return listResult("pages", hits);
    }

    private Object gitlabCreateMr(TenantStore st, String title, String sourceBranch, String targetBranch, String description) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", "MR-" + mrSeq.incrementAndGet());
        rec.put("title", title);
        rec.put("source_branch", sourceBranch);
        rec.put("target_branch", targetBranch);
        rec.put("description", description);
        rec.put("state", "opened");
        synchronized (st.mrs) { st.mrs.add(rec); }
        return withMock(rec);
    }

    private Object gitlabListMrs(TenantStore st) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        synchronized (st.mrs) { st.mrs.forEach(r -> snapshot.add(new LinkedHashMap<>(r))); }
        return listResult("mrs", snapshot);
    }

    private Object gitlabCreateIssue(TenantStore st, String title, String description) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", "GL-" + gitlabIssueSeq.incrementAndGet());
        rec.put("title", title);
        rec.put("description", description);
        rec.put("status", "opened");
        synchronized (st.gitlabIssues) { st.gitlabIssues.add(rec); }
        return withMock(rec);
    }

    // ============================ 通用辅助 ============================

    /** 取本租户数据桶（无上下文时落到 SYSTEM_TENANT=0 系统桶，不越租户）。 */
    private TenantStore store() {
        return tenants.computeIfAbsent(TenantContext.get(), k -> new TenantStore());
    }

    /** 按 id 在指定实体列表中查找记录（仅本租户桶内，跨租户自然 NOT_FOUND）。 */
    private Map<String, Object> findById(List<Map<String, Object>> list, String id) {
        synchronized (list) {
            for (Map<String, Object> r : list) {
                if (String.valueOf(r.get("id")).equals(id)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 校验必填参数（schema.required）：缺失/null/空白串计入缺失清单。 */
    private List<String> missingRequired(Map<String, Object> schema, Map<String, Object> params) {
        List<String> missing = new ArrayList<>();
        if (schema == null) {
            return missing;
        }
        Object required = schema.get("required");
        if (!(required instanceof List<?> reqList)) {
            return missing;
        }
        for (Object r : reqList) {
            String key = String.valueOf(r);
            Object v = params == null ? null : params.get(key);
            if (v == null || (v instanceof String s && s.isBlank())) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** 响应加统一 mock 标识（裁决 Q2）：{@code {__mock:true, ...工具结果}}。 */
    private Map<String, Object> withMock(Map<String, Object> rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(MOCK_FLAG, true);
        out.putAll(rec);
        return out;
    }

    /** 列表类响应：{@code {__mock:true, <key>:[...], total:n}}（无命中返回空列表，AC-03）。 */
    private Map<String, Object> listResult(String key, List<Map<String, Object>> records) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(MOCK_FLAG, true);
        out.put(key, records);
        out.put("total", records.size());
        return out;
    }

    /** 结构化错误（AC-07）：{@code {__mock:true, error:{code,message}}}，不抛异常。 */
    private Map<String, Object> error(int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(MOCK_FLAG, true);
        out.put("error", err);
        return out;
    }

    /** 参数取值转字符串（null → 空串；非字符串标量转文本），mock 宽松收纳。 */
    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** 构造 JSON-Schema 形态的参数 schema（全 string 参数，Q3 最小原则）。 */
    private static Map<String, Object> schema(Map<String, String> props, String... required) {
        Map<String, Object> properties = new LinkedHashMap<>();
        props.forEach((k, desc) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "string");
            p.put("description", desc);
            properties.put(k, p);
        });
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(required));
        return schema;
    }

    /** 租户数据桶：实体列表（并发低频，synchronized 列表足够）；id 序列在 provider 级（跨租户唯一）。 */
    private static final class TenantStore {
        final List<Map<String, Object>> jiraIssues = new ArrayList<>();
        final List<Map<String, Object>> pages = new ArrayList<>();
        final List<Map<String, Object>> mrs = new ArrayList<>();
        final List<Map<String, Object>> gitlabIssues = new ArrayList<>();
    }
}
