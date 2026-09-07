package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.MCPAdapter.ToolInfo;
import com.eaiselp.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockMCPProvider 工具集单测（case-20260822-MCPMock，AC-01~AC-07 的 F1 部分）。
 *
 * <p>覆盖：注册元数据完整性 / 三源（Jira、Confluence、GitLab）调用链路 /
 * 租户隔离（含跨租户 id 直取 NOT_FOUND）/ 参数校验错误结构 / __mock 标识 / 内存边界。
 *
 * <p>租户上下文：TenantContext 为 ThreadLocal，测试内手动 set / clear，
 * 模拟 MCPController 请求线程经 TenantContextFilter 注入的租户身份。
 */
class MockMCPProviderTest {

    private static final long T1 = 1001L;
    private static final long T2 = 1002L;

    private MockMCPProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MockMCPProvider();
        TenantContext.set(T1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 在指定租户上下文内执行（模拟请求线程身份切换）。 */
    private <R> R asTenant(long tenantId, java.util.function.Supplier<R> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.set(T1);
        }
    }

    /** 断言 result 是结构化错误并返回 error 子 Map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> expectError(Object result, int expectedCode) {
        assertNotNull(result, "错误响应不应为 null");
        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        Object err = map.get("error");
        assertInstanceOf(Map.class, err, "错误响应应含 error 子对象: " + map);
        Map<String, Object> errMap = (Map<String, Object>) err;
        assertEquals(expectedCode, ((Number) errMap.get("code")).intValue(), "错误码应可判读: " + errMap);
        assertNotNull(errMap.get("message"), "错误 message 不应为空");
        assertFalse(errMap.get("message").toString().isBlank());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object result) {
        assertInstanceOf(Map.class, result);
        return (Map<String, Object>) result;
    }

    // ============================ AC-01 注册与元数据 ============================

    @Test
    @DisplayName("listTools 返回恰好 9 个工具，名称/描述/参数 schema 完整")
    void listTools_返回恰好9个工具_元数据完整() {
        List<ToolInfo> tools = provider.listTools();

        assertEquals(9, tools.size(), "应恰好 9 个工具");
        assertEquals(Set.of(
                "jira.create_issue", "jira.list_issues", "jira.get_issue",
                "confluence.create_page", "confluence.get_page", "confluence.search_pages",
                "gitlab.create_mr", "gitlab.list_mrs", "gitlab.create_issue"
        ), Set.of(tools.stream().map(ToolInfo::getName).toArray()));

        for (ToolInfo t : tools) {
            assertFalse(t.getDescription() == null || t.getDescription().isBlank(),
                    "工具描述不应为空: " + t.getName());
            Map<String, Object> schema = t.getSchema();
            assertNotNull(schema, "schema 不应为 null: " + t.getName());
            // 字段名 / 类型 / 必填标记（AC-01）
            assertInstanceOf(Map.class, schema.get("properties"), "schema.properties 应为字段映射: " + t.getName());
            Map<?, ?> props = (Map<?, ?>) schema.get("properties");
            props.forEach((k, v) -> {
                assertInstanceOf(Map.class, v, "字段定义应为对象: " + k);
                assertTrue(((Map<?, ?>) v).containsKey("type"), "字段应含类型标记: " + k);
            });
            assertInstanceOf(List.class, schema.get("required"), "schema.required 应存在（可为空表）");
        }

        // 抽样断言必填标记（Q3 附录抽查 3 个代表工具）
        ToolInfo create = tools.stream().filter(t -> t.getName().equals("jira.create_issue")).findFirst().orElseThrow();
        assertEquals(List.of("summary"), create.getSchema().get("required"));
        ToolInfo mr = tools.stream().filter(t -> t.getName().equals("gitlab.create_mr")).findFirst().orElseThrow();
        assertEquals(List.of("title", "source_branch", "target_branch"), mr.getSchema().get("required"));
        ToolInfo list = tools.stream().filter(t -> t.getName().equals("jira.list_issues")).findFirst().orElseThrow();
        assertEquals(List.of(), list.getSchema().get("required"));
    }

    // ============================ AC-02 Jira 链路 ============================

    @Test
    @DisplayName("jira 链路: create → get 字段一致 → list 包含该条")
    void jira链路_create后get字段一致且list包含() {
        Map<String, Object> created = asMap(provider.invokeTool("jira.create_issue",
                Map.of("summary", "X", "description", "演示描述")));

        assertNotNull(created.get("id"), "create 应返回唯一 id");
        assertTrue(created.get("id").toString().startsWith("JIRA-"));
        assertEquals("X", created.get("summary"));

        Object got = provider.invokeTool("jira.get_issue", Map.of("id", created.get("id")));
        Map<String, Object> gotIssue = asMap(got);
        assertEquals(created.get("id"), gotIssue.get("id"), "get_issue 按 id 返回该对象");
        assertEquals(created.get("summary"), gotIssue.get("summary"), "字段一致");
        assertEquals(created.get("description"), gotIssue.get("description"));

        Map<String, Object> list = asMap(provider.invokeTool("jira.list_issues", Map.of()));
        assertInstanceOf(List.class, list.get("issues"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) list.get("issues");
        assertTrue(issues.stream().anyMatch(i -> created.get("id").equals(i.get("id"))),
                "list_issues 至少包含该条");
    }

    // ============================ AC-03 Confluence 链路 ============================

    @Test
    @DisplayName("confluence 链路: get_page 按 id / search 关键字命中 / 无命中返回空列表")
    void confluence链路_get与search_无命中空列表() {
        Map<String, Object> page = asMap(provider.invokeTool("confluence.create_page",
                Map.of("title", "发布手册", "content", "三层演进路线与回滚步骤")));

        assertEquals(page.get("id"), asMap(provider.invokeTool("confluence.get_page",
                Map.of("id", page.get("id")))).get("id"));

        // title 命中
        Map<String, Object> byTitle = asMap(provider.invokeTool("confluence.search_pages",
                Map.of("keyword", "发布")));
        assertEquals(1, ((List<?>) byTitle.get("pages")).size(), "按 title 关键字应命中");
        // content 命中
        Map<String, Object> byContent = asMap(provider.invokeTool("confluence.search_pages",
                Map.of("keyword", "回滚")));
        assertEquals(1, ((List<?>) byContent.get("pages")).size(), "按 content 关键字应命中");
        // 不存在关键字：空列表而非报错
        Map<String, Object> noHit = asMap(provider.invokeTool("confluence.search_pages",
                Map.of("keyword", "zzz_no_hit")));
        assertEquals(0, ((List<?>) noHit.get("pages")).size(), "无命中应返回空列表");
        assertEquals(0, noHit.get("total"));
        assertFalse(noHit.containsKey("error"), "无命中不是错误");
    }

    // ============================ AC-04 GitLab 链路 ============================

    @Test
    @DisplayName("gitlab 链路: create_mr 含分支字段且 list_mrs 包含 / create_issue 同语义唯一 id")
    void gitlab链路_mr含分支字段_issue同语义() {
        Map<String, Object> mr = asMap(provider.invokeTool("gitlab.create_mr", Map.of(
                "title", "feat: mock provider",
                "source_branch", "feature/mock",
                "target_branch", "main")));

        assertNotNull(mr.get("id"));
        assertEquals("feature/mock", mr.get("source_branch"), "mr 应含 source 分支字段");
        assertEquals("main", mr.get("target_branch"), "mr 应含 target 分支字段");

        Map<String, Object> list = asMap(provider.invokeTool("gitlab.list_mrs", Map.of()));
        assertTrue(((List<?>) list.get("mrs")).stream()
                .map(m -> ((Map<?, ?>) m).get("id"))
                .anyMatch(mr.get("id")::equals), "list_mrs 应包含该条");

        // gitlab.create_issue 与 AC-02 同语义：create 返回带唯一 id 的对象（F1 固定 9 工具，
        // 无 gitlab get/list 工具，"可 get/list" 由同租户分桶存储结构保证——见租户隔离测试）
        Map<String, Object> issue1 = asMap(provider.invokeTool("gitlab.create_issue", Map.of("title", "GL issue 1")));
        Map<String, Object> issue2 = asMap(provider.invokeTool("gitlab.create_issue", Map.of("title", "GL issue 2")));
        assertNotEquals(issue1.get("id"), issue2.get("id"), "重复 create 应产生唯一 id");
        assertEquals("GL issue 1", issue1.get("title"));
    }

    // ============================ AC-05 租户隔离 ============================

    @Test
    @DisplayName("租户隔离: 列表仅见本租户数据，他租户 id 直取 NOT_FOUND 且无任何他租户字段")
    void 租户隔离_跨租户不可见不可取() {
        // T1 各建 1 条
        Map<String, Object> t1Issue = asMap(provider.invokeTool("jira.create_issue", Map.of("summary", "T1-issue")));
        Map<String, Object> t1Page = asMap(provider.invokeTool("confluence.create_page",
                Map.of("title", "T1-page", "content", "T1 secret content")));
        Map<String, Object> t1Mr = asMap(provider.invokeTool("gitlab.create_mr", Map.of(
                "title", "T1-mr", "source_branch", "a", "target_branch", "b")));

        // T2 各建 1 条
        asTenant(T2, () -> {
            provider.invokeTool("jira.create_issue", Map.of("summary", "T2-issue"));
            provider.invokeTool("confluence.create_page", Map.of("title", "T2-page", "content", "T2 content"));
            provider.invokeTool("gitlab.create_mr", Map.of("title", "T2-mr", "source_branch", "c", "target_branch", "d"));
            return null;
        });

        // T2 视角：列表仅见 T2 自己的数据
        Map<String, Object> t2Issues = asMap(asTenant(T2, () -> provider.invokeTool("jira.list_issues", Map.of())));
        List<?> issues = (List<?>) t2Issues.get("issues");
        assertEquals(1, issues.size(), "T2 list_issues 仅见 1 条（自己）");
        assertEquals("T2-issue", ((Map<?, ?>) issues.get(0)).get("summary"));

        Map<String, Object> t2Pages = asMap(asTenant(T2, () -> provider.invokeTool("confluence.search_pages", Map.of("keyword", "page"))));
        List<?> pages = (List<?>) t2Pages.get("pages");
        assertEquals(1, pages.size(), "T2 search 仅命中自己");
        assertEquals("T2-page", ((Map<?, ?>) pages.get(0)).get("title"));

        Map<String, Object> t2Mrs = asMap(asTenant(T2, () -> provider.invokeTool("gitlab.list_mrs", Map.of())));
        assertEquals(1, ((List<?>) t2Mrs.get("mrs")).size(), "T2 list_mrs 仅见 1 条");

        // T2 用 T1 的 id 直取：NOT_FOUND 类结果，不含 T1 数据任何字段
        Map<String, Object> crossIssue = expectError(
                asTenant(T2, () -> provider.invokeTool("jira.get_issue", Map.of("id", t1Issue.get("id")))),
                MockMCPProvider.CODE_NOT_FOUND);
        String crossIssueJson = crossIssue.toString();
        assertFalse(crossIssueJson.contains("T1-issue"), "跨租户响应不得含 T1 issue 字段");
        assertFalse(crossIssueJson.contains("summary"), "跨租户响应不得含 issue 任何数据字段");

        Map<String, Object> crossPage = expectError(
                asTenant(T2, () -> provider.invokeTool("confluence.get_page", Map.of("id", t1Page.get("id")))),
                MockMCPProvider.CODE_NOT_FOUND);
        String crossPageJson = crossPage.toString();
        assertFalse(crossPageJson.contains("T1-page"), "跨租户响应不得含 T1 page 字段");
        assertFalse(crossPageJson.contains("T1 secret content"), "跨租户响应不得含 T1 page 内容");

        // 同 id 在 T1 视角仍可取（隔离不破坏本租户可见性）
        assertEquals(t1Issue.get("id"), asMap(provider.invokeTool("jira.get_issue",
                Map.of("id", t1Issue.get("id")))).get("id"));
        List<?> t1MrList = (List<?>) asMap(provider.invokeTool("gitlab.list_mrs", Map.of())).get("mrs");
        assertEquals(1, t1MrList.size(), "T1 list_mrs 仍见自己的 1 条");
        assertEquals(t1Mr.get("id"), ((Map<?, ?>) t1MrList.get(0)).get("id"));
    }

    // ============================ AC-06 __mock 标识与存储边界 ============================

    @Test
    @DisplayName("__mock 标识: 所有成功调用响应顶层携带 __mock=true")
    void mock标识_所有成功响应携带() {
        Map<String, Object> issue = asMap(provider.invokeTool("jira.create_issue", Map.of("summary", "s")));
        assertEquals(Boolean.TRUE, issue.get("__mock"));
        Map<String, Object> list = asMap(provider.invokeTool("jira.list_issues", Map.of()));
        assertEquals(Boolean.TRUE, list.get("__mock"));
        Map<String, Object> page = asMap(provider.invokeTool("confluence.create_page",
                Map.of("title", "t", "content", "c")));
        assertEquals(Boolean.TRUE, page.get("__mock"));
        Map<String, Object> search = asMap(provider.invokeTool("confluence.search_pages", Map.of("keyword", "t")));
        assertEquals(Boolean.TRUE, search.get("__mock"));
        Map<String, Object> mr = asMap(provider.invokeTool("gitlab.create_mr",
                Map.of("title", "t", "source_branch", "s", "target_branch", "m")));
        assertEquals(Boolean.TRUE, mr.get("__mock"));
        Map<String, Object> mrs = asMap(provider.invokeTool("gitlab.list_mrs", Map.of()));
        assertEquals(Boolean.TRUE, mrs.get("__mock"));
        Map<String, Object> glIssue = asMap(provider.invokeTool("gitlab.create_issue", Map.of("title", "t")));
        assertEquals(Boolean.TRUE, glIssue.get("__mock"));
    }

    @Test
    @DisplayName("内存边界: 新实例（等价应用重启）list 类工具返回空列表，无残留")
    void 内存边界_重启即清空() {
        provider.invokeTool("jira.create_issue", Map.of("summary", "重启前数据"));
        MockMCPProvider restarted = new MockMCPProvider();

        Map<String, Object> issues = asMap(restarted.invokeTool("jira.list_issues", Map.of()));
        assertEquals(0, ((List<?>) issues.get("issues")).size(), "重启后 jira 数据清空");
        Map<String, Object> pages = asMap(restarted.invokeTool("confluence.search_pages", Map.of("keyword", "重启")));
        assertEquals(0, ((List<?>) pages.get("pages")).size(), "重启后 confluence 数据清空");
        Map<String, Object> mrs = asMap(restarted.invokeTool("gitlab.list_mrs", Map.of()));
        assertEquals(0, ((List<?>) mrs.get("mrs")).size(), "重启后 gitlab 数据清空");
    }

    // ============================ AC-07 参数校验与错误结构 ============================

    @Test
    @DisplayName("缺必填参数: 返回结构化错误 -32602（code+message），不抛异常")
    void 缺必填参数_结构化错误不抛异常() {
        assertDoesNotThrow(() -> {
            Map<String, Object> err = expectError(
                    provider.invokeTool("jira.create_issue", Map.of("description", "有描述但缺 summary")),
                    MockMCPProvider.CODE_INVALID_PARAMS);
            assertTrue(err.get("error").toString().contains("summary"), "message 应指出缺失参数名");
        });
        // params 为 null 同样结构化错误
        assertDoesNotThrow(() -> expectError(
                provider.invokeTool("confluence.create_page", null),
                MockMCPProvider.CODE_INVALID_PARAMS));
        // 空白串视为缺失
        assertDoesNotThrow(() -> expectError(
                provider.invokeTool("jira.get_issue", Map.of("id", "  ")),
                MockMCPProvider.CODE_INVALID_PARAMS));
    }

    @Test
    @DisplayName("不存在的工具名: 返回结构化错误 -32601")
    void 未知工具名_结构化错误() {
        Map<String, Object> err = expectError(
                provider.invokeTool("jira.delete_issue", Map.of()),
                MockMCPProvider.CODE_TOOL_NOT_FOUND);
        assertTrue(err.get("error").toString().contains("jira.delete_issue"));
    }

    // ============================ SPI 语义补充 ============================

    @Test
    @DisplayName("registerTool: 重复 name 拒绝（false），新 name 可注册并出现在 listTools")
    void registerTool_重复拒绝_新名可注册() {
        assertFalse(provider.registerTool("jira.create_issue", "重复注册", Map.of()),
                "重复 name 应注册失败");
        assertTrue(provider.registerTool("custom.ping", "自定义 mock 工具", Map.of("type", "object")));
        assertEquals(10, provider.listTools().size());
        assertTrue(provider.listTools().stream().anyMatch(t -> "custom.ping".equals(t.getName())));
        // 未实现处理器的注册工具调用 → 结构化错误（不是 500）
        expectError(provider.invokeTool("custom.ping", Map.of()), MockMCPProvider.CODE_TOOL_NOT_FOUND);
    }

    @Test
    @DisplayName("provider 元信息: getType=mcp / getProvider=mock / isAvailable=true")
    void provider元信息() {
        assertEquals("mcp", provider.getType());
        assertEquals("mock", provider.getProvider());
        assertTrue(provider.isAvailable());
    }
}
