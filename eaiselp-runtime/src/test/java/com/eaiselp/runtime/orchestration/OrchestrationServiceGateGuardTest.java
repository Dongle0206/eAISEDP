package com.eaiselp.runtime.orchestration;

import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.data.service.CheckpointService;
import com.eaiselp.runtime.context.DerivationContext;
import com.eaiselp.runtime.engine.DerivationEngine;
import com.eaiselp.runtime.hierarchy.GovernanceInjectionService;
import com.eaiselp.runtime.hierarchy.QualityGateRuleService;
import com.eaiselp.runtime.workspace.ArtifactFileService;
import com.eaiselp.runtime.workspace.CICDTriggerService;
import com.eaiselp.runtime.workspace.CodeValidationService;
import com.eaiselp.runtime.workspace.DingTalkNotifier;
import com.eaiselp.runtime.workspace.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OrchestrationService 门禁终判防旁路（D10）与门禁步骤治理注入豁免（M2 裁判中立）单测。
 *
 * <p><b>D10 验收基线</b>（case-20260818 收尾，fast 档）：
 * <ul>
 *   <li>AC-D10.1：门禁终判（重做超限、failed 且 gateResult=FAIL）落在重试区间 → 拒绝断点续跑，
 *       步骤/状态保持原样（异步入口 log 拒绝；控制器入口 409 业务码，见 RuntimeController）</li>
 *   <li>AC-D10.3：无门禁终判的普通失败编排 → 断点续跑行为与现状一致（正常重置重跑，不误伤）</li>
 * </ul>
 *
 * <p><b>M2 豁免验收基线</b>（AC-M2.2）：gate 类型步骤（llm_review）组装 prompt 时不携带
 * governanceContext（裁判中立，防治理措辞操纵判定）；非门禁步骤注入不变。</p>
 *
 * <p><b>测试形态</b>：纯 Mockito（无 Spring 上下文）。编排状态经 getState 的 DB 恢复链路
 * （recordMapper.selectById → stepsJson 反序列化 → stateMap）注入内存态，随后直接驱动
 * retryFromStep / runFromStep（单测线程内同步执行，@Async 代理不生效）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestrationServiceGateGuardTest {

    @Mock DerivationEngine engine;
    @Mock CapabilityLoader capabilityLoader;
    @Mock AdapterFactory adapterFactory;
    @Mock ArtifactFileService artifactFileService;
    @Mock GitService gitService;
    @Mock CICDTriggerService cicdTriggerService;
    @Mock CodeValidationService codeValidationService;
    @Mock CheckpointService checkpointService;
    @Mock OrchestrationRecordMapper recordMapper;
    @Mock DingTalkNotifier dingTalkNotifier;
    @Mock GovernanceInjectionService governanceInjectionService;
    @Mock QualityGateRuleService qualityGateRuleService;

    @InjectMocks
    OrchestrationService service;

    // ===== 终判语义（finalFailedGateInRange）：只认 failed + FAIL，近似态不算 =====

    @Test
    void 终判判定_只认failed且FAIL_中间态FAILWARN与基础设施失败不算() {
        List<OrchestrationState.StepResult> steps = new ArrayList<>();
        steps.add(step(1, "team-dev", "code", "success", null));
        steps.add(step(2, "team-reviewer", "review", "failed", "FAIL"));          // 终判：重做超限
        steps.get(1).setGateType("llm_review");
        steps.get(1).setGateReason("存在严重缺陷");

        OrchestrationState.StepResult hit = OrchestrationService.finalFailedGateInRange(steps, 1);
        assertNotNull(hit, "failed + gateResult=FAIL 的门禁步骤是终判，必须命中");
        assertEquals(2, hit.getIndex());

        // 打回重做中的中间态（FAIL 但 pending/success）不算终判
        assertNull(OrchestrationService.finalFailedGateInRange(List.of(
                step(1, "team-reviewer", "review", "pending", "FAIL")), 1),
                "打回重做中的 FAIL（pending）不是终判");
        assertNull(OrchestrationService.finalFailedGateInRange(List.of(
                step(1, "team-reviewer", "review", "success", "PASS")), 1),
                "已通过的步骤不是终判");
        // FAIL_WARN（fail_action=warn 记录放行）不算终判
        assertNull(OrchestrationService.finalFailedGateInRange(List.of(
                step(1, "team-reviewer", "review", "failed", "FAIL_WARN")), 1),
                "FAIL_WARN 是记录放行语义，不是终判");
        // 门禁步骤的基础设施失败（无 FAIL 判定，如 LLM 超时）不算终判——可正常重试
        assertNull(OrchestrationService.finalFailedGateInRange(List.of(
                step(1, "team-reviewer", "review", "failed", null)), 1),
                "门禁步骤基础设施失败（无判定）可正常重试");
        // 普通失败（无门禁）不算终判
        assertNull(OrchestrationService.finalFailedGateInRange(List.of(
                step(1, "team-dev", "code", "failed", null)), 1));
        // 终判在区间外（fromStep > 终判步序号）不命中
        assertNull(OrchestrationService.finalFailedGateInRange(steps, 3),
                "fromStep 起的区间不含终判步骤时不命中");
    }

    // ===== AC-D10.1：有终判 → 拒绝重试 =====

    @Test
    void 门禁终判在重试区间_断点续跑被拒绝_步骤与状态保持原样() {
        // 步骤2 = 门禁终判（failed + gateResult=FAIL）；fromStep=1 区间覆盖该步骤
        OrchestrationRecord rec = record("failed", String.join(",",
                "{\"index\":1,\"role\":\"team-po\",\"roleLabel\":\"产品经理(PO)\",\"artifactType\":\"prd\",\"status\":\"success\"}",
                "{\"index\":2,\"role\":\"team-reviewer\",\"roleLabel\":\"代码审查\",\"artifactType\":\"review\","
                        + "\"status\":\"failed\",\"gateType\":\"llm_review\",\"gateResult\":\"FAIL\","
                        + "\"gateReason\":\"存在严重缺陷\",\"rerunCount\":2}",
                "{\"index\":3,\"role\":\"team-ops\",\"roleLabel\":\"运维(Ops)\",\"artifactType\":\"deploy\",\"status\":\"skipped\"}"));
        when(recordMapper.selectById(1L)).thenReturn(rec);

        service.retryFromStep(1L, 1, 0L);

        OrchestrationState state = service.getState(1L);
        assertEquals("failed", state.getSteps().get(1).getStatus(),
                "终判步骤不得被重置为 pending（拒绝旁路）");
        assertEquals("FAIL", state.getSteps().get(1).getGateResult(), "终审判定留痕保留");
        assertEquals("skipped", state.getSteps().get(2).getStatus(), "后续跳过步骤不被重置");
        assertEquals("failed", state.getStatus(), "编排状态保持 failed，不被改写为 pending");
        verifyNoInteractions(engine, capabilityLoader, governanceInjectionService,
                checkpointService, dingTalkNotifier);
    }

    // ===== AC-D10.3：无终判 → 行为与现状一致 =====

    @Test
    void 无门禁终判的普通失败_断点续跑正常执行_行为与现状一致() {
        // 步骤2 = 普通失败（LLM 超时，无门禁判定）；fromStep=2 重跑步骤2-3
        OrchestrationRecord rec = record("failed", String.join(",",
                "{\"index\":1,\"role\":\"team-po\",\"roleLabel\":\"产品经理(PO)\",\"artifactType\":\"prd\",\"status\":\"success\"}",
                "{\"index\":2,\"role\":\"team-dev\",\"roleLabel\":\"开发(Dev)\",\"artifactType\":\"code\","
                        + "\"status\":\"failed\",\"error\":\"LLM 超时\"}",
                "{\"index\":3,\"role\":\"team-ops\",\"roleLabel\":\"运维(Ops)\",\"artifactType\":\"deploy\",\"status\":\"skipped\"}"));
        when(recordMapper.selectById(1L)).thenReturn(rec);
        when(capabilityLoader.getAgent(anyString())).thenReturn(new AgentDefinition());

        service.retryFromStep(1L, 2, 0L);

        OrchestrationState state = service.getState(1L);
        assertEquals("success", state.getSteps().get(1).getStatus(), "普通失败步骤正常重跑（不被终判闸误伤）");
        assertEquals("success", state.getSteps().get(2).getStatus(), "区间内后续步骤照常执行");
        assertEquals("done", state.getStatus(), "重试完成后编排状态照常置 done");
        verify(engine, times(2)).derive(any(), anyString(), anyString(), any(DerivationContext.class));
    }

    // ===== AC-M2.2：门禁步骤豁免治理注入，非门禁步骤照常携带 =====

    @Test
    void 门禁步骤豁免治理注入_非门禁步骤照常携带() {
        // 步骤1 普通步骤、步骤2 llm_review 门禁步骤（均为 pending，等价 retryFromStep 重置后的入口态）
        OrchestrationRecord rec = record("failed", String.join(",",
                "{\"index\":1,\"role\":\"team-dev\",\"roleLabel\":\"开发(Dev)\",\"artifactType\":\"code\",\"status\":\"pending\"}",
                "{\"index\":2,\"role\":\"team-reviewer\",\"roleLabel\":\"代码审查\",\"artifactType\":\"review\","
                        + "\"status\":\"pending\",\"gateType\":\"llm_review\",\"gateResult\":\"FAIL\"}"));
        when(recordMapper.selectById(1L)).thenReturn(rec);
        when(capabilityLoader.getAgent(anyString())).thenReturn(new AgentDefinition());
        // runFromStep 入口重解析注入快照（governanceContext 不落库）：mock 一份治理文本
        when(governanceInjectionService.resolveInjection("case-1", 0L)).thenReturn(
                new GovernanceInjectionService.InjectionResult("GOV_TEXT", List.of("P11"), false, 8));

        service.getState(1L);   // DB 恢复 → stateMap（runFromStep 从 stateMap 取）
        service.runFromStep(1L, 0L, 1);

        ArgumentCaptor<DerivationContext> cap = ArgumentCaptor.forClass(DerivationContext.class);
        verify(engine, times(2)).derive(any(), anyString(), anyString(), cap.capture());
        List<DerivationContext> ctxs = cap.getAllValues();
        assertEquals("GOV_TEXT", ctxs.get(0).getGovernanceContext(),
                "非门禁步骤照常携带治理注入（既有行为不变）");
        assertNull(ctxs.get(1).getGovernanceContext(),
                "门禁步骤豁免治理注入（裁判中立：门禁角色只审产出，不受治理文本影响）");
    }

    // ===== 辅助 =====

    private static OrchestrationState.StepResult step(int index, String role, String artifactType,
                                                      String status, String gateResult) {
        OrchestrationState.StepResult s = new OrchestrationState.StepResult();
        s.setIndex(index);
        s.setRole(role);
        s.setRoleLabel(OrchestrationState.StepResult.ROLE_LABELS.getOrDefault(role, role));
        s.setArtifactType(artifactType);
        s.setStatus(status);
        s.setGateResult(gateResult);
        return s;
    }

    private static OrchestrationRecord record(String status, String stepsJson) {
        OrchestrationRecord r = new OrchestrationRecord();
        r.setId(1L);
        r.setTenantId(0L);
        r.setCaseId("case-1");
        r.setRequirement("测试需求");
        r.setTier("fast");
        r.setStatus(status);
        r.setStepsJson("[" + stepsJson + "]");
        return r;
    }
}
