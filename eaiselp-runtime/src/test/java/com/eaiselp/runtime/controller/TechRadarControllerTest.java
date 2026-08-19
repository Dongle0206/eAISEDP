package com.eaiselp.runtime.controller;

import com.eaiselp.common.exception.BizException;
import com.eaiselp.runtime.hierarchy.TechRadarItem;
import com.eaiselp.runtime.hierarchy.TechRadarService;
import com.eaiselp.runtime.hierarchy.dto.TechRadarVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TechRadarController 单测（case-20260818 T18）。
 *
 * <p>验证：语义名映射（name↔tech_name、reviewedAt↔reviewed_at，C4）、四象限分组端点
 * （恒含四键）、枚举/唯一冲突 400 原样上抛。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TechRadarControllerTest {

    @Mock TechRadarService techRadarService;

    @InjectMocks TechRadarController controller;

    private static TechRadarController.TechRadarSaveRequest saveReq() {
        TechRadarController.TechRadarSaveRequest req = new TechRadarController.TechRadarSaveRequest();
        req.setName("Redis");
        req.setQuadrant("tools");
        req.setRing("adopt");
        req.setReason("缓存事实标准");
        req.setReviewedAt(LocalDate.of(2026, 8, 1));
        req.setRemark(null);
        return req;
    }

    @Test
    void list_象限环组合筛选透传() {
        when(techRadarService.list("tools", "adopt")).thenReturn(List.of(new TechRadarVo()));

        var result = controller.list("tools", "adopt");
        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        verify(techRadarService).list("tools", "adopt");
    }

    @Test
    void quadrants_恒含四键_空象限空列表() {
        Map<String, List<TechRadarVo>> groups = new LinkedHashMap<>();
        groups.put("languages", List.of());
        groups.put("platforms", List.of());
        groups.put("techniques", List.of());
        groups.put("tools", List.of(new TechRadarVo()));
        when(techRadarService.quadrantGroups(null)).thenReturn(groups);

        var result = controller.quadrants(null);

        assertEquals(0, result.getCode());
        assertEquals(4, result.getData().size(), "恒含四键（前端 SVG 扇区渲染零判空）");
        assertTrue(result.getData().containsKey("tools"));
    }

    @Test
    void create_语义名映射V5列名() {
        TechRadarItem created = new TechRadarItem();
        created.setId(6001L);
        when(techRadarService.create(any(TechRadarItem.class))).thenReturn(created);
        when(techRadarService.toVo(any(TechRadarItem.class))).thenReturn(new TechRadarVo());

        assertEquals(0, controller.create(saveReq()).getCode());

        ArgumentCaptor<TechRadarItem> captor = ArgumentCaptor.forClass(TechRadarItem.class);
        verify(techRadarService).create(captor.capture());
        TechRadarItem item = captor.getValue();
        assertEquals("Redis", item.getTechName(), "API 语义名 name ↔ V5 列 tech_name（C4）");
        assertEquals("tools", item.getQuadrant());
        assertEquals("adopt", item.getRing());
        assertEquals(LocalDate.of(2026, 8, 1), item.getReviewedAt());
    }

    @Test
    void create_同名重复400_原样上抛() {
        when(techRadarService.create(any(TechRadarItem.class)))
                .thenThrow(new BizException(400, "技术项已存在: Redis，请编辑既有项"));

        BizException ex = assertThrows(BizException.class, () -> controller.create(saveReq()));
        assertEquals(400, ex.getCode());
        assertEquals("技术项已存在: Redis，请编辑既有项", ex.getMessage());
    }

    @Test
    void create_象限非法400_原样上抛() {
        TechRadarController.TechRadarSaveRequest req = saveReq();
        req.setQuadrant("hardware");
        when(techRadarService.create(any(TechRadarItem.class)))
                .thenThrow(new BizException(400, "quadrant 非法，应为 techniques/tools/platforms/languages"));

        BizException ex = assertThrows(BizException.class, () -> controller.create(req));
        assertEquals(400, ex.getCode());
    }

    @Test
    void get_详情Vo() {
        TechRadarVo vo = new TechRadarVo();
        when(techRadarService.detailVo(6001L)).thenReturn(vo);
        assertSame(vo, controller.get(6001L).getData());
    }

    @Test
    void update_编辑透传_环移动审计在Service() {
        when(techRadarService.edit(eq(6001L), any(TechRadarItem.class))).thenReturn(new TechRadarItem());
        when(techRadarService.toVo(any(TechRadarItem.class))).thenReturn(new TechRadarVo());

        assertEquals(0, controller.update(6001L, saveReq()).getCode());
        verify(techRadarService).edit(eq(6001L), any(TechRadarItem.class));
    }

    @Test
    void delete_逻辑删() {
        assertEquals(0, controller.delete(6001L).getCode());
        verify(techRadarService).remove(6001L);
    }
}
