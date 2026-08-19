package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.mapper.CaseMapper;
import com.eaiselp.runtime.hierarchy.dto.ProjectDetailVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * ProjectServiceImpl 详情扩展/删除联动单测（case-20260818 T22，AC-F2.4/F2.5/F3.5）。
 *
 * <p>覆盖：achievementHint 判定（全完成 + planned 里程碑 → eligible；空项目/未全完成/
 * 无 planned → null 不出提示条）、依赖区块（等待/责任分组 + 对方已删边过滤 + 异常降级
 * null 不阻塞主渲染）、删除项目联动清依赖边（from=id OR to=id）。</p>
 *
 * <p>注：Mapper mock 字段名必须为 baseMapper——ServiceImpl.baseMapper 按名注入
 * （同 MilestoneServiceImplTransitTest 说明）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectServiceImplDetailTest {

    @Mock ProjectMapper baseMapper;
    @Mock CaseMapper caseMapper;
    @Mock ProjectPrincipleMapper projectPrincipleMapper;
    @Mock ArchitecturePrincipleMapper principleMapper;
    @Mock MilestoneService milestoneService;
    @Mock ProjectDependencyMapper projectDependencyMapper;

    @InjectMocks ProjectServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProjectDependency.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, ProjectPrinciple.class);
        TableInfoHelper.initTableInfo(assistant, Case.class);   // deleteWithUnlinkCase 的解除挂接 wrapper
    }

    @BeforeEach
    void injectBaseMapper() throws Exception {
        java.lang.reflect.Field f = ProjectServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    private static Project project(Long id, int total, int done) {
        Project p = new Project();
        p.setId(id);
        p.setName("项目B");
        p.setStatus("in_progress");
        p.setProgress(total == 0 || done == 0 ? 0 : done * 100 / total);
        p.setCaseTotal(total);
        p.setCaseDone(done);
        return p;
    }

    private static ProjectDependency edge(Long id, Long from, Long to, String type, String note) {
        ProjectDependency e = new ProjectDependency();
        e.setId(id);
        e.setFromProjectId(from);
        e.setToProjectId(to);
        e.setDependencyType(type);
        e.setNote(note);
        return e;
    }

    /** 通用详情前置：项目存在 + 无原则绑定。 */
    private void stubProject(Project p) {
        when(baseMapper.selectById(p.getId())).thenReturn(p);
        when(projectPrincipleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    // ===== achievementHint（AC-F2.4/F2.5） =====

    @Test
    void 详情_全完成且存在planned里程碑_提示eligible() {
        stubProject(project(202L, 2, 2));
        when(milestoneService.plannedMilestoneIds(202L)).thenReturn(List.of(5001L));
        when(projectDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ProjectDetailVo vo = service.detail(202L);

        ProjectDetailVo.AchievementHint hint = vo.getAchievementHint();
        assertNotNull(hint, "AC-F2.4：全部完成 + planned 里程碑 → 出提示条");
        assertTrue(hint.getEligible());
        assertEquals(List.of(5001L), hint.getMilestoneIds());
        assertEquals("项目全部 Case 已完成，可达成里程碑", hint.getMessage());
        assertNotNull(vo.getDependencies(), "无依赖边时返回空分组而非 null");
        assertTrue(vo.getDependencies().getWaitingFor().isEmpty());
    }

    @Test
    void 详情_空项目total0_无提示() {
        stubProject(project(202L, 0, 0));
        when(projectDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertNull(service.detail(202L).getAchievementHint(), "AC-F2.5：空项目不出提示条");
        verify(milestoneService, never()).plannedMilestoneIds(any());
    }

    @Test
    void 详情_未全完成_无提示() {
        stubProject(project(202L, 2, 1));
        when(projectDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertNull(service.detail(202L).getAchievementHint(), "未全 done 不出提示条");
        verify(milestoneService, never()).plannedMilestoneIds(any());
    }

    @Test
    void 详情_全完成但无planned里程碑_无提示() {
        stubProject(project(202L, 2, 2));
        when(milestoneService.plannedMilestoneIds(202L)).thenReturn(List.of());
        when(projectDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertNull(service.detail(202L).getAchievementHint(), "无 planned 里程碑 → 无可确认对象");
    }

    // ===== 依赖区块（AC-F3.5） =====

    @Test
    void 详情_依赖区块_等待与责任分组_对方已删边过滤() {
        stubProject(project(202L, 1, 0));
        // 9001: from=202→101（本项目等待 101）；9002: from=101→202（101 责任依赖本项目）；
        // 9003: from=202→303（303 已删除——selectBatchIds 不返回 → 过滤）
        when(projectDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                edge(9001L, 202L, 101L, "depends_on", "[orig:blocks]接口未就绪"),
                edge(9002L, 101L, 202L, "relates_to", "共享组件"),
                edge(9003L, 202L, 303L, "depends_on", "已删对端")));
        Project peer = new Project();
        peer.setId(101L);
        peer.setName("项目A");
        when(baseMapper.selectBatchIds(anyCollection())).thenReturn(List.of(peer));

        ProjectDetailVo vo = service.detail(202L);

        ProjectDetailVo.DependencySection deps = vo.getDependencies();
        assertNotNull(deps);
        assertEquals(1, deps.getWaitingFor().size(), "等待组=from 本项目的边（9003 已被对端删除过滤）");
        var waiting = deps.getWaitingFor().get(0);
        assertEquals(9001L, waiting.getEdgeId());
        assertEquals(101L, waiting.getToProjectId());
        assertEquals("项目A", waiting.getToProjectName());
        assertEquals("blocks", waiting.getOrigType(), "origType 由 note 前缀还原（C1）");
        assertEquals("受阻", waiting.getDisplayName());
        assertEquals("接口未就绪", waiting.getRemark());

        assertEquals(1, deps.getResponsibleFor().size(), "责任组=to 本项目的边");
        var responsible = deps.getResponsibleFor().get(0);
        assertEquals(9002L, responsible.getEdgeId());
        assertEquals("relates_to", responsible.getDependencyType());
        assertEquals("relates_to", responsible.getOrigType(), "无 [orig:blocks] 前缀时按存储类型还原");
        assertEquals("依赖", responsible.getDisplayName(), "与看板 toEdgeItem 同口径（非 blocks 一律依赖）");
        assertEquals("共享组件", responsible.getRemark());
    }

    @Test
    void 详情_依赖计算异常_降级null_主渲染不受影响() {
        stubProject(project(202L, 1, 0));
        when(projectDependencyMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenThrow(new RuntimeException("mock DB down"));

        ProjectDetailVo vo = assertDoesNotThrow(() -> service.detail(202L));

        assertNotNull(vo.getId(), "PRD §6.3：区块异常不阻塞详情主渲染");
        assertNull(vo.getDependencies(), "降级 null");
        assertEquals("项目B", vo.getName());
    }

    @Test
    void 详情_里程碑判定异常_提示降级null() {
        stubProject(project(202L, 2, 2));
        when(milestoneService.plannedMilestoneIds(202L)).thenThrow(new RuntimeException("mock"));
        when(projectDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ProjectDetailVo vo = assertDoesNotThrow(() -> service.detail(202L));
        assertNull(vo.getAchievementHint(), "提示块异常降级 null");
    }

    @Test
    void 详情_项目不存在404() {
        when(baseMapper.selectById(9999L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.detail(9999L));
        assertEquals(404, ex.getCode());
    }

    // ===== 删除联动清依赖边（AC-F3.5） =====

    @Test
    void 删除项目_联动清依赖边_fromOrTo命中() {
        when(baseMapper.selectById(202L)).thenReturn(project(202L, 0, 0));
        when(caseMapper.update(isNull(), any())).thenReturn(1);
        when(projectPrincipleMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
        when(projectDependencyMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);
        when(baseMapper.deleteById(202L)).thenReturn(1);

        service.deleteWithUnlinkCase(202L);

        // Case 解除挂接 + 原则绑定清理（既有行为回归）
        verify(caseMapper).update(isNull(), any());
        verify(projectPrincipleMapper).delete(any(LambdaQueryWrapper.class));
        // T22：依赖边联动逻辑删，from=id OR to=id
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ProjectDependency>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(projectDependencyMapper).delete((Wrapper<ProjectDependency>) captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("from_project_id"), "from=id 条件");
        assertTrue(sql.contains("to_project_id"), "to=id 条件");
        assertTrue(sql.toUpperCase().contains("OR"), "两端任一命中即删");
        // 项目行逻辑删（MP removeById 走 deleteById(entity) 形态）
        verify(baseMapper).deleteById(any(Project.class));
    }

    @Test
    void 删除项目_项目不存在404() {
        when(baseMapper.selectById(9999L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.deleteWithUnlinkCase(9999L));
        assertEquals(404, ex.getCode());
        verify(projectDependencyMapper, never()).delete(any());
    }
}
