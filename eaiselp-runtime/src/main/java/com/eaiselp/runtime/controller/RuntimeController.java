package com.eaiselp.runtime.controller;

import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.common.ratelimit.RateLimit;
import com.eaiselp.common.result.R;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.context.DerivationContext;
import com.eaiselp.runtime.engine.DerivationEngine;
import com.eaiselp.runtime.orchestration.OrchestrationService;
import com.eaiselp.runtime.orchestration.OrchestrationState;
import com.eaiselp.runtime.workspace.ArtifactFileService;
import com.eaiselp.runtime.workspace.CodeValidationService;
import com.eaiselp.runtime.workspace.GitService;
import com.eaiselp.runtime.task.DerivationAsyncRunner;
import com.eaiselp.runtime.task.DerivationTaskService;
import com.eaiselp.runtime.task.DerivationTaskState;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final OrchestrationService orchestrationService;
    private final ArtifactFileService artifactFileService;
    private final GitService gitService;
    private final CodeValidationService codeValidationService;

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

    // ======================== 编排模式 ========================

    /**
     * 一键编排：一句话需求 → 自动按流水线派生所有角色（PO→SE→Dev→Reviewer→QA→Ops）。
     *
     * <p>用户只需输入需求，平台自动串行派生 6 个角色，每步把前面步骤的产出传给下一步。
     * 立即返回编排 ID，前端轮询 {@code GET /orchestrate/{id}} 查看流水线进度。
     */
    @PostMapping("/orchestrate")
    @RateLimit(name = "orchestrate", key = RateLimit.KeyType.TENANT,
            capacity = 3, refillPerMin = 3,
            message = "编排请求过于频繁，请稍后再试")
    public ResponseEntity<R<Map<String, Object>>> orchestrate(@RequestBody OrchestrateRequest req) {
        if (req.getRequirement() == null || req.getRequirement().isBlank()) {
            return ResponseEntity.ok(R.fail("requirement（需求）不能为空"));
        }
        Long tenantId = TenantContext.get();
        Long orchId = orchestrationService.start(req.getRequirement(), req.getCaseId(), req.getTier());
        auditService.log("orchestrate_start", "case", req.getCaseId(),
                "{\"requirement\":\"" + safeJson(req.getRequirement()) + "\",\"orchestrationId\":" + orchId + "}");
        // 异步执行流水线
        orchestrationService.runAsync(orchId, tenantId);
        return ResponseEntity.accepted().body(R.ok(Map.of(
                "orchestrationId", orchId, "status", "pending")));
    }

    /**
     * 查询编排进度（前端轮询用）。
     *
     * <p>返回流水线步骤列表，每步含角色/状态/产出类型。status: pending/running/done/failed。</p>
     */
    @GetMapping("/orchestrate/{id}")
    @RateLimit(name = "orchestrate-get", key = RateLimit.KeyType.USER,
            capacity = 60, refillPerMin = 60,
            message = "查询请求过于频繁，请稍后再试")
    public R<OrchestrationState> getOrchestration(@PathVariable Long id) {
        OrchestrationState state = orchestrationService.getState(id);
        if (state == null) {
            OrchestrationState notFound = new OrchestrationState();
            notFound.setStatus("not_found");
            return R.ok(notFound);
        }
        return R.ok(state);
    }

    /**
     * 编排单步重试（#16 断点续跑）。
     *
     * <p>失败的编排不整条重来——从第一个失败步骤（或指定步骤）重跑后半段，
     * 已成功步骤的产出从工作区重建为上游上下文。</p>
     *
     * @param id  编排 ID
     * @param step 从第几步重跑（1 起；默认 0=自动找第一个非成功步骤）
     */
    @PostMapping("/orchestrate/{id}/retry")
    @RateLimit(name = "orchestrate-retry", key = RateLimit.KeyType.TENANT,
            capacity = 5, refillPerMin = 5,
            message = "重试请求过于频繁")
    public ResponseEntity<R<Map<String, Object>>> retryOrchestration(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int step) {
        OrchestrationState state = orchestrationService.getState(id);
        if (state == null) {
            return ResponseEntity.ok(R.fail(404, "编排不存在: " + id));
        }
        if (!"done".equals(state.getStatus()) && !"failed".equals(state.getStatus())) {
            return ResponseEntity.ok(R.fail(409, "仅已结束的编排可重试（当前: " + state.getStatus() + "）"));
        }
        // step=0 时自动找第一个非成功步骤
        int fromStep = step;
        if (fromStep <= 0) {
            fromStep = 1;
            for (OrchestrationState.StepResult sr : state.getSteps()) {
                if ("success".equals(sr.getStatus())) fromStep = sr.getIndex() + 1;
                else break;
            }
        }
        Long tenantId = TenantContext.get();
        orchestrationService.retryFromStep(id, fromStep, tenantId);
        auditService.log("orchestrate_retry", "case", state.getCaseId(),
                "{\"orchestrationId\":" + id + ",\"fromStep\":" + fromStep + "}");
        return ResponseEntity.accepted().body(R.ok(Map.of("orchestrationId", id, "fromStep", fromStep, "status", "pending")));
    }

    // ======================== 工作区文件浏览（产出落地） ========================

    /**
     * 列出 Case 工作区的文件树（编排产出落地的文件）。
     *
     * <p>编排完成后，AI 产出自动写入工作区目录并 Git commit。
     * 本接口返回文件列表，前端可查看已落地的代码文件。</p>
     */
    @GetMapping("/workspace/{caseId}/files")
    public R<Map<String, Object>> listWorkspaceFiles(@PathVariable String caseId) {
        if (!artifactFileService.exists(caseId)) {
            return R.ok(Map.of("exists", false, "files", List.of()));
        }
        var files = artifactFileService.listFiles(caseId);
        return R.ok(Map.of(
                "exists", true,
                "files", files,
                "fileCount", files.size(),
                "gitCommitted", gitService.isRemoteConfigured()
        ));
    }

    /**
     * 读取工作区中的单个文件内容。
     */
    @GetMapping("/workspace/{caseId}/read")
    public R<String> readWorkspaceFile(@PathVariable String caseId,
                                       @RequestParam String path) {
        if (!artifactFileService.exists(caseId)) {
            return R.fail(404, "工作区不存在: " + caseId);
        }
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get(
                    System.getProperty("user.dir"), "workspaces", caseId, path).normalize();
            // 路径安全检查
            java.nio.file.Path baseDir = java.nio.file.Paths.get(
                    System.getProperty("user.dir"), "workspaces", caseId).normalize();
            if (!filePath.startsWith(baseDir)) {
                return R.fail(403, "路径越权");
            }
            if (!java.nio.file.Files.exists(filePath)) {
                return R.fail(404, "文件不存在: " + path);
            }
            return R.ok(java.nio.file.Files.readString(filePath));
        } catch (Exception e) {
            return R.fail(500, "读取失败: " + e.getMessage());
        }
    }

    /**
     * 验证 Case 工作区的代码文件（核心价值闭环 #1）。
     *
     * <p>对 HTML/JS/Python/Java/CSS 做分层验证：
     * 优先用真实工具（node --check / python py_compile），降级为结构检查。
     * 返回逐文件的验证结果（通过/失败 + 具体原因）。</p>
     */
    @PostMapping("/workspace/{caseId}/validate")
    public R<CodeValidationService.ValidationResult> validateWorkspace(@PathVariable String caseId) {
        return R.ok(codeValidationService.validateWorkspace(caseId));
    }

    /**
     * 预览工作区 HTML 产出（产出在线预览 #3）。
     *
     * <p>返回 HTML 文件内容，Content-Type=text/html，
     * 前端直接 iframe 渲染——客户能"看一眼 AI 生成的网页长什么样"。</p>
     */
    @GetMapping(value = "/workspace/{caseId}/preview", produces = "text/html; charset=utf-8")
    public org.springframework.http.ResponseEntity<String> previewHtml(
            @PathVariable String caseId, @RequestParam String path) {
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get(
                    System.getProperty("user.dir"), "workspaces", caseId, path).normalize();
            java.nio.file.Path baseDir = java.nio.file.Paths.get(
                    System.getProperty("user.dir"), "workspaces", caseId).normalize();
            if (!filePath.startsWith(baseDir)) {
                return org.springframework.http.ResponseEntity.badRequest().body("路径越权");
            }
            if (!java.nio.file.Files.exists(filePath)) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            String content = java.nio.file.Files.readString(filePath);
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(content);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError()
                    .body("预览失败: " + e.getMessage());
        }
    }

    /** 转义 JSON 字符串（审计 detail 防注入）。 */
    private String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    @Data
    public static class OrchestrateRequest {
        /** 一句话需求（必填） */
        private String requirement;
        /** 关联 Case ID */
        private String caseId;
        /** 模式：fast（默认，6步）/ standard（预留） */
        private String tier;
    }
}
