package com.eaiselp.runtime.controller;

import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.service.TenantSubscriptionService;
import com.eaiselp.runtime.engine.DerivationEngine;
import com.eaiselp.runtime.orchestration.OrchestrationService;
import com.eaiselp.runtime.orchestration.OrchestrationState;
import com.eaiselp.runtime.task.DerivationAsyncRunner;
import com.eaiselp.runtime.task.DerivationTaskService;
import com.eaiselp.runtime.task.DerivationTaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RuntimeController 单测（Wave4-B）。
 *
 * <p>采用<b>纯 Mockito 方式</b>（@ExtendWith(MockitoExtension) + 直接调 Controller 方法）。
 * 原因：RuntimeController 的方法贴了 {@code @RateLimit}，@WebMvcTest 会拉起 RateLimitInterceptor
 * + PermissionInterceptor + JwtAuthInterceptor，配置成本高；任务说明明确允许纯 Mockito，优先保证通过。
 *
 * <p>依赖接口/Bean 用 @Mock，具体类 RuntimeController 用 @InjectMocks 真实实例。
 * 注意：derive 走异步分支，调用 {@link DerivationAsyncRunner#deriveAsync}（@Mock）、
 * {@link DerivationTaskService#createPending}（@Mock）、{@link CapabilityLoader#getAgent}（@Mock）。
 *
 * <p>覆盖用例：
 * <ul>
 *   <li>TC1 POST derive 异步模式返回 taskId（202 + status=pending），createPending 被调用；</li>
 *   <li>TC2 POST derive 线程池满（asyncRunner 抛 RejectedExecutionException）→ 503；</li>
 *   <li>TC3 GET derive/{taskId} 返回 running；</li>
 *   <li>TC4 GET derive/{taskId} 不存在返回 not_found；</li>
 *   <li>TC5 GET derive/{taskId} 返回 success + 结果。</li>
 * </ul>
 *
 * <p><b>多租户上下文</b>：derive 内部调 {@code TenantContext.get()}，测试 setUp 显式 set 非空 tenant，
 * 模拟 JwtAuthInterceptor 注入后的请求线程状态。
 */
@ExtendWith(MockitoExtension.class)
class RuntimeControllerTest {

    @Mock DerivationEngine engine;
    @Mock CapabilityLoader capabilityLoader;
    @Mock DerivationAsyncRunner asyncRunner;
    @Mock DerivationTaskService taskService;
    @Mock AuditService auditService;
    @Mock OrchestrationService orchestrationService;
    @Mock TenantSubscriptionService subscriptionService;

    @InjectMocks RuntimeController controller;

    private AgentDefinition agent;

    @BeforeEach
    void setUp() {
        // derive 方法内部读 TenantContext.get()（异步线程跨线程传递用），测试需显式注入非空 tenant
        TenantContext.set(1L);
        agent = new AgentDefinition();
        agent.setName("team-po");
        agent.setModel("sonnet");
        agent.setPrompt("你是一个产品经理");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        LoginUser.set(null);
    }

    /** TC1: POST derive 异步模式，立即返回 taskId（HTTP 202），createPending 被调用。 */
    @Test
    @SuppressWarnings("unchecked")
    void TC1_derive异步模式返回TaskId() {
        when(capabilityLoader.getAgent("team-po")).thenReturn(agent);
        when(taskService.createPending(eq("team-po"), any(), any())).thenReturn(99L);
        // asyncRunner.deriveAsync 不抛异常 = 提交成功
        doNothing().when(asyncRunner)
                .deriveAsync(eq(99L), eq(agent), anyString(), any(), any(), eq(1L));

        RuntimeController.DeriveRequest req = new RuntimeController.DeriveRequest();
        req.setRole("team-po");
        req.setTask("写 PRD");
        req.setCaseId("case-1");
        req.setStage("plan");

        ResponseEntity<R<Map<String, Object>>> resp = controller.derive(req);

        assertEquals(202, resp.getStatusCode().value(), "异步派生应立即返回 202 Accepted");
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getCode());
        Map<String, Object> data = resp.getBody().getData();
        assertNotNull(data);
        assertEquals(99L, data.get("taskId"), "应返回 createPending 生成的 taskId");
        assertEquals("pending", data.get("status"), "初始状态应为 pending");
        // createPending 必须被调用（异步模式的标志）
        verify(taskService).createPending("team-po", "case-1", "plan");
        // asyncRunner 必须提交后台任务
        verify(asyncRunner).deriveAsync(eq(99L), eq(agent), eq("写 PRD"), eq("case-1"), any(), eq(1L));
        // 审计：派生发起
        verify(auditService).log(eq("derive_create"), eq("derivation"), eq("99"), anyString());
    }

    /** TC2: POST derive 线程池满（asyncRunner 抛 RejectedExecutionException）→ 返回 503。 */
    @Test
    @SuppressWarnings("unchecked")
    void TC2_线程池满返回503() {
        when(capabilityLoader.getAgent("team-po")).thenReturn(agent);
        when(taskService.createPending(anyString(), any(), any())).thenReturn(100L);
        // 模拟线程池满：asyncRunner 抛 RejectedExecutionException（与 Controller 内 catch 一致）
        doThrow(new RejectedExecutionException("pool full"))
                .when(asyncRunner).deriveAsync(eq(100L), eq(agent), anyString(), any(), any(), eq(1L));

        RuntimeController.DeriveRequest req = new RuntimeController.DeriveRequest();
        req.setRole("team-po");
        req.setTask("写 PRD");

        ResponseEntity<R<Map<String, Object>>> resp = controller.derive(req);

        assertEquals(503, resp.getStatusCode().value(), "线程池满应返回 503");
        assertNotNull(resp.getBody());
        assertEquals(503, resp.getBody().getCode(), "R.code 也应为 503");
        assertNull(resp.getBody().getData());
        assertNotNull(resp.getBody().getMsg());
        // 审计：派生排队满失败（result=failure）
        verify(auditService).log(eq("derive_rejected"), eq("derivation"), eq("100"),
                anyString(), eq("failure"), anyString());
    }

    /** TC2 补充：角色未注册 → R.fail（不进异步，不调 createPending）。 */
    @Test
    @SuppressWarnings("unchecked")
    void 角色未注册同步失败不进异步() {
        when(capabilityLoader.getAgent("team-unknown")).thenReturn(null);

        RuntimeController.DeriveRequest req = new RuntimeController.DeriveRequest();
        req.setRole("team-unknown");
        req.setTask("写 PRD");

        ResponseEntity<R<Map<String, Object>>> resp = controller.derive(req);

        // 角色未注册返回 200 + R.fail（同步校验不进异步，不返回 503/202）
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertNotEquals(0, resp.getBody().getCode(), "未注册应非 0 成功码");
        // 关键：createPending 不应被调用（同步校验拒绝前不预占 DB 行）
        verify(taskService, never()).createPending(anyString(), any(), any());
        verify(asyncRunner, never()).deriveAsync(anyLong(), any(), anyString(), any(), any(), any());
    }

    /** TC3: GET derive/{taskId} 返回 running 状态。 */
    @Test
    void TC3_getTask返回running状态() {
        DerivationTaskState running = DerivationTaskState.builder()
                .taskId(1L).status("running").role("team-po").caseId("case-1").build();
        when(taskService.getTask(1L)).thenReturn(running);

        R<DerivationTaskState> r = controller.getTask(1L);

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals("running", r.getData().getStatus());
        assertEquals("team-po", r.getData().getRole());
        verify(taskService).getTask(1L);
    }

    /** TC4: GET derive/{taskId} 不存在返回 not_found（HTTP 仍 200，靠 status 字段区分）。 */
    @Test
    void TC4_getTask不存在返回NotFound() {
        DerivationTaskState notFound = DerivationTaskState.builder().status("not_found").build();
        when(taskService.getTask(999L)).thenReturn(notFound);

        R<DerivationTaskState> r = controller.getTask(999L);

        // 统一 200（SE §4.1.1：not_found 不抛 404，便于前端统一判断）
        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals("not_found", r.getData().getStatus());
        verify(taskService).getTask(999L);
    }

    /** TC5: GET derive/{taskId} 返回 success + 结果（result 字段非空）。 */
    @Test
    void TC5_getTask返回Success带结果() {
        DerivationEngine.DerivationResult result = DerivationEngine.DerivationResult.builder()
                .status("success")
                .output("PRD 正文")
                .model("sonnet")
                .inputTokens(100)
                .outputTokens(50)
                .build();
        DerivationTaskState success = DerivationTaskState.builder()
                .taskId(2L).status("success").role("team-po").result(result).build();
        when(taskService.getTask(2L)).thenReturn(success);

        R<DerivationTaskState> r = controller.getTask(2L);

        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertEquals("success", r.getData().getStatus());
        assertNotNull(r.getData().getResult(), "success 应携带 result");
        assertEquals("PRD 正文", r.getData().getResult().getOutput());
        assertEquals(100, r.getData().getResult().getInputTokens());
        verify(taskService).getTask(2L);
    }

    // ==================== TC30 试用到期前置校验（T20 锚点 30，SE §4.3/§9.1.30） ====================

    /** TC30a: /derive 到期租户 → BizException(40003)，且不 createPending、不提交异步（不烧 token），审计 derive_trial_blocked。 */
    @Test
    void TC30a_derive到期租户_40003_不createPending不提交异步() {
        when(capabilityLoader.getAgent("team-po")).thenReturn(agent); // 角色校验通过
        LoginUser.set(JwtClaims.builder().userId(9L).username("alice").tenantId(1L).roles(List.of("engineer")).build());
        doThrow(new BizException(ResultCode.TRIAL_EXPIRED, "试用已到期，请联系平台管理员升级（platform_admin 可通过订阅管理接口延期/转正）"))
                .when(subscriptionService).assertNotExpired(1L);

        RuntimeController.DeriveRequest req = new RuntimeController.DeriveRequest();
        req.setRole("team-po");
        req.setTask("写 PRD");

        BizException ex = assertThrows(BizException.class, () -> controller.derive(req));
        assertEquals(ResultCode.TRIAL_EXPIRED, ex.getCode(), "到期派生必须 40003");
        assertTrue(ex.getMessage().contains("试用已到期"));

        // 关键断言：资源预占前拦截——createPending/deriveAsync 零调用（不烧 token）
        verify(taskService, never()).createPending(anyString(), any(), any());
        verify(asyncRunner, never()).deriveAsync(anyLong(), any(), anyString(), any(), any(), any());
        // 审计：resource_type=tenant，detail 含 tenantId/username
        verify(auditService).log(eq("derive_trial_blocked"), eq("tenant"), eq("1"),
                contains("\"username\":\"alice\""), eq("failure"), contains("试用已到期"));
    }

    /** TC30b: /orchestrate 到期租户 → BizException(40003)，且不 start、不 runAsync，审计 orchestrate_trial_blocked。 */
    @Test
    void TC30b_orchestrate到期租户_40003_不start不runAsync() {
        LoginUser.set(JwtClaims.builder().userId(9L).username("alice").tenantId(1L).roles(List.of("engineer")).build());
        doThrow(new BizException(ResultCode.TRIAL_EXPIRED, "试用已到期，请联系平台管理员升级（platform_admin 可通过订阅管理接口延期/转正）"))
                .when(subscriptionService).assertNotExpired(1L);

        RuntimeController.OrchestrateRequest req = new RuntimeController.OrchestrateRequest();
        req.setRequirement("写一个报销系统");

        BizException ex = assertThrows(BizException.class, () -> controller.orchestrate(req));
        assertEquals(ResultCode.TRIAL_EXPIRED, ex.getCode(), "到期编排必须 40003");

        // 关键断言：start/runAsync 零调用（不预占编排行、不烧 token）
        verify(orchestrationService, never()).start(any(), any(), any());
        verify(orchestrationService, never()).runAsync(any(), any());
        verify(auditService).log(eq("orchestrate_trial_blocked"), eq("tenant"), eq("1"),
                contains("\"username\":\"alice\""), eq("failure"), contains("试用已到期"));
    }

    /** TC30c: 未到期租户 derive 正常走异步（前置校验不误伤，回归）。 */
    @Test
    @SuppressWarnings("unchecked")
    void TC30c_未到期租户_derive正常返回202() {
        when(capabilityLoader.getAgent("team-po")).thenReturn(agent);
        when(taskService.createPending(eq("team-po"), any(), any())).thenReturn(77L);
        doNothing().when(asyncRunner).deriveAsync(eq(77L), eq(agent), anyString(), any(), any(), eq(1L));
        // subscriptionService.assertNotExpired 不抛异常 = 未到期放行

        RuntimeController.DeriveRequest req = new RuntimeController.DeriveRequest();
        req.setRole("team-po");
        req.setTask("写 PRD");

        ResponseEntity<R<Map<String, Object>>> resp = controller.derive(req);

        assertEquals(202, resp.getStatusCode().value(), "未到期租户派生不受影响");
        verify(subscriptionService).assertNotExpired(1L);
        verify(taskService).createPending("team-po", null, null);
    }

    /**
     * TC30d [M2 安全评审]: /orchestrate/{id}/retry 到期租户 → BizException(40003)，
     * 不 retryFromStep（不重跑派生步骤、不烧 token），审计 orchestrate_retry_trial_blocked。
     * retry 本质是派生的另一个触发器——与 /derive /orchestrate 同口径堵存量 JWT 到期烧 token 口。
     */
    @Test
    void TC30d_retry到期租户_40003_不retryFromStep() {
        OrchestrationState state = new OrchestrationState();
        state.setId(5L);
        state.setCaseId("case-1");
        state.setStatus("failed");
        OrchestrationState.StepResult ok = new OrchestrationState.StepResult();
        ok.setIndex(1);
        ok.setRole("team-po");
        ok.setStatus("success");
        OrchestrationState.StepResult bad = new OrchestrationState.StepResult();
        bad.setIndex(2);
        bad.setRole("team-dev");
        bad.setStatus("failed");
        state.setSteps(List.of(ok, bad));
        when(orchestrationService.getState(5L)).thenReturn(state);
        LoginUser.set(JwtClaims.builder().userId(9L).username("alice").tenantId(1L).roles(List.of("engineer")).build());
        doThrow(new BizException(ResultCode.TRIAL_EXPIRED, "试用已到期，请联系平台管理员升级（platform_admin 可通过订阅管理接口延期/转正）"))
                .when(subscriptionService).assertNotExpired(1L);

        BizException ex = assertThrows(BizException.class, () -> controller.retryOrchestration(5L, 0));

        assertEquals(ResultCode.TRIAL_EXPIRED, ex.getCode(), "到期重试必须 40003（M2：retry 是派生的另一触发器）");
        // 关键断言：门禁终判检查后、retryFromStep 前拦截——不重跑、不烧 token
        verify(orchestrationService, never()).retryFromStep(anyLong(), anyInt(), any());
        // 审计：resource_type=tenant，detail 含 tenantId/username（与 TC30a/b 同口径）
        verify(auditService).log(eq("orchestrate_retry_trial_blocked"), eq("tenant"), eq("1"),
                contains("\"username\":\"alice\""), eq("failure"), contains("试用已到期"));
    }

    /** TC30e [M2 回归]: 未到期租户 retry 正常断点续跑（前置校验不误伤既有重试路径）。 */
    @Test
    @SuppressWarnings("unchecked")
    void TC30e_未到期租户_retry正常续跑() {
        OrchestrationState state = new OrchestrationState();
        state.setId(5L);
        state.setCaseId("case-1");
        state.setStatus("failed");
        OrchestrationState.StepResult ok = new OrchestrationState.StepResult();
        ok.setIndex(1);
        ok.setRole("team-po");
        ok.setStatus("success");
        OrchestrationState.StepResult bad = new OrchestrationState.StepResult();
        bad.setIndex(2);
        bad.setRole("team-dev");
        bad.setStatus("failed");
        state.setSteps(List.of(ok, bad));
        when(orchestrationService.getState(5L)).thenReturn(state);
        // subscriptionService.assertNotExpired 不抛异常 = 未到期放行

        ResponseEntity<R<Map<String, Object>>> resp = controller.retryOrchestration(5L, 0);

        assertEquals(202, resp.getStatusCode().value(), "未到期租户重试不受影响");
        // step=0 自动定位第一个非成功步骤 = 第 2 步
        verify(orchestrationService).retryFromStep(5L, 2, 1L);
        verify(auditService).log(eq("orchestrate_retry"), eq("case"), eq("case-1"), anyString());
    }
}
