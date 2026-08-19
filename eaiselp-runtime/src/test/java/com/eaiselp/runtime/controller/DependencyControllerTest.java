package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.runtime.hierarchy.DependencyService;
import com.eaiselp.runtime.hierarchy.Project;
import com.eaiselp.runtime.hierarchy.ProjectDependency;
import com.eaiselp.runtime.hierarchy.ProjectService;
import com.eaiselp.runtime.hierarchy.dto.DependencyBoardVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * DependencyController 单测（case-20260818 T16）。
 *
 * <p>核心验证（批A 交接点=环路径 id→项目名映射在 Controller 层）：</p>
 * <ul>
 *   <li>登记/编辑：归一化参数透传 Service；环拒绝 400 文案的 id 路径换项目名（C5=400 非 409）</li>
 *   <li>响应行：origType 由 note 前缀解析还原（C1）、displayName 双端名拼接、remark 剥前缀</li>
 *   <li>cycle-check / full-check：pathDisplay 换项目名；board 透传</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DependencyControllerTest {

    @Mock DependencyService dependencyService;
    @Mock ProjectService projectService;

    @InjectMocks DependencyController controller;

    private static Project project(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    /** 归一化后存储形态：from=202（被阻塞方 B）、to=101（阻塞方 A）、note 带 [orig:blocks] 前缀。 */
    private static ProjectDependency edge(Long id) {
        ProjectDependency e = new ProjectDependency();
        e.setId(id);
        e.setFromProjectId(202L);
        e.setToProjectId(101L);
        e.setDependencyType("depends_on");
        e.setNote("[orig:blocks]接口未就绪");
        return e;
    }

    // ===== 登记：归一化透传 + 响应行组装 =====

    @Test
    void create_参数透传_响应行含origType与项目名() {
        when(dependencyService.register(101L, 202L, "blocks", "接口未就绪")).thenReturn(edge(9001L));
        when(projectService.listByIds(anyCollection()))
                .thenReturn(List.of(project(101L, "项目A"), project(202L, "项目B")));

        DependencyController.EdgeSaveRequest req = new DependencyController.EdgeSaveRequest();
        req.setFromProjectId(101L);
        req.setToProjectId(202L);
        req.setDependencyType("blocks");
        req.setRemark("接口未就绪");

        var result = controller.create(req);

        assertEquals(0, result.getCode());
        DependencyController.EdgeVo vo = result.getData();
        assertEquals(202L, vo.getFromProjectId(), "blocks 换向后 from=被阻塞方 B");
        assertEquals(101L, vo.getToProjectId());
        assertEquals("depends_on", vo.getDependencyType());
        assertEquals("blocks", vo.getOrigType(), "origType 由 note 前缀解析还原（C1）");
        assertEquals("项目B", vo.getFromProjectName());
        assertEquals("项目A", vo.getToProjectName());
        assertEquals("项目B → 项目A（硬阻塞）", vo.getDisplayName());
        assertEquals("接口未就绪", vo.getRemark(), "remark 剥离 [orig:blocks] 存储前缀");
    }

    @Test
    void create_环拒绝400_路径换项目名() {
        when(dependencyService.register(101L, 202L, "depends_on", null))
                .thenThrow(new BizException(400, "依赖成环，禁止登记：101→202→101"));
        when(projectService.listByIds(anyCollection()))
                .thenReturn(List.of(project(101L, "项目A"), project(202L, "项目B")));

        DependencyController.EdgeSaveRequest req = new DependencyController.EdgeSaveRequest();
        req.setFromProjectId(101L);
        req.setToProjectId(202L);
        req.setDependencyType("depends_on");

        var result = controller.create(req);

        assertEquals(400, result.getCode(), "环拒绝=400 非 409（C5）");
        assertEquals("依赖成环，禁止登记：项目A→项目B→项目A", result.getMsg(),
                "id 路径在 Controller 层换项目名（批A 交接点）");
    }

    @Test
    void create_非环业务异常_文案原样() {
        when(dependencyService.register(101L, 202L, "depends_on", null))
                .thenThrow(new BizException(400, "同对项目同类型依赖已存在（blocks 与 depends_on 同向视为同一强依赖）：id=9001"));

        DependencyController.EdgeSaveRequest req = new DependencyController.EdgeSaveRequest();
        req.setFromProjectId(101L);
        req.setToProjectId(202L);
        req.setDependencyType("depends_on");

        var result = controller.create(req);
        assertEquals(400, result.getCode());
        assertTrue(result.getMsg().contains("id=9001"), "唯一冲突文案不被换名逻辑触碰");
    }

    // ===== 编辑：同样过环预检 + 换名 =====

    @Test
    void update_环预检400_路径换项目名() {
        when(dependencyService.edit(9001L, "depends_on", "x")).thenThrow(
                new BizException(400, "依赖成环，禁止登记：202→101→202"));
        when(projectService.listByIds(anyCollection()))
                .thenReturn(List.of(project(101L, "项目A"), project(202L, "项目B")));

        DependencyController.EdgeSaveRequest req = new DependencyController.EdgeSaveRequest();
        req.setDependencyType("depends_on");
        req.setRemark("x");

        var result = controller.update(9001L, req);
        assertEquals(400, result.getCode());
        assertEquals("依赖成环，禁止登记：项目B→项目A→项目B", result.getMsg());
    }

    @Test
    void delete_逻辑删() {
        assertEquals(0, controller.delete(9001L).getCode());
        verify(dependencyService).remove(9001L);
    }

    // ===== 列表：行结构同 POST 响应 =====

    @Test
    void page_行映射含双端名() {
        Page<ProjectDependency> page = new Page<>(1, 20);
        page.setRecords(List.of(edge(9001L)));
        when(dependencyService.pageEdges(202L, "depends_on", 1, 20)).thenReturn(page);
        when(projectService.listByIds(anyCollection()))
                .thenReturn(List.of(project(101L, "项目A"), project(202L, "项目B")));

        var result = controller.page(1, 20, 202L, "depends_on");

        assertEquals(0, result.getCode());
        IPage<DependencyController.EdgeVo> rows = result.getData();
        assertEquals(1, rows.getRecords().size());
        assertEquals("项目B → 项目A（硬阻塞）", rows.getRecords().get(0).getDisplayName());
        assertEquals("blocks", rows.getRecords().get(0).getOrigType());
    }

    // ===== 看板透传 =====

    @Test
    void board_透传() {
        DependencyBoardVo vo = new DependencyBoardVo();
        when(dependencyService.board()).thenReturn(vo);
        assertSame(vo, controller.board().getData());
    }

    // ===== cycle-check / full-check：pathDisplay 换项目名 =====

    @Test
    void cycleCheck_成环路径换名() {
        when(dependencyService.cycleCheck(101L, 202L)).thenReturn(
                new DependencyService.CycleCheckVo(true, List.of(101L, 202L, 101L), "101→202→101"));
        when(projectService.listByIds(anyCollection()))
                .thenReturn(List.of(project(101L, "项目A"), project(202L, "项目B")));

        var result = controller.cycleCheck(101L, 202L);

        assertEquals(0, result.getCode());
        assertTrue(result.getData().wouldCycle());
        assertEquals("项目A→项目B→项目A", result.getData().pathDisplay());
        assertEquals(List.of(101L, 202L, 101L), result.getData().cyclePathIds());
    }

    @Test
    void cycleCheck_不成环原样() {
        when(dependencyService.cycleCheck(101L, 300L)).thenReturn(
                new DependencyService.CycleCheckVo(false, List.of(), null));

        var result = controller.cycleCheck(101L, 300L);
        assertFalse(result.getData().wouldCycle());
        assertNull(result.getData().pathDisplay());
    }

    @Test
    void fullCheck_环路径换名() {
        when(dependencyService.fullCheck()).thenReturn(new DependencyService.FullCheckVo(1, List.of(
                new DependencyService.FullCheckVo.CyclePath(List.of(101L, 202L, 101L), "101→202→101"))));
        when(projectService.listByIds(anyCollection()))
                .thenReturn(List.of(project(101L, "项目A"), project(202L, "项目B")));

        var result = controller.fullCheck();

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().cycleCount());
        assertEquals("项目A→项目B→项目A", result.getData().cycles().get(0).pathDisplay());
    }

    // ===== 已删项目降退 id 展示 =====

    @Test
    void create_对端项目查不到_降退id展示() {
        when(dependencyService.register(101L, 202L, "depends_on", null)).thenReturn(edge(9001L));
        when(projectService.listByIds(anyCollection())).thenReturn(List.of(project(202L, "项目B")));

        DependencyController.EdgeSaveRequest req = new DependencyController.EdgeSaveRequest();
        req.setFromProjectId(101L);
        req.setToProjectId(202L);
        req.setDependencyType("depends_on");

        var result = controller.create(req);
        assertEquals("项目B → 101（硬阻塞）", result.getData().getDisplayName(),
                "查不到名（跨租户/已删）降退 id 展示；kind 仍按 origType（本 fixture note 带 [orig:blocks]）");
    }
}
