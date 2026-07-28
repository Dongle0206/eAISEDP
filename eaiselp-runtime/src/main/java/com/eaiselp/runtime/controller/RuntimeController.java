package com.eaiselp.runtime.controller;

import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.common.ratelimit.RateLimit;
import com.eaiselp.common.result.R;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.context.DerivationContext;
import com.eaiselp.runtime.engine.DerivationEngine;
import com.eaiselp.runtime.task.DerivationAsyncRunner;
import com.eaiselp.runtime.task.DerivationTaskService;
import com.eaiselp.runtime.task.DerivationTaskState;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runtime REST 入口（M2-DFX 异步化 + 限流）。
 *
 * <p><b>路径</b>：{@code /api/runtime}（沿用现状，无 /v1；SE §10 决策点 D-1，
 * 版本化属全局路由决策，单独立项统一改，不在本次 DFX 范围）。
 *
 * <p><b>异步化（M2-DFX，SE §4.1）</b>：
 * <ul>
 *   <li>{@code POST /derive}：立即返回 taskId（HTTP 202），LLM 调用转后台线程池；
 *       角色未注册仍 {@code R.fail}（同步校验不进异步）。</li>
 *   <li>{@code GET /derive/{taskId}}：轮询查状态（pending→running→success/failed/not_found）。</li>
 * </ul>
 *
 * <p><b>限流（M2-DFX，SE §4.2）</b>：
 * <ul>
 *   <li>POST /derive：10 次/分/租户（防 token 烧刷）；</li>
 *   <li>GET /derive/{taskId}：100 次/分/用户（防轮询打爆）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime")
@RequiredArgsConstructor
public class RuntimeController {

    private final DerivationEngine engine;
    private final CapabilityLoader capabilityLoader;
    private final DerivationAsyncRunner asyncRunner;
    private final DerivationTaskService taskService;
    private final AuditService auditService;

    /**
     * 手动派生单角色（M2-DFX 异步化：立即返回 taskId）。
     *
     * <p>原 M1 同步 return R.ok(engine.derive(...)) 改为：
     * ① 同步校验角色（未注册直接 fail，不进异步）；
     * ② createPending 预占 DB 行拿 taskId；
     * ③ asyncRunner.deriveAsync 提交后台线程池；
     * ④ 立即返回 202 + {taskId, status:pending}。
     *
     * <p>线程池满（queue+max 都满）→ AbortPolicy 抛 RejectedExecutionException →
     * 返回 503 + 排队满提示（SE §4.1.5）。
     */
    @PostMapping("/derive")
    @RateLimit(name = "derive", key = RateLimit.KeyType.TENANT,
            capacity = 10, refillPerMin = 10,
            message = "派生请求过于频繁，请稍后再试")
    public ResponseEntity<R<Map<String, Object>>> derive(@RequestBody DeriveRequest req) {
        // ① 同步校验角色（不进异步，快速失败）
        AgentDefinition agent = capabilityLoader.getAgent(req.getRole());
        if (agent == null) {
            return ResponseEntity.ok(R.fail("角色未注册或未加载: " + req.getRole()));
        }
        DerivationContext ctx = DerivationContext.builder()
                .task(req.getTask()).stage(req.getStage()).build();
        // ② 预占 DB 行拿 taskId（createPending 内部 INSERT pending + 内存 put）
        Long taskId = taskService.createPending(req.getRole(), req.getCaseId(), req.getStage());
        // ③ 提交后台线程池（多租户 tenantId 跨线程显式传递，供 MP 租户拦截器）
        Long tenantId = TenantContext.get();
        try {
            asyncRunner.deriveAsync(taskId, agent, req.getTask(), req.getCaseId(), ctx, tenantId);
        } catch (RejectedExecutionException e) {
            // 线程池满（queue=50 + max=20 → 最多 70 个，超则拒绝，SE §4.1.5 D-5）
            // 注意：此时 createPending 已插入了 pending 行，属可接受的脏数据
            // （后续可由 markFailed 补写或定时清理；M2 dogfooding 可接受，SE §11）
            log.warn("[Derive] 派生线程池排队已满，拒绝 taskId={}, role={}", taskId, req.getRole());
            // 审计：派生排队满失败
            auditService.log("derive_rejected", "derivation", String.valueOf(taskId),
                    "{\"role\":\"" + req.getRole() + "\",\"caseId\":\"" + req.getCaseId() + "\"}",
                    "failure", "派生线程池排队已满");
            return ResponseEntity.status(503)
                    .body(R.fail(503, "当前派生任务排队已满，请稍后重试"));
        }
        // 审计：派生发起（GRC 治理：LLM 调用可追溯）
        auditService.log("derive_create", "derivation", String.valueOf(taskId),
                "{\"role\":\"" + req.getRole() + "\",\"caseId\":\""
                        + (req.getCaseId() == null ? "" : req.getCaseId()) + "\",\"taskId\":" + taskId + "}");
        // ④ 立即返回 202 Accepted + taskId（前端轮询 GET /derive/{taskId}）
        return ResponseEntity.accepted().body(R.ok(Map.of("taskId", taskId, "status", "pending")));
    }

    /**
     * 查派生任务状态（M2-DFX 新增，前端轮询用）。
     *
     * <p>响应统一 200，靠 status 字段区分（SE §4.1.1）：pending/running/success/failed/not_found。
     * not_found 不抛 404，便于前端统一判断（SE §4.1.1）。
     */
    @GetMapping("/derive/{taskId}")
    @RateLimit(name = "derive-get", key = RateLimit.KeyType.USER,
            capacity = 100, refillPerMin = 100,
            message = "查询请求过于频繁，请稍后再试")
    public R<DerivationTaskState> getTask(@PathVariable Long taskId) {
        return R.ok(taskService.getTask(taskId));
    }

    @Data
    public static class DeriveRequest {
        private String role;
        private String task;
        private String caseId;
        private String stage;
    }
}
