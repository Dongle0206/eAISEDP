package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.service.CaseService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GovernanceInjectionService 单测（AC-F7 下行注入核心语义，PRD 验收基线）。
 *
 * <p>覆盖注入解析契约（DBA §3 / SE 决策 D-1）五条主线：
 * <ul>
 *   <li>有绑定启用原则 → 文本含编号/标题/级别/must 拦截提示 + 项目约束（AC-F7.1 三载体之"章节文本"）</li>
 *   <li>无绑定行 → 租户全局 enabled=1 原则（AC-F7.2 全局强制语义）</li>
 *   <li>绑定行全禁 → 空集不回退租户默认（显式豁免边界）</li>
 *   <li>Case 未关联项目 / caseId 空 / Case 不存在 / 项目不存在 → 空结果（场景C，AC-F4.3）</li>
 *   <li>DB 异常 → 降级空、绝不阻塞编排（AC-F7 硬约束）；单条 2000 / 总量 8000 截断留痕（AC-F7.4）</li>
 * </ul>
 *
 * <p><b>lambda cache 初始化</b>：同 {@code CaseStateServiceImplTest}——纯 Mockito（无 Spring 上下文）下
 * MyBatis-Plus 解析 Case/ProjectPrinciple/ArchitecturePrinciple 的 lambda 方法引用需 TableInfo 缓存。</p>
 */
@ExtendWith(MockitoExtension.class)
class GovernanceInjectionServiceTest {

    @Mock CaseService caseService;
    @Mock ProjectMapper projectMapper;
    @Mock ProjectPrincipleMapper projectPrincipleMapper;
    @Mock ArchitecturePrincipleMapper principleMapper;

