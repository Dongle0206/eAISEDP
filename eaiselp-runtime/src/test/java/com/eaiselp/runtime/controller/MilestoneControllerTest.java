package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Milestone;
import com.eaiselp.runtime.hierarchy.MilestoneService;
import com.eaiselp.runtime.hierarchy.ProgramService;
import com.eaiselp.runtime.hierarchy.StrategyService;
import com.eaiselp.runtime.hierarchy.dto.MilestoneTimelineVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * MilestoneController 单测（case-20260818 T15）。
 *
 * <p>验证薄控制器契约：参数透传（ownerType/ownerId/status 过滤与分页）、请求→实体映射
 * （status/achievedDate 不映射——状态变更只走 transit）、transit 参数传递与 400、
 * BizException（404/400）原样上抛给 GlobalExceptionHandler、ProgramController 群聚合
 * 时间线挂靠端点。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MilestoneControllerTest {

    @Mock MilestoneService milestoneService;
    // ProgramController 依赖（挂靠端点测试用）
    @Mock ProgramService programService;
    @Mock StrategyService strategyService;
    @Mock AuditService auditService;

    @InjectMocks MilestoneController controller;
    @InjectMocks ProgramController programController;

    private static Milestone entity(Long id, String status) {
        Milestone m = new Milestone();
        m.setId(id);
        m.setMilestoneCode("MS-0001");
        m.setOwnerType("project");
        m.setOwnerId(202L);
        m.setTitle("接口联调完成");
        m.setStatus(status);
        return m;
    }

    private static MilestoneController.MilestoneSaveRequest saveReq() {
        MilestoneController.MilestoneSaveRequest req = new MilestoneController.MilestoneSaveRequest();
        req.setOwnerType("project");
        req.setOwnerId(202L);
        req.setTitle("接口联调完成");
        req.setDescription("双方接口冻结");
        req.setTargetDate(LocalDate.of(2026, 8, 18));
        req.setOwner("张三");
        req.setBlocker(null);
        req.setSubprojects(null);
        return req;
    }

    // ===== 列表：两级归属过滤 + 分页透传 =====

    @Test
    void page_过滤参数透传() {
        Page<MilestoneTimelineVo> page = new Page<>(1, 20);
        page.setRecords(List.of());
        when(milestoneService.pageTimeline("project", 202L, "planned", 1, 20)).thenReturn(page);

        var result = controller.page(1, 20, "project", 202L, "planned");

        assertEquals(0, result.getCode());
        verify(milestoneService).pageTimeline("project", 202L, "planned", 1, 20);
    }

    @Test
    void page_无过滤() {
        IPage<MilestoneTimelineVo> page = new Page<>(2, 10);
        when(milestoneService.pageTimeline(isNull(), isNull(), isNull(), eq(2L), eq(10L))).thenReturn(page);

        assertEquals(0, controller.page(2, 10, null, null, null).getCode());
        verify(milestoneService).pageTimeline(isNull(), isNull(), isNull(), eq(2L), eq(10L));
    }

    // ===== 创建：请求→实体映射（status/achievedDate 不映射） =====

    @Test
    void create_请求映射实体_状态字段不进实体() {
        Milestone created = entity(5001L, "planned");
        when(milestoneService.create(any(Milestone.class))).thenReturn(created);
        when(milestoneService.toVo(any(Milestone.class))).thenReturn(new MilestoneTimelineVo());

        var result = controller.create(saveReq());

        assertEquals(0, result.getCode());
        ArgumentCaptor<Milestone> captor = ArgumentCaptor.forClass(Milestone.class);
        verify(milestoneService).create(captor.capture());
        Milestone ms = captor.getValue();
        assertEquals("project", ms.getOwnerType());
        assertEquals(202L, ms.getOwnerId());
        assertEquals("接口联调完成", ms.getTitle());
        assertEquals(LocalDate.of(2026, 8, 18), ms.getTargetDate());
        assertEquals("张三", ms.getOwner());
        assertNull(ms.getStatus(), "创建请求不含状态——由 Service 固定 planned");
        assertNull(ms.getAchievedDate());
    }

    @Test
    void create_归属校验失败400_原样上抛() {
        when(milestoneService.create(any(Milestone.class)))
                .thenThrow(new BizException(404, "归属项目不存在: 202"));

        BizException ex = assertThrows(BizException.class, () -> controller.create(saveReq()));
        assertEquals(404, ex.getCode());
        assertEquals("归属项目不存在: 202", ex.getMessage());
    }

    // ===== 详情：Vo 形态 + 跨租户 404 =====

    @Test
    void get_详情返回Vo() {
        Milestone ms = entity(5001L, "achieved");
        when(milestoneService.loadOr404(5001L)).thenReturn(ms);
        when(milestoneService.toVo(ms)).thenReturn(new MilestoneTimelineVo());

        assertEquals(0, controller.get(5001L).getCode());
        verify(milestoneService).loadOr404(5001L);
    }

    @Test
    void get_不存在404() {
        when(milestoneService.loadOr404(9999L)).thenThrow(new BizException(404, "里程碑不存在: 9999"));
        BizException ex = assertThrows(BizException.class, () -> controller.get(9999L));
        assertEquals(404, ex.getCode());
    }

    // ===== 编辑 =====

    @Test
    void update_编辑透传() {
        when(milestoneService.edit(eq(5001L), any(Milestone.class))).thenReturn(entity(5001L, "planned"));
        when(milestoneService.toVo(any(Milestone.class))).thenReturn(new MilestoneTimelineVo());

        assertEquals(0, controller.update(5001L, saveReq()).getCode());
        verify(milestoneService).edit(eq(5001L), any(Milestone.class));
    }

    // ===== 删除 =====

    @Test
    void delete_逻辑删() {
        assertEquals(0, controller.delete(5001L).getCode());
        verify(milestoneService).remove(5001L);
    }

    // ===== transit：参数传递 + target 必填 400 + 状态机 400 上抛 =====

    @Test
    void transit_达成日期参数透传() {
        when(milestoneService.transit(5001L, "achieved", LocalDate.of(2026, 8, 18)))
                .thenReturn(entity(5001L, "achieved"));
        when(milestoneService.toVo(any(Milestone.class))).thenReturn(new MilestoneTimelineVo());

        MilestoneController.TransitRequest req = new MilestoneController.TransitRequest();
        req.setTarget("achieved");
        req.setAchievedDate(LocalDate.of(2026, 8, 18));

        assertEquals(0, controller.transit(5001L, req).getCode());
        verify(milestoneService).transit(5001L, "achieved", LocalDate.of(2026, 8, 18));
    }

    @Test
    void transit_target缺失400() {
        MilestoneController.TransitRequest req = new MilestoneController.TransitRequest();
        req.setTarget(" ");
        var result = controller.transit(5001L, req);
        assertEquals(400, result.getCode());
        verifyNoInteractions(milestoneService);
    }

    @Test
    void transit_非法流转400_原样上抛() {
        when(milestoneService.transit(5001L, "delayed", null))
                .thenThrow(new BizException(400, "非法状态流转: achieved→delayed"));
        MilestoneController.TransitRequest req = new MilestoneController.TransitRequest();
        req.setTarget("delayed");

        BizException ex = assertThrows(BizException.class, () -> controller.transit(5001L, req));
        assertEquals(400, ex.getCode());
        assertEquals("非法状态流转: achieved→delayed", ex.getMessage());
    }

    // ===== ProgramController 挂靠端点：群聚合时间线（AC-F2.6） =====

    @Test
    void programTimeline_挂靠端点透传() {
        when(milestoneService.programTimeline(3L)).thenReturn(List.of(new MilestoneTimelineVo()));

        var result = programController.milestoneTimeline(3L);

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        verify(milestoneService).programTimeline(3L);
    }

    @Test
    void programTimeline_群不存在404() {
        when(milestoneService.programTimeline(9999L)).thenThrow(new BizException(404, "项目群不存在: 9999"));
        BizException ex = assertThrows(BizException.class, () -> programController.milestoneTimeline(9999L));
        assertEquals(404, ex.getCode());
    }
}
