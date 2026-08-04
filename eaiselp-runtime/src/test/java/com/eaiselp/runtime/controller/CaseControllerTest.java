package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.result.R;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Artifact;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.service.ArtifactService;
import com.eaiselp.data.service.CaseService;
import com.eaiselp.data.service.DerivationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CaseController 单测（Wave4-A）。
 *
 * <p>采用<b>纯 Mockito 方式</b>（@ExtendWith(MockitoExtension) + 直接调 Controller 方法，不走 MockMvc）。
 * 原因：CaseController 的所有方法贴了 {@code @RequirePermission("case:view"/"case:create")}，
 * @WebMvcTest 会拉起 PermissionInterceptor / JwtAuthInterceptor，需 mock 整条安全过滤器链，
 * 配置成本高且易因权限码与测试上下文不一致而误挂。纯 Mockito 直调方法可绕过拦截器，
 * 专注验证 Controller 业务分支（任务说明「优先保证测试能跑过」）。
 *
 * <p>接口（CaseService/DerivationService/ArtifactService/AuditService）用 @Mock，
 * 具体类 CaseController 用 @InjectMocks 真实实例注入。
 *
 * <p>覆盖用例：TC1 分页查询 / TC2 详情 / TC3 不存在 404 / TC4 创建 / TC5 title 空 400 /
 * TC6 派生列表 / TC7 产物列表。
 */
@ExtendWith(MockitoExtension.class)
class CaseControllerTest {

    @Mock CaseService caseService;
    @Mock DerivationService derivationService;
    @Mock ArtifactService artifactService;
    @Mock AuditService auditService;

    @InjectMocks CaseController controller;

    private Case sampleCase;

    @BeforeEach
    void setUp() {
        sampleCase = new Case();
        sampleCase.setId(1L);
        sampleCase.setCaseId("case-abc");
        sampleCase.setTitle("示例 Case");
        sampleCase.setRequirement("需求描述");
        sampleCase.setStatus("drafting");
    }

    /** TC1: GET /api/v1/cases 分页查询返回列表。 */
    @Test
    void TC1_分页查询返回列表() {
        Page<Case> expectedPage = new Page<>(1, 20);
        expectedPage.setRecords(List.of(sampleCase));
        expectedPage.setTotal(1);
        // caseService.page(Page, String) 是 CaseService 接口 default 方法，stub 接口方法即可
        when(caseService.page(any(Page.class), anyString())).thenReturn(expectedPage);

        R<IPage<Case>> r = controller.page(1, 20, "drafting");

        assertEquals(0, r.getCode(), "成功码应为 0");
        assertNotNull(r.getData());
        assertEquals(1, r.getData().getTotal(), "总数应为 1");
        assertEquals(1, r.getData().getRecords().size(), "记录数应为 1");
        assertEquals("case-abc", r.getData().getRecords().get(0).getCaseId());
        verify(caseService).page(any(Page.class), eq("drafting"));
    }

    /** TC2: GET /api/v1/cases/{caseId} 返回 Case 详情。 */
    @Test
    void TC2_查询Case详情返回实体() {
        // get 内部 new LambdaQueryWrapper，stub any 匹配
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(sampleCase);

        R<Case> r = controller.get("case-abc");

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals("case-abc", r.getData().getCaseId());
        assertEquals("示例 Case", r.getData().getTitle());
    }

    /** TC3: GET /api/v1/cases/{caseId} Case 不存在返回 404。 */
    @Test
    void TC3_Case不存在返回404() {
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        R<Case> r = controller.get("case-not-exist");

        assertEquals(404, r.getCode(), "不存在时应返回 404 码");
        assertNull(r.getData(), "404 时 data 应为 null");
        assertNotNull(r.getMsg(), "404 应携带 msg");
        assertTrue(r.getMsg().contains("case-not-exist"), "msg 应包含 caseId");
    }

    /** TC4: POST /api/v1/cases 创建 Case。 */
    @Test
    void TC4_创建Case成功() {
        when(caseService.save(any(Case.class))).thenAnswer(inv -> {
            // 模拟 MP save：保留入参实体并返回 true（Controller 依赖 save 后回显同一对象）
            return true;
        });

        CaseController.CreateCaseRequest req = new CaseController.CreateCaseRequest();
        req.setTitle("  新 Case 标题  ");
        req.setDescription("需求描述");

        R<Case> r = controller.create(req);

        assertEquals(0, r.getCode(), "创建成功应返回 0");
        assertNotNull(r.getData());
        assertNotNull(r.getData().getCaseId(), "应生成 caseId");
        assertTrue(r.getData().getCaseId().startsWith("case-"), "caseId 应带 case- 前缀");
        assertEquals("新 Case 标题", r.getData().getTitle(), "title 应被 trim");
        assertEquals("需求描述", r.getData().getRequirement(), "description 应落 requirement");
        assertEquals("L1", r.getData().getLayer());
        assertEquals("standard", r.getData().getTier());
        assertEquals("drafting", r.getData().getStatus());
        assertEquals("stage_0", r.getData().getCurrentStage());
        verify(caseService).save(any(Case.class));
        // 审计：应记录 case_create
        verify(auditService).log(eq("case_create"), eq("case"), anyString(), anyString());
    }

    /** TC5: POST /api/v1/cases title 为空返回 400。 */
    @Test
    void TC5_title为空返回400() {
        CaseController.CreateCaseRequest req = new CaseController.CreateCaseRequest();
        req.setTitle("   ");
        req.setDescription("需求");

        R<Case> r = controller.create(req);

        assertEquals(400, r.getCode(), "title 空/空白应返回 400");
        assertNull(r.getData());
        verify(caseService, never()).save(any(Case.class));
        verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    /** TC6: GET /api/v1/cases/{caseId}/derivations 返回派生列表。 */
    @Test
    void TC6_返回派生列表() {
        Derivation d = new Derivation();
        d.setId(10L);
        d.setCaseId("case-abc");
        d.setRole("team-po");
        when(derivationService.listByCaseId("case-abc")).thenReturn(List.of(d));

        R<List<Derivation>> r = controller.derivations("case-abc");

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals(1, r.getData().size());
        assertEquals("team-po", r.getData().get(0).getRole());
        verify(derivationService).listByCaseId("case-abc");
    }

    /** TC7: GET /api/v1/cases/{caseId}/artifacts 返回产物列表。 */
    @Test
    void TC7_返回产物列表() {
        Artifact a = new Artifact();
        a.setId(20L);
        a.setCaseId("case-abc");
        a.setType("prd");
        when(artifactService.listByCaseId("case-abc")).thenReturn(List.of(a));

        R<List<Artifact>> r = controller.artifacts("case-abc");

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals(1, r.getData().size());
        assertEquals("prd", r.getData().get(0).getType());
        verify(artifactService).listByCaseId("case-abc");
    }

    /** 边界：派生/产物列表为空时返回空列表而非 null。 */
    @Test
    void 派生产物列表为空时返回空列表() {
        when(derivationService.listByCaseId(anyString())).thenReturn(Collections.emptyList());
        when(artifactService.listByCaseId(anyString())).thenReturn(Collections.emptyList());

        R<List<Derivation>> rd = controller.derivations("case-x");
        R<List<Artifact>> ra = controller.artifacts("case-x");

        assertEquals(0, rd.getCode());
        assertTrue(rd.getData().isEmpty());
        assertEquals(0, ra.getCode());
        assertTrue(ra.getData().isEmpty());
    }
}