    @InjectMocks
    GovernanceInjectionService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Case.class);
        TableInfoHelper.initTableInfo(assistant, ProjectPrinciple.class);
        TableInfoHelper.initTableInfo(assistant, ArchitecturePrinciple.class);
    }

    // ===== AC-F7.1：有绑定启用原则 → 注入文本含编号+标题+级别+must 拦截提示+项目约束 =====

    @Test
    void 有绑定启用原则_注入文本含编号标题级别must提示与项目约束() {
        stubCaseProjectBindings("金融行业项目，必须满足多租户与等保要求",
                List.of(binding(100L, 1)),
                principle(100L, "P11", "must", "所有业务表必须携带 tenant_id，实现多租户隔离", 1));

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        assertNotNull(r.getGovernanceText(), "有可注入内容时不得返回空文本（禁止空标题章节的反面：有内容必须有章节）");
        String text = r.getGovernanceText();
        assertTrue(text.startsWith(GovernanceInjectionService.DELIM_START), "M2 定界：文本须以开始定界标记开头");
        assertTrue(text.endsWith(GovernanceInjectionService.DELIM_END), "M2 定界：文本须以结束定界标记收尾");
        assertTrue(text.contains(GovernanceInjectionService.SECTION_TITLE), "章节正文以 AC-F7.1 断言标题开头");
        assertTrue(text.contains("P11"), "须含原则编号");
        assertTrue(text.contains("[P11|must]"), "须含 编号|强制级别 标记");
        assertTrue(text.contains("多租户隔离"), "须含原则标题");
        assertTrue(text.contains("tenant_id"), "须含原则内容");
        assertTrue(text.contains("违反将被 Reviewer 门禁拦截"), "must 级须含拦截提示行");
        assertTrue(text.contains("### 项目约束"), "项目描述非空时须含项目约束段");
        assertTrue(text.contains("金融行业项目"), "须含项目描述正文");
        assertEquals(List.of("P11"), r.getInjectedPrinciples(), "注入清单=实际注入的 code 列表");
        assertFalse(r.isTruncated());
    }

    // ===== AC-M2.1：注入文本被定界标记包裹并附"平台注入、不得修改/追加"声明 =====

    @Test
    void 注入文本被定界标记包裹_附平台注入不可修改声明() {
        stubCaseProjectBindings(null,
                List.of(binding(100L, 1)),
                principle(100L, "P11", "must", "内容", 1));

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        String text = r.getGovernanceText();
        int start = text.indexOf(GovernanceInjectionService.DELIM_START);
        int end = text.lastIndexOf(GovernanceInjectionService.DELIM_END);
        assertEquals(0, start, "定界开始标记必须在文本首");
        assertEquals(text.length() - GovernanceInjectionService.DELIM_END.length(), end,
                "定界结束标记必须在文本尾");
        assertTrue(end > start, "开始/结束标记必须成对出现（首尾包裹）");
        assertTrue(text.contains(GovernanceInjectionService.PLATFORM_DECLARE),
                "须附平台注入声明（不得修改/不得追加）");
        // 声明须在开始标记之后、章节标题之前（角色读到约束前先知道"这是平台注入"）
        int declareAt = text.indexOf(GovernanceInjectionService.PLATFORM_DECLARE);
        int titleAt = text.indexOf(GovernanceInjectionService.SECTION_TITLE);
        assertTrue(declareAt > start && declareAt < titleAt, "声明位于定界开始标记与章节标题之间");
        assertEquals(text.length(), r.getRenderedChars(), "留痕字符数与最终文本（含定界）一致");
    }

    // ===== AC-F5.2 语义：绑定保留但原则租户级停用 → 不注入该原则 =====

    @Test
    void 原则租户级停用_绑定保留但不注入() {
        stubCaseProjectBindings("项目描述",
                List.of(binding(100L, 1)),
                principle(100L, "P11", "must", "内容", 0));   // 原则侧 enabled=0

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        assertTrue(r.getInjectedPrinciples().isEmpty(), "停用原则不得进入注入清单");
        assertNotNull(r.getGovernanceText(), "仍有项目约束 → 章节保留");
        assertFalse(r.getGovernanceText().contains("P11"), "停用原则的编号不得出现在文本中");
        assertTrue(r.getGovernanceText().contains("### 项目约束"));
    }

    // ===== AC-F7.2：无绑定行 → 继承租户全部启用原则（全局强制） =====

    @Test
    void 项目无绑定行_继承租户全部启用原则() {
        Case c = new Case();
        c.setCaseId("case-1");
        c.setProjectId(10L);
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);
        Project p = new Project();
        p.setId(10L);
        p.setDescription(null);
        when(projectMapper.selectById(10L)).thenReturn(p);
        when(projectPrincipleMapper.selectList(any())).thenReturn(List.of());   // 无绑定行
        // 租户全局启用原则：P6(must) + P3(should)
        when(principleMapper.selectList(any())).thenReturn(List.of(
                principle(200L, "P3", "should", "依赖单向：L3→L2→L1", 1),
                principle(201L, "P6", "must", "零硬编码：角色集合由数据驱动", 1)));

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        // 渲染排序 must 在前；注入清单两原则都在
        assertEquals(List.of("P6", "P3"), r.getInjectedPrinciples(), "无绑定 → 租户全局启用原则全量注入（must 在前）");
        assertTrue(r.getGovernanceText().contains("[P3|should]"), "should 级别正常渲染");
        verify(principleMapper, never()).selectBatchIds(any());
    }

    // ===== 边界：绑定行全禁 → 空集，不回退租户全局（显式豁免） =====

    @Test
    void 绑定行全禁_原则空集不回退租户全局() {
        stubCaseProjectBindings(null,          // 描述空 → 无可注入内容
                List.of(binding(100L, 0), binding(101L, 0)));   // 绑定行全为 enabled=0

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        assertNull(r.getGovernanceText(), "原则空集且无项目约束 → 整体省略章节（禁止空标题）");
        assertTrue(r.getInjectedPrinciples().isEmpty());
        // 关键断言：不回退——不得再查租户全局原则
        verify(principleMapper, never()).selectList(any());
        verify(principleMapper, never()).selectBatchIds(any());
    }

    @Test
    void 绑定行全禁_仅剩项目约束时章节保留() {
        stubCaseProjectBindings("仅约束无原则的项目", List.of(binding(100L, 0)));

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        assertNotNull(r.getGovernanceText());
        assertTrue(r.getInjectedPrinciples().isEmpty(), "空集语义：清单为空但章节可因项目约束保留");
        assertTrue(r.getGovernanceText().contains("### 项目约束"));
        verify(principleMapper, never()).selectList(any());
    }

    // ===== 场景C / 降级链路（AC-F4.3 + AC-F7 降级硬约束） =====

    @Test
    void Case未关联项目_不注入_且不触达项目层查询() {
        Case c = new Case();
        c.setCaseId("case-1");
        c.setProjectId(null);   // 场景C：project_id 为空
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        assertNull(r.getGovernanceText());
        assertTrue(r.getInjectedPrinciples().isEmpty());
        verifyNoInteractions(projectMapper, projectPrincipleMapper, principleMapper);
    }

    @Test
    void caseId为空_直接返回空结果() {
        GovernanceInjectionService.InjectionResult r = service.resolveInjection("  ", null);

        assertNull(r.getGovernanceText());
        verifyNoInteractions(caseService, projectMapper, projectPrincipleMapper, principleMapper);
    }

    @Test
    void Case不存在_返回空结果() {
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-x", null);

        assertNull(r.getGovernanceText());
        verifyNoInteractions(projectMapper, projectPrincipleMapper, principleMapper);
    }

    @Test
    void 项目不存在_降级为不注入() {
        Case c = new Case();
        c.setCaseId("case-1");
        c.setProjectId(10L);
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);
        when(projectMapper.selectById(10L)).thenReturn(null);   // 已删/跨租户被拦截器过滤

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        assertNull(r.getGovernanceText());
        verifyNoInteractions(projectPrincipleMapper, principleMapper);
    }

    @Test
    void 解析链路DB异常_降级空结果_绝不阻塞编排() {
        when(caseService.getOne(any(LambdaQueryWrapper.class)))
                .thenThrow(new RuntimeException("mock: db down"));

        GovernanceInjectionService.InjectionResult r = assertDoesNotThrow(
                () -> service.resolveInjection("case-1", null),
                "AC-F7 硬约束：注入失败必须降级，不得向编排主流程抛异常");

        assertNull(r.getGovernanceText());
        assertTrue(r.getInjectedPrinciples().isEmpty());
        assertFalse(r.isTruncated());
    }

    // ===== AC-F7.4：双上限截断留痕 =====

    @Test
    void 单条内容超2000字符_截断到上限并留痕() {
        String content = "A".repeat(2400) + "MARKER_TAIL" + "B".repeat(100);
        stubTenantGlobalPrinciples(principle(100L, "P6", "must", content, 1));

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        assertTrue(r.getGovernanceText().contains("…(已截断)"), "单条超限须截断并留截断标记");
        assertFalse(r.getGovernanceText().contains("MARKER_TAIL"), "2000 位之后的原文不得进入注入文本");
        assertEquals(List.of("P6"), r.getInjectedPrinciples());
    }

    @Test
    void 注入总量超8000字符_按must优先截断_文本不超限() {
        // 1 条 must + 4 条 may，单条内容均超 2000（截断后单块约 2000+）→ 总量必超 8000
        stubTenantGlobalPrinciples(
                principle(100L, "PM", "must", "M".repeat(4000), 1),
                principle(101L, "PY1", "may", "Y".repeat(4000), 1),
                principle(102L, "PY2", "may", "Y".repeat(4000), 1),
                principle(103L, "PY3", "may", "Y".repeat(4000), 1),
                principle(104L, "PY4", "may", "Y".repeat(4000), 1));

        GovernanceInjectionService.InjectionResult r = service.resolveInjection("case-1", null);

        assertTrue(r.isTruncated(), "总量超限必须置 truncated 留痕");
        assertTrue(r.getGovernanceText().length() <= GovernanceInjectionService.MAX_TOTAL_CHARS,
                "实际注入文本不得超过 8000 字符");
        assertTrue(r.getInjectedPrinciples().contains("PM"), "must 级最优先保留");
        assertTrue(r.getInjectedPrinciples().contains("PY1"));
        assertFalse(r.getInjectedPrinciples().contains("PY3"), "尾部 may 级被丢弃");
        assertFalse(r.getInjectedPrinciples().contains("PY4"));
        assertFalse(r.getGovernanceText().contains("[PY4|"), "被丢弃的原则不出现在文本中");
    }

    // ===== 辅助 =====

    /** 打通"Case→项目→绑定行→原则批量查"链路的通用桩（绑定行全禁等场景不会触达 selectBatchIds，条件桩避免严格桩未用告警）。 */
    private void stubCaseProjectBindings(String projectDesc, List<ProjectPrinciple> bindings,
                                         ArchitecturePrinciple... batchPrinciples) {
        Case c = new Case();
        c.setCaseId("case-1");
        c.setProjectId(10L);
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);
        Project p = new Project();
        p.setId(10L);
        p.setDescription(projectDesc);
        when(projectMapper.selectById(10L)).thenReturn(p);
        when(projectPrincipleMapper.selectList(any())).thenReturn(bindings);
        if (batchPrinciples.length > 0) {
            when(principleMapper.selectBatchIds(any())).thenReturn(List.of(batchPrinciples));
        }
    }

    /** 无绑定行场景：直通租户全局原则查询。 */
    private void stubTenantGlobalPrinciples(ArchitecturePrinciple... tenantGlobal) {
        Case c = new Case();
        c.setCaseId("case-1");
        c.setProjectId(10L);
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);
        Project p = new Project();
        p.setId(10L);
        when(projectMapper.selectById(10L)).thenReturn(p);
        when(projectPrincipleMapper.selectList(any())).thenReturn(List.of());
        when(principleMapper.selectList(any())).thenReturn(List.of(tenantGlobal));
    }

    private ProjectPrinciple binding(Long principleId, int enabled) {
        ProjectPrinciple b = new ProjectPrinciple();
        b.setProjectId(10L);
        b.setPrincipleId(principleId);
        b.setEnabled(enabled);
        return b;
    }

    private ArchitecturePrinciple principle(Long id, String code, String enforceLevel,
                                            String content, int enabled) {
        ArchitecturePrinciple p = new ArchitecturePrinciple();
        p.setId(id);
        p.setCode(code);
        p.setTitle("原则" + code);
        p.setEnforceLevel(enforceLevel);
        p.setContent(content);
        p.setEnabled(enabled);
        return p;
    }
}
