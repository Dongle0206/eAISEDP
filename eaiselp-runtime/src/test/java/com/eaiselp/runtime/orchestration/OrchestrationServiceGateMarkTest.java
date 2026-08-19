package com.eaiselp.runtime.orchestration;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.mapper.DerivationMapper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * OrchestrationService gate_result 埋点接线单测（case-20260818 T21，AC-F1.4，最高风险任务）。
 *
 * <p><b>验收基线（api-contracts §7 / PRD §6.3）</b>——四分支行为与改造前逐项等价 + 埋点值正确：</p>
 * <ul>
 *   <li>PASS 支：门禁 PASS → 该次派生行 UPDATE gate_result=PASS；gate=null（LLM 未输出判定）
 *       → 不写保持 NULL；</li>
 *   <li>FAIL+warn → FAIL_WARN；FAIL+超限（终判）→ FAIL；FAIL+打回 → FAIL，重跑通过后新行 PASS
 *       （同 case+role 两行 FAIL/PASS，RT=PASS.finished_at−FAIL.finished_at 的数据源）；</li>
 *   <li><b>铁律</b>：埋点 UPDATE 抛异常仅 ERROR，打回/终判/PASS 主流程行为逐分支不变
 *       （步骤状态/编排终态与无埋点时一致）；</li>
 *   <li>精准 UPDATE：按 derivationId（T1 回填）定位，一次门禁判定恰好一次 UPDATE。</li>
 * </ul>
 *
 * <p>测试形态：纯 Mockito（同 OrchestrationServiceGateGuardTest）。state 经 start() 预填后
 * 原地改写步骤（stateMap 内同一引用），runAsync 在单测线程内同步执行。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestrationServiceGateMarkTest {

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
    @Mock DerivationMapper derivationMapper;

    @InjectMocks
    OrchestrationService service;

    @BeforeAll
    static void initLambdaCache() {
        // LambdaUpdateWrapper<Derivation> 列缓存初始化（同 MilestoneServiceImplTransitTest 先例）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Derivation.class);
    }

    // ===== 场景装配 =====

    /** 双步流水线：0=code 步骤（team-dev）、1=llm_review 门禁步骤（team-reviewer）。 */
    private OrchestrationState seedPipeline() {
        Long id = service.start("测试需求", "case-mark", "fast");
        OrchestrationState state = service.getState(id);
        state.getSteps().clear();
        state.getSteps().add(step(1, "team-dev", "code"));
        OrchestrationState.StepResult gate = step(2, "team-reviewer", "review");
        gate.setGateType("llm_review");
        gate.setGateRuleId(5L);
        state.getSteps().add(gate);
        return state;
    }

    private static OrchestrationState.StepResult step(int index, String role, String artifactType) {
        OrchestrationState.StepResult s = new OrchestrationState.StepResult();
        s.setIndex(index);
        s.setRole(role);
        s.setRoleLabel(OrchestrationState.StepResult.ROLE_LABELS.getOrDefault(role, role));
        s.setArtifactType(artifactType);
        s.setStatus("pending");
        return s;
    }

    /** 派生结果构造（derivationId=T1 回填语义；output 即 LLM 产出）。 */
    private static DerivationEngine.DerivationResult deriveResult(String role, Long derivationId, String output) {
        return DerivationEngine.DerivationResult.builder()
                .role(role).caseId("case-mark").output(output).derivationId(derivationId)
                .build();
    }

    private void stubCommon() {
        // AgentDefinition 带角色名（derive 的答案按 agent.getName() 分流门禁/普通步骤）
        when(capabilityLoader.getAgent(anyString())).thenAnswer(inv -> {
            AgentDefinition a = new AgentDefinition();
            a.setName(inv.getArgument(0));
            return a;
        });
        when(qualityGateRuleService.listEnabledByStage(any())).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaUpdateWrapper<Derivation>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    /**
     * 触发 MP 3.5.5 条件段的惰性参数物化（eq 的值在 getSqlSegment() 首次调用时才注册进
     * paramNameValuePairs），返回物化后的参数值集。
     */
    private static java.util.Collection<Object> forceParams(LambdaUpdateWrapper<Derivation> w) {
        w.getSqlSegment();
        return w.getParamNameValuePairs().values();
    }

    // ===== PASS 支 =====

    @Test
    void 门禁PASS_埋点PASS_非门禁步骤不埋点() {
        stubCommon();
        OrchestrationState state = seedPipeline();
        when(engine.derive(any(), anyString(), anyString(), any(DerivationContext.class)))
                .thenAnswer(inv -> {
                    String role = inv.getArgument(0) == null ? "" : ((AgentDefinition) inv.getArgument(0)).getName();
                    if ("team-reviewer".equals(role)) {
                        return deriveResult("team-reviewer", 777L, "审查通过\nGATE:PASS");
                    }
                    return deriveResult("team-dev", 500L, "# 代码\n实现完成");
                });
        when(derivationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.runAsync(state.getId(), 0L);

        assertEquals("done", state.getStatus(), "主流程行为不变：编排正常完成");
        assertEquals("PASS", state.getSteps().get(1).getGateResult());
        ArgumentCaptor<LambdaUpdateWrapper<Derivation>> captor = wrapperCaptor();
        verify(derivationMapper, times(1)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<Derivation> w = captor.getValue();
        var params = forceParams(w);
        assertTrue(params.contains("PASS"), "埋点值=PASS");
        assertTrue(params.contains(777L), "按 derivationId 精准 UPDATE");
        assertFalse(params.contains(500L), "非门禁派生行不埋点");
    }

    @Test
    void 门禁无判定标记_步骤视为PASS放行_但埋点保持NULL不写() {
        stubCommon();
        OrchestrationState state = seedPipeline();
        when(engine.derive(any(), anyString(), anyString(), any(DerivationContext.class)))
                .thenAnswer(inv -> {
                    String role = inv.getArgument(0) == null ? "" : ((AgentDefinition) inv.getArgument(0)).getName();
                    if ("team-reviewer".equals(role)) {
                        return deriveResult("team-reviewer", 888L, "审查完成（忘输出判定标记）");
                    }
                    return deriveResult("team-dev", 501L, "# 代码");
                });

        service.runAsync(state.getId(), 0L);

        assertEquals("PASS", state.getSteps().get(1).getGateResult(), "步骤侧视为 PASS 放行（现状语义）");
        assertEquals("done", state.getStatus());
        verify(derivationMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        // gate=null → 不写保持 NULL（api-contracts §7 值域）
    }

    // ===== FAIL 三支 =====

    @Test
    void 门禁FAIL_warn放行_埋点FAIL_WARN() {
        stubCommon();
        OrchestrationState state = seedPipeline();
        state.getSteps().get(1).setGateFailAction("warn");
        when(engine.derive(any(), anyString(), anyString(), any(DerivationContext.class)))
                .thenAnswer(inv -> {
                    String role = inv.getArgument(0) == null ? "" : ((AgentDefinition) inv.getArgument(0)).getName();
                    if ("team-reviewer".equals(role)) {
                        return deriveResult("team-reviewer", 779L, "GATE:FAIL: 轻微问题");
                    }
                    return deriveResult("team-dev", 502L, "# 代码");
                });
        when(derivationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.runAsync(state.getId(), 0L);

        // 主流程行为等价：warn=记录放行——步骤照常 success；步骤级 gateResult 维持改造前
        // fall-through 形态（先 FAIL_WARN 后被 PASS 行覆盖，既有语义不动，T21 等价性铁律）
        assertEquals("PASS", state.getSteps().get(1).getGateResult(), "步骤级判定维持改造前形态");
        assertEquals("success", state.getSteps().get(1).getStatus(), "记录放行——步骤照常成功");
        assertEquals("done", state.getStatus());
        ArgumentCaptor<LambdaUpdateWrapper<Derivation>> captor = wrapperCaptor();
        verify(derivationMapper, times(1)).update(isNull(), captor.capture());
        var params = forceParams(captor.getValue());
        assertTrue(params.contains("FAIL_WARN"), "埋点值=FAIL_WARN（t_derivation 与步骤级形态解耦）");
        assertTrue(params.contains(779L));
    }

    @Test
    void 门禁FAIL_超限终判_埋点FAIL_后续步骤skipped() {
        stubCommon();
        OrchestrationState state = seedPipeline();
        state.getSteps().get(1).setGateMaxRetries(0);   // 规则级 0 轮 → 首次 FAIL 即终判
        when(engine.derive(any(), anyString(), anyString(), any(DerivationContext.class)))
                .thenAnswer(inv -> {
                    String role = inv.getArgument(0) == null ? "" : ((AgentDefinition) inv.getArgument(0)).getName();
                    if ("team-reviewer".equals(role)) {
                        return deriveResult("team-reviewer", 780L, "GATE:FAIL: 严重缺陷");
                    }
                    return deriveResult("team-dev", 503L, "# 代码");
                });
        when(derivationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.runAsync(state.getId(), 0L);

        assertEquals("FAIL", state.getSteps().get(1).getGateResult(), "终判语义不变");
        assertEquals("failed", state.getSteps().get(1).getStatus());
        ArgumentCaptor<LambdaUpdateWrapper<Derivation>> captor = wrapperCaptor();
        verify(derivationMapper, times(1)).update(isNull(), captor.capture());
        var params = forceParams(captor.getValue());
        assertTrue(params.contains("FAIL"), "埋点值=FAIL");
        assertTrue(params.contains(780L));
    }

    @Test
    void 门禁FAIL_打回重做_FAIL行与重跑PASS行各埋点一次() {
        stubCommon();
        OrchestrationState state = seedPipeline();
        state.getSteps().get(1).setGateMaxRetries(2);   // 允许 2 轮重做
        AtomicInteger gateCalls = new AtomicInteger();
        when(engine.derive(any(), anyString(), anyString(), any(DerivationContext.class)))
                .thenAnswer(inv -> {
                    String role = inv.getArgument(0) == null ? "" : ((AgentDefinition) inv.getArgument(0)).getName();
                    if ("team-reviewer".equals(role)) {
                        // 第一次 FAIL（derivationId=781）、重跑 PASS（derivationId=782）
                        return gateCalls.incrementAndGet() == 1
                                ? deriveResult("team-reviewer", 781L, "GATE:FAIL: 必须修复 X")
                                : deriveResult("team-reviewer", 782L, "已修复\nGATE:PASS");
                    }
                    return deriveResult("team-dev", 504L, "# 代码");
                });
        when(derivationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.runAsync(state.getId(), 0L);

        // 主流程行为等价：打回 code 步骤重做 → 重跑门禁通过 → 编排完成
        assertEquals("done", state.getStatus());
        assertEquals("PASS", state.getSteps().get(1).getGateResult());
        assertEquals(1, state.getSteps().get(0).getRerunCount(), "code 步骤被打回重做一次");

        ArgumentCaptor<LambdaUpdateWrapper<Derivation>> captor = wrapperCaptor();
        verify(derivationMapper, times(2)).update(isNull(), captor.capture());
        List<java.util.Collection<Object>> writes = captor.getAllValues().stream()
                .map(OrchestrationServiceGateMarkTest::forceParams).toList();
        boolean failRow = writes.stream().anyMatch(p -> p.contains("FAIL") && p.contains(781L));
        boolean passRow = writes.stream().anyMatch(p -> p.contains("PASS") && p.contains(782L));
        assertTrue(failRow, "打回轮 FAIL 行按 derivationId=781 埋 FAIL（AC-F1.4 断言数据）");
        assertTrue(passRow, "重跑轮 PASS 行按 derivationId=782 埋 PASS（RT=PASS.finished−FAIL.finished 的数据源）");
    }

    // ===== 铁律：埋点失败绝不影响编排主流程 =====

    @Test
    void 埋点UPDATE异常_仅ERROR_主流程四分支行为不变() {
        stubCommon();
        when(derivationMapper.update(isNull(), any(LambdaUpdateWrapper.class)))
                .thenThrow(new RuntimeException("mock DB down"));
        OrchestrationState state = seedPipeline();
        state.getSteps().get(1).setGateMaxRetries(2);
        AtomicInteger gateCalls = new AtomicInteger();
        when(engine.derive(any(), anyString(), anyString(), any(DerivationContext.class)))
                .thenAnswer(inv -> {
                    String role = inv.getArgument(0) == null ? "" : ((AgentDefinition) inv.getArgument(0)).getName();
                    if ("team-reviewer".equals(role)) {
                        return gateCalls.incrementAndGet() == 1
                                ? deriveResult("team-reviewer", 783L, "GATE:FAIL: 修复 Y")
                                : deriveResult("team-reviewer", 784L, "GATE:PASS");
                    }
                    return deriveResult("team-dev", 505L, "# 代码");
                });

        // 打回路径埋点异常不重抛：打回照旧发生、重跑照旧通过、编排照旧 done
        assertDoesNotThrow(() -> service.runAsync(state.getId(), 0L));
        assertEquals("done", state.getStatus(), "埋点失败不影响打回/重做/完成主流程（PRD §6.3）");
        assertEquals(1, state.getSteps().get(0).getRerunCount());
        assertEquals("PASS", state.getSteps().get(1).getGateResult());
        verify(derivationMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void derivationId回填缺失_跳过埋点_不报错() {
        stubCommon();
        OrchestrationState state = seedPipeline();
        // 落库失败路径：DerivationResult.derivationId 保持 null（T1 契约）
        when(engine.derive(any(), anyString(), anyString(), any(DerivationContext.class)))
                .thenAnswer(inv -> {
                    String role = inv.getArgument(0) == null ? "" : ((AgentDefinition) inv.getArgument(0)).getName();
                    if ("team-reviewer".equals(role)) {
                        return deriveResult("team-reviewer", null, "GATE:PASS");
                    }
                    return deriveResult("team-dev", null, "# 代码");
                });

        assertDoesNotThrow(() -> service.runAsync(state.getId(), 0L));
        assertEquals("done", state.getStatus());
        verify(derivationMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    // ===== 断点续跑路径不接线（口径自洽） =====

    @Test
    void 断点续跑executeStep路径_门禁步骤不埋点保持NULL() {
        stubCommon();
        // 重试态编排：步骤1 已成功、步骤2 门禁 pending → runFromStep 从步骤 2 起跑
        OrchestrationRecord rec = new OrchestrationRecord();
        rec.setId(99L);
        rec.setTenantId(0L);
        rec.setCaseId("case-mark");
        rec.setRequirement("测试需求");
        rec.setTier("fast");
        rec.setStatus("failed");
        rec.setStepsJson("[" + String.join(",",
                "{\"index\":1,\"role\":\"team-dev\",\"roleLabel\":\"开发(Dev)\",\"artifactType\":\"code\",\"status\":\"success\"}",
                "{\"index\":2,\"role\":\"team-reviewer\",\"roleLabel\":\"代码审查\",\"artifactType\":\"review\","
                        + "\"status\":\"pending\",\"gateType\":\"llm_review\"}") + "]");
        when(recordMapper.selectById(99L)).thenReturn(rec);
        when(engine.derive(any(), anyString(), anyString(), any(DerivationContext.class)))
                .thenReturn(deriveResult("team-reviewer", 790L, "GATE:PASS"));
        when(governanceInjectionService.resolveInjection(anyString(), any()))
                .thenReturn(new GovernanceInjectionService.InjectionResult(null, List.of(), false, 0));

        service.getState(99L);
        service.runFromStep(99L, 0L, 2);

        assertEquals("done", service.getState(99L).getStatus(), "断点续跑照常完成");
        verify(derivationMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        // executeStep 路径不接线（RT 走≈近似分支口径自洽，api-contracts §7）
    }
}
