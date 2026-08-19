package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DependencyService 单测（case-20260818 T11，AC-F3.1~F3.4 验收基线，R3 自检核心）。
 *
 * <p>覆盖四个收敛点：
 * <ul>
 *   <li><b>C1 归一化</b>：blocks 换向（from=被阻塞方）+ note 前缀 [orig:blocks]。</li>
 *   <li><b>C2 复活语义</b>：DuplicateKey → 查逻辑删行（自定义 SQL 绕过 @TableLogic）→
 *       命中 UPDATE 复活（审计 detail 含 revived:true）；无命中 400 唯一冲突指名既有 id。</li>
 *   <li><b>C5 环拒绝</b>：成环 400 携带路径提示（非 409）。</li>
 *   <li><b>AC-F3.4</b>：relates_to 不进环检测、不参与 blocked 判定。</li>
 * </ul>
 *
 * <p>注：Mapper mock 字段名必须为 baseMapper——ServiceImpl.baseMapper 字段注入按名匹配
 * （多个 BaseMapper 子类型 mock 同时存在时按字段名消歧）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DependencyServiceTest {

    @Mock ProjectDependencyMapper baseMapper;
    @Mock ProjectMapper projectMapper;
    @Mock AuditService auditService;

    @InjectMocks
    DependencyServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        // ProjectDependency/Project 的 LambdaWrapper 列解析需 TableInfo（纯 Mockito 无 Spring）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProjectDependency.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void injectBaseMapper() throws Exception {
        // ServiceImpl.baseMapper（protected 泛型字段）显式注入：多个 BaseMapper 子类型 mock
        // 并存时 Mockito 字段注入按名匹配不可靠，反射钉死（ProjectProgressServiceTest 无此问题
        // 因其不继承 ServiceImpl；本服务继承 ServiceImpl 必须手工接线）
        java.lang.reflect.Field f = DependencyServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    private static Project project(long id, String name, String status) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setStatus(status);
        return p;
    }

    private static ProjectDependency edge(long from, long to, String type) {
        ProjectDependency e = new ProjectDependency();
        e.setFromProjectId(from);
        e.setToProjectId(to);
        e.setDependencyType(type);
        return e;
    }

    // ==================== C1 归一化（AC-F3.1） ====================

    /** blocks 登记换向：from=B(被阻塞方)、to=A(阻塞方)、type=depends_on、note 前缀 [orig:blocks]。 */
    @Test
    void register_blocks换向归一() {
        when(projectMapper.selectById(101L)).thenReturn(project(101, "项目A", "in_progress"));
        when(projectMapper.selectById(202L)).thenReturn(project(202, "项目B", "in_progress"));
        when(baseMapper.selectList(any())).thenReturn(List.of());   // 无既有强边
        when(baseMapper.insert(any(ProjectDependency.class))).thenReturn(1);

        ProjectDependency edge = service.register(101L, 202L, "blocks", "接口未就绪");

        ArgumentCaptor<ProjectDependency> captor = ArgumentCaptor.forClass(ProjectDependency.class);
        verify(baseMapper).insert(captor.capture());
        ProjectDependency stored = captor.getValue();
        assertEquals(202L, stored.getFromProjectId(), "换向后 from=被阻塞方 B");
        assertEquals(101L, stored.getToProjectId(), "换向后 to=阻塞方 A");
        assertEquals("depends_on", stored.getDependencyType(), "类型归一 depends_on");
        assertEquals("[orig:blocks]接口未就绪", stored.getNote(), "原始类型由 note 前缀承载（C1）");
        assertEquals(202L, edge.getFromProjectId());
        verify(auditService).log(eq("dependency_create"), eq("dependency"), anyString(), anyString());
    }

    /** depends_on 原样存储（不换向不加前缀）；relates_to 同样原样。 */
    @Test
    void register_dependsOn与relatesTo原样() {
        when(projectMapper.selectById(101L)).thenReturn(project(101, "A", "planning"));
        when(projectMapper.selectById(202L)).thenReturn(project(202, "B", "planning"));
        when(baseMapper.selectList(any())).thenReturn(List.of());
        when(baseMapper.insert(any(ProjectDependency.class))).thenReturn(1);

        service.register(202L, 101L, "depends_on", "接口依赖");
        ArgumentCaptor<ProjectDependency> captor = ArgumentCaptor.forClass(ProjectDependency.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals(202L, captor.getValue().getFromProjectId());
        assertEquals(101L, captor.getValue().getToProjectId());
        assertEquals("depends_on", captor.getValue().getDependencyType());
        assertEquals("接口依赖", captor.getValue().getNote(), "非 blocks 无前缀");

        service.register(202L, 101L, "relates_to", null);
        verify(baseMapper, times(2)).insert(any(ProjectDependency.class));
    }

    /** 硬校验：自依赖 400 / 类型非法 400 / 端点项目不存在 404（AC-F3.1）。 */
    @Test
    void register_硬校验() {
        BizException self = assertThrows(BizException.class,
                () -> service.register(101L, 101L, "depends_on", null));
        assertEquals(400, self.getCode());
        assertTrue(self.getMessage().contains("禁止自依赖"));

        BizException badType = assertThrows(BizException.class,
                () -> service.register(101L, 202L, "blocks2", null));
        assertEquals(400, badType.getCode());
        assertTrue(badType.getMessage().contains("dependencyType 非法"));

        when(projectMapper.selectById(101L)).thenReturn(project(101, "A", "planning"));
        BizException notFound = assertThrows(BizException.class,
                () -> service.register(101L, 999L, "depends_on", null));
        assertEquals(404, notFound.getCode());
        assertTrue(notFound.getMessage().contains("项目不存在: 999"));
    }

    // ==================== C5 环拒绝（AC-F3.3） ====================

    /** 既有 101→202，新登记 202→101（depends_on）成环 → 400 + 路径提示（非 409）。 */
    @Test
    void register_成环400带路径() {
        when(projectMapper.selectById(101L)).thenReturn(project(101, "项目A", "in_progress"));
        when(projectMapper.selectById(202L)).thenReturn(project(202, "项目B", "in_progress"));
        when(baseMapper.selectList(any()))
                .thenReturn(List.of(edge(101L, 202L, "depends_on")));   // 既有强边

        BizException ex = assertThrows(BizException.class,
                () -> service.register(202L, 101L, "depends_on", null));
        assertEquals(400, ex.getCode(), "环检测错误码=400（C5：PRD+SE 双源一致，编排简报 409 为口径笔误）");
        assertTrue(ex.getMessage().contains("依赖成环，禁止登记"), "文案携带成环提示");
        assertTrue(ex.getMessage().contains("202→101→202"), "提示携带还原路径");
        verify(baseMapper, never()).insert(any(ProjectDependency.class));
    }

    /** relates_to 豁免：同对成环方向登记 relates_to 不触发环检测（AC-F3.4）。 */
    @Test
    void register_relatesTo豁免环检测() {
        when(projectMapper.selectById(101L)).thenReturn(project(101, "A", "in_progress"));
        when(projectMapper.selectById(202L)).thenReturn(project(202, "B", "in_progress"));
        when(baseMapper.insert(any(ProjectDependency.class))).thenReturn(1);

        ProjectDependency edge = assertDoesNotThrow(() -> service.register(202L, 101L, "relates_to", null));
        assertEquals("relates_to", edge.getDependencyType());
        verify(baseMapper, never()).selectList(any());   // 不进图（无强边查询）
    }

    // ==================== C2 复活语义（AC-F3.1 删后重登） ====================

    /** 删后重登：DuplicateKey → 逻辑删行命中 → UPDATE 复活（id 复用，审计 revived:true）。 */
    @Test
    void register_删后重登复活() {
        when(projectMapper.selectById(202L)).thenReturn(project(202, "B", "in_progress"));
        when(projectMapper.selectById(101L)).thenReturn(project(101, "A", "in_progress"));
        when(baseMapper.selectList(any())).thenReturn(List.of());   // 活边预检不可见逻辑删行
        when(baseMapper.insert(any(ProjectDependency.class)))
                .thenThrow(new DuplicateKeyException("uk_dep_tenant_from_to_type"));
        ProjectDependency deleted = edge(202L, 101L, "depends_on");
        deleted.setId(9001L);
        when(baseMapper.selectDeletedEdge(anyLong(), eq(202L), eq(101L), eq("depends_on")))
                .thenReturn(deleted);
        when(baseMapper.reviveEdge(anyLong(), eq(202L), eq(101L), eq("depends_on"),
                anyString(), anyString())).thenReturn(1);
        ProjectDependency revived = edge(202L, 101L, "depends_on");
        revived.setId(9001L);
        when(baseMapper.selectById(9001L)).thenReturn(revived);

        ProjectDependency result = service.register(202L, 101L, "depends_on", "复活后备注");

        assertEquals(9001L, result.getId(), "复活复用逻辑删行 id（C2）");
        verify(baseMapper).reviveEdge(anyLong(), eq(202L), eq(101L), eq("depends_on"),
                eq("复活后备注"), anyString());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("dependency_create"), eq("dependency"), eq("9001"), detail.capture());
        assertTrue(detail.getValue().contains("\"revived\":true"), "审计 detail 标记 revived:true");
    }

    /** DuplicateKey 但无逻辑删行命中 → 400 唯一冲突，文案指名既有活跃行 id（AC-F3.1 第二次登记）。 */
    @Test
    void register_唯一冲突400指名id() {
        when(projectMapper.selectById(202L)).thenReturn(project(202, "B", "in_progress"));
        when(projectMapper.selectById(101L)).thenReturn(project(101, "A", "in_progress"));
        when(baseMapper.insert(any(ProjectDependency.class)))
                .thenThrow(new DuplicateKeyException("uk"));
        when(baseMapper.selectDeletedEdge(anyLong(), eq(202L), eq(101L), eq("depends_on")))
                .thenReturn(null);
        ProjectDependency active = edge(202L, 101L, "depends_on");
        active.setId(8888L);
        // selectList 两次：第一次=环预检（空），第二次=冲突行定位（active）
        when(baseMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(active));

        BizException ex = assertThrows(BizException.class,
                () -> service.register(202L, 101L, "depends_on", null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("同对项目同类型依赖已存在"), "唯一冲突文案");
        assertTrue(ex.getMessage().contains("id=8888"), "指名既有 id");
    }

    // ==================== blocked 判定（AC-F3.2/F3.4，展示层实时） ====================

    /** 强依赖对端未交付 → blocked + 阻塞链文案；对端 delivered → 不阻塞；relates_to 不计。 */
    @Test
    void board_blocked判定() {
        Project a = project(101, "项目A", "in_progress");      // A 未交付
        Project b = project(202, "项目B", "in_progress");
        Project c = project(303, "项目C", "delivered");        // C 已交付
        ProjectDependency bDepA = edge(202L, 101L, "depends_on");
        bDepA.setNote("[orig:blocks]接口未就绪");              // C1：blocks 原始表述由 note 承载
        when(baseMapper.selectList(any())).thenReturn(List.of(
                bDepA,                                 // B→A 强边，A 未交付 → B blocked
                edge(202L, 303L, "depends_on"),        // B→C 强边，C 已交付 → 不阻塞
                edge(101L, 303L, "relates_to")));      // 弱关联不参与判定
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(a, b, c));

        var board = service.board();

        assertEquals(3, board.getStats().getTotalProjects());
        assertEquals(3, board.getStats().getEdgeCount());
        assertEquals(1, board.getStats().getBlockedCount());
        var cardB = board.getProjects().stream()
                .filter(p -> p.getProjectId() == 202L).findFirst().orElseThrow();
        assertTrue(cardB.getBlocked(), "B 依赖未交付的 A → blocked");
        assertEquals(1, cardB.getBlockedSources().size());
        assertTrue(cardB.getBlockedSources().get(0).contains("被 项目A 阻塞：项目A 未交付"), "阻塞链文案");
        assertEquals(2, cardB.getWaitingFor().size(), "等待组=from=B 的两条边（含已交付的 C，边保留仅不阻塞）");
        assertEquals("blocks", cardB.getWaitingFor().get(0).getOrigType(), "origType 由 note 前缀解析还原（C1）");
        assertEquals("受阻", cardB.getWaitingFor().get(0).getDisplayName());
        assertEquals("接口未就绪", cardB.getWaitingFor().get(0).getRemark());
        var cardC = board.getProjects().stream()
                .filter(p -> p.getProjectId() == 303L).findFirst().orElseThrow();
        assertFalse(cardC.getBlocked(), "relates_to 不参与判定（AC-F3.4）");
        assertEquals(0, cardC.getBlockedSources().size());
    }

    /** 空图：统计条零值 + 空项目卡列表。 */
    @Test
    void board_空图() {
        when(baseMapper.selectList(any())).thenReturn(List.of());

        var board = service.board();

        assertEquals(0, board.getStats().getTotalProjects());
        assertEquals(0, board.getStats().getBlockedCount());
        assertEquals(0, board.getStats().getEdgeCount());
        assertTrue(board.getProjects().isEmpty());
    }

    // ==================== cycleCheck / fullCheck ====================

    /** cycleCheck 查询语义：成环也返回 200 数据（wouldCycle + 路径），不抛 400。 */
    @Test
    void cycleCheck_查询语义返回路径() {
        when(baseMapper.selectList(any()))
                .thenReturn(List.of(edge(101L, 202L, "depends_on")));
        var vo = service.cycleCheck(202L, 101L);
        assertTrue(vo.wouldCycle());
        assertEquals(List.of(202L, 101L, 202L), vo.cyclePathIds());
        assertEquals("202→101→202", vo.pathDisplay());

        var no = service.cycleCheck(901L, 101L);
        assertFalse(no.wouldCycle());
        assertTrue(no.cyclePathIds().isEmpty());
        assertNull(no.pathDisplay());
    }

    /** fullCheck：正常空（可见可治口径）。 */
    @Test
    void fullCheck_正常空() {
        when(baseMapper.selectList(any())).thenReturn(List.of());
        var vo = service.fullCheck();
        assertEquals(0, vo.cycleCount());
        assertTrue(vo.cycles().isEmpty());
    }

    /** 规模超限翻译：GraphSizeExceeded → 400 业务码（10002 节点 > 10000 上界）。 */
    @Test
    void cycleCheck_规模超限翻译400() {
        List<ProjectDependency> many = new ArrayList<>();
        for (long i = 1; i <= 10001; i++) {
            many.add(edge(i, i + 1, "depends_on"));   // 节点 1..10002
        }
        when(baseMapper.selectList(any())).thenReturn(many);
        BizException ex = assertThrows(BizException.class, () -> service.fullCheck());
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("依赖规模超限"));
    }
}
