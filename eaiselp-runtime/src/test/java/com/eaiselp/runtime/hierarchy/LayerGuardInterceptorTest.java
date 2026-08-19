package com.eaiselp.runtime.hierarchy;

import com.eaiselp.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LayerGuardInterceptor 单测（PRJ-002 T28 存量回归 + case-20260818 T20 L2 组扩展）。
 *
 * <p><b>验收基线（AC-SWITCH.1/SWITCH.2，tasks.md T20/R4）</b>：</p>
 * <ul>
 *   <li>存量三前缀回归：strategy 关 → /api/v1/strategies/** 43001；L2 关 →
 *       /api/v1/programs|projects/** 43002——行为与改造前逐项一致；</li>
 *   <li>三新前缀：L2 关 → /api/v1/milestones|project-dependencies|metrics/** 43002
 *       （HTTP 200 非 500）；</li>
 *   <li>不拦清单：/api/v1/cases/**（L1 恒开）与 adrs / tech-radar / principles
 *       （不限层知识资产，AC-SWITCH.2）任何开关组合恒放行；</li>
 *   <li>OPTIONS 预检放行（CORS）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LayerGuardInterceptorTest {

    @Mock TenantLayerService layerService;

    LayerGuardInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new LayerGuardInterceptor(layerService);
        TenantContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletResponse pass(String method, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean proceed = interceptor.preHandle(req, resp, new Object());
        assertTrue(proceed, uri + " 应放行");
        assertEquals(200, resp.getStatus(), uri + " 应放行且不写响应体");
        return resp;
    }

    /** 断言拦截并返回响应（供断言业务码与 HTTP 200 形态）。 */
    private MockHttpServletResponse guard(String method, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean proceed = interceptor.preHandle(req, resp, new Object());
        assertFalse(proceed, uri + " 应被拦截");
        return resp;
    }

    // ===== 存量三前缀回归（R4：先回归再验新） =====

    @Test
    void 存量_L3关_strategies43001() throws Exception {
        when(layerService.isStrategyEnabled(1L)).thenReturn(false);
        MockHttpServletResponse resp = guard("GET", "/api/v1/strategies");
        assertEquals(200, resp.getStatus(), "HTTP 200 非 500（AC-F10.1）");
        assertTrue(resp.getContentAsString().contains("\"code\":43001"));
    }

    @Test
    void 存量_L2关_programs与projects43002() throws Exception {
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(false);
        for (String uri : new String[]{"/api/v1/programs", "/api/v1/programs/3",
                "/api/v1/projects", "/api/v1/projects/202"}) {
            MockHttpServletResponse resp = guard("GET", uri);
            assertEquals(200, resp.getStatus(), uri + " HTTP 200 非 500");
            assertTrue(resp.getContentAsString().contains("\"code\":43002"), uri);
        }
    }

    @Test
    void 存量_开关全开_全部放行() throws Exception {
        when(layerService.isStrategyEnabled(1L)).thenReturn(true);
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(true);
        for (String uri : new String[]{"/api/v1/strategies", "/api/v1/programs",
                "/api/v1/projects", "/api/v1/milestones", "/api/v1/project-dependencies/board",
                "/api/v1/metrics/dora"}) {
            pass("GET", uri);
        }
    }

    // ===== T20 新增三前缀（AC-SWITCH.1） =====

    @Test
    void 新增_L2关_milestones前缀43002() throws Exception {
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(false);
        for (String uri : new String[]{"/api/v1/milestones", "/api/v1/milestones/5001",
                "/api/v1/milestones/5001/transit", "/api/v1/programs/3/milestone-timeline"}) {
            MockHttpServletResponse resp = guard("GET", uri);
            assertEquals(200, resp.getStatus());
            assertTrue(resp.getContentAsString().contains("\"code\":43002"), uri);
        }
    }

    @Test
    void 新增_L2关_projectDependencies前缀43002() throws Exception {
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(false);
        for (String uri : new String[]{"/api/v1/project-dependencies", "/api/v1/project-dependencies/9001",
                "/api/v1/project-dependencies/board", "/api/v1/project-dependencies/cycle-check"}) {
            MockHttpServletResponse resp = guard("GET", uri);
            assertTrue(resp.getContentAsString().contains("\"code\":43002"), uri);
        }
    }

    @Test
    void 新增_L2关_metrics前缀43002() throws Exception {
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(false);
        MockHttpServletResponse resp = guard("GET", "/api/v1/metrics/dora?scope=all&periodDays=30");
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":43002"));
    }

    @Test
    void 新增_L2关_dora不下沉strategies语义_L3开不误报43001() throws Exception {
        // L2 关但 L3 开：metrics 拦 43002（不是 43001）——组间语义不串
        when(layerService.isStrategyEnabled(1L)).thenReturn(true);
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(false);
        MockHttpServletResponse resp = guard("GET", "/api/v1/metrics/dora");
        assertTrue(resp.getContentAsString().contains("\"code\":43002"));
        assertTrue(resp.getContentAsString().contains("项目群/项目层未启用"));
    }

    // ===== 不拦清单（AC-SWITCH.2 否定性用例核心） =====

    @Test
    void 不限层_adrs与techRadar与principles恒放行_任何开关组合() throws Exception {
        when(layerService.isStrategyEnabled(1L)).thenReturn(false);
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(false);
        for (String uri : new String[]{"/api/v1/adrs", "/api/v1/adrs/7001/transit",
                "/api/v1/tech-radar", "/api/v1/tech-radar/quadrants",
                "/api/v1/principles", "/api/v1/principles/3/adrs",
                "/api/v1/cases", "/api/v1/cases/CASE-1/run"}) {
            pass("GET", uri);
        }
    }

    // ===== 前缀边界：不误拦同头异名路径 =====

    @Test
    void 边界_milestoneX等非注册前缀不拦() throws Exception {
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(false);
        // /api/v1/milestone-xxx 与 milestones 前缀不同（不在 L2 组）；防御性确认 startsWith 语义
        pass("GET", "/api/v1/milestone-fe");
        pass("GET", "/api/v1/dependent-things");
        pass("GET", "/api/v1/metric-other");
    }

    // ===== OPTIONS 预检放行 =====

    @Test
    void OPTIONS预检放行_不判开关() throws Exception {
        when(layerService.isProgramProjectEnabled(1L)).thenReturn(false);
        pass("OPTIONS", "/api/v1/milestones");
        pass("OPTIONS", "/api/v1/strategies");
        verifyNoInteractions(layerService);
    }
}
