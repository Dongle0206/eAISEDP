package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.hierarchy.Adr;
import com.eaiselp.runtime.hierarchy.AdrService;
import com.eaiselp.runtime.hierarchy.ArchitecturePrinciple;
import com.eaiselp.runtime.hierarchy.PrincipleService;
import com.eaiselp.runtime.hierarchy.dto.AdrVo;
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
 * AdrController 单测（case-20260818 T17）。
 *
 * <p>验证：语义名→V5 列名映射（context↔context_text，C4）、relatedPrincipleCodes
 * List→JSON 承载、transit 参数传递与 target 必填 400、PrincipleController 反查端点
 * （原则 id→code→ADR 列表）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdrControllerTest {

    @Mock AdrService adrService;
    // PrincipleController 依赖（反查端点测试用）
    @Mock PrincipleService principleService;
    @Mock AuditService auditService;

    @InjectMocks AdrController controller;
    @InjectMocks PrincipleController principleController;

    private static AdrController.AdrSaveRequest saveReq() {
        AdrController.AdrSaveRequest req = new AdrController.AdrSaveRequest();
        req.setAdrCode("ADR-001");
        req.setTitle("多租户隔离贯穿");
        req.setContext("上下文");
        req.setDecision("决策");
        req.setConsequences("后果");
        req.setRelatedPrincipleCodes(List.of("P11"));
        req.setDecisionDate(LocalDate.of(2026, 8, 1));
        req.setAuthor("admin");
        return req;
    }

    // ===== 列表 =====

    @Test
    void page_筛选透传_缺省双状态由Service兜底() {
        Page<AdrVo> page = new Page<>(1, 20);
        page.setRecords(List.of());
        when(adrService.pageFilter(isNull(), eq("P11"), eq("多租户"), eq(1L), eq(20L))).thenReturn(page);

        assertEquals(0, controller.page(1, 20, null, "P11", "多租户").getCode());
        verify(adrService).pageFilter(isNull(), eq("P11"), eq("多租户"), eq(1L), eq(20L));
    }

    // ===== 创建：语义名→列名映射 + JSON 承载 =====

    @Test
    void create_语义名映射V5列名() {
        Adr created = new Adr();
        created.setId(7001L);
        created.setAdrCode("ADR-001");
        created.setStatus("proposed");
        when(adrService.create(any(Adr.class))).thenReturn(created);
        when(adrService.toVo(any(Adr.class))).thenReturn(new AdrVo());

        assertEquals(0, controller.create(saveReq()).getCode());

        ArgumentCaptor<Adr> captor = ArgumentCaptor.forClass(Adr.class);
        verify(adrService).create(captor.capture());
        Adr adr = captor.getValue();
        assertEquals("ADR-001", adr.getAdrCode());
        assertEquals("上下文", adr.getContextText(), "API 语义名 context ↔ V5 列 context_text（C4）");
        assertEquals("决策", adr.getDecisionText());
        assertEquals("后果", adr.getConsequenceText());
        assertEquals("[\"P11\"]", adr.getRelatedPrincipleCodes(), "关联原则以 JSON 数组 String 承载");
        assertEquals("admin", adr.getAuthor());
        assertNull(adr.getStatus(), "创建不映射状态——由 Service 固定 proposed");
        assertNull(adr.getSupersededBy());
    }

    @Test
    void create_关联原则不存在400_原样上抛() {
        when(adrService.create(any(Adr.class))).thenThrow(new BizException(400, "原则 P99 不存在"));

        BizException ex = assertThrows(BizException.class, () -> controller.create(saveReq()));
        assertEquals(400, ex.getCode());
        assertEquals("原则 P99 不存在", ex.getMessage());
    }

    // ===== 详情（C3 审计回显在 Service） =====

    @Test
    void get_详情Vo透传() {
        AdrVo vo = new AdrVo();
        vo.setDeprecateReason("已被事件驱动架构取代");
        when(adrService.detailVo(7001L)).thenReturn(vo);

        var result = controller.get(7001L);
        assertEquals(0, result.getCode());
        assertEquals("已被事件驱动架构取代", result.getData().getDeprecateReason());
    }

    // ===== 编辑 =====

    @Test
    void update_编辑透传() {
        when(adrService.edit(eq(7001L), any(Adr.class))).thenReturn(new Adr());
        when(adrService.toVo(any(Adr.class))).thenReturn(new AdrVo());

        assertEquals(0, controller.update(7001L, saveReq()).getCode());
        verify(adrService).edit(eq(7001L), any(Adr.class));
    }

    @Test
    void delete_逻辑删() {
        assertEquals(0, controller.delete(7001L).getCode());
        verify(adrService).remove(7001L);
    }

    // ===== transit =====

    @Test
    void transit_superseded参数透传() {
        AdrVo vo = new AdrVo();
        vo.setStatus("superseded");
        when(adrService.transit(7001L, "superseded", "ADR-002", null)).thenReturn(vo);

        AdrController.TransitRequest req = new AdrController.TransitRequest();
        req.setTarget("superseded");
        req.setSupersededBy("ADR-002");

        var result = controller.transit(7001L, req);
        assertEquals(0, result.getCode());
        assertSame(vo, result.getData());
    }

    @Test
    void transit_deprecated参数透传() {
        AdrVo vo = new AdrVo();
        when(adrService.transit(7001L, "deprecated", null, "已被取代")).thenReturn(vo);

        AdrController.TransitRequest req = new AdrController.TransitRequest();
        req.setTarget("deprecated");
        req.setDeprecateReason("已被取代");

        assertSame(vo, controller.transit(7001L, req).getData());
    }

    @Test
    void transit_target缺失400() {
        AdrController.TransitRequest req = new AdrController.TransitRequest();
        var result = controller.transit(7001L, req);
        assertEquals(400, result.getCode());
        verifyNoInteractions(adrService);
    }

    @Test
    void transit_非法流转400_原样上抛() {
        when(adrService.transit(7001L, "superseded", null, null))
                .thenThrow(new BizException(400, "非法状态流转: proposed→superseded"));
        AdrController.TransitRequest req = new AdrController.TransitRequest();
        req.setTarget("superseded");

        BizException ex = assertThrows(BizException.class, () -> controller.transit(7001L, req));
        assertEquals("非法状态流转: proposed→superseded", ex.getMessage());
    }

    // ===== PrincipleController 反查端点（AC-F4.3 原则侧聚合） =====

    @Test
    void relatedAdrs_按原则id载入code反查() {
        ArchitecturePrinciple ap = new ArchitecturePrinciple();
        ap.setId(3L);
        ap.setCode("P11");
        when(principleService.getById(3L)).thenReturn(ap);
        AdrVo vo = new AdrVo();
        vo.setAdrCode("ADR-001");
        when(adrService.listByPrincipleCode("P11")).thenReturn(List.of(vo));

        var result = principleController.relatedAdrs(3L);

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("ADR-001", result.getData().get(0).getAdrCode());
        verify(adrService).listByPrincipleCode("P11");
    }

    @Test
    void relatedAdrs_原则不存在404() {
        when(principleService.getById(9999L)).thenReturn(null);

        var result = principleController.relatedAdrs(9999L);
        assertEquals(404, result.getCode());
        verifyNoInteractions(adrService);
    }
}
