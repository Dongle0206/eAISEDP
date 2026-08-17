package com.eaiselp.runtime.orchestration;

import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.adapter.spi.LlmAdapter;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.runtime.context.DerivationContext;
import com.eaiselp.runtime.engine.DerivationEngine;
import com.eaiselp.runtime.workspace.ArtifactFileService;
import com.eaiselp.runtime.workspace.CICDTriggerService;
import com.eaiselp.runtime.workspace.CodeValidationService;
import com.eaiselp.runtime.workspace.GitService;
import com.eaiselp.data.entity.Checkpoint;
import com.eaiselp.data.service.CheckpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 编排服务 —— 一句话需求自动按流水线派生所有角色。
 *
 * <p><b>核心价值</b>：从"手动逐个角色派生"升级为"一句话需求 → 自动全流程编排"。
 * 用户只需输入一句话需求，平台自动按 PO→SE→Dev→Reviewer→QA→Ops 流水线串行派生，
 * 每步把前面步骤的产出通过 {@link DerivationContext#getUpstreamArtifacts()} 传给下一步。</p>
 *
 * <p><b>流水线定义</b>：Fast 模式 6 步，覆盖需求→方案→编码→审查→测试→部署核心链路。
 * 步骤间有数据依赖（Dev 需要 PO 的 PRD + SE 的技术方案），故只能串行不能并行。</p>
 *
 * <p><b>上下文传递</b>：复用已有的 {@code DerivationContext.upstreamArtifacts} 管道
 * （{@code ContextAssembler} 已实现"## 上游产出（必读）"渲染），每步产出填入 Map 供下一步使用。</p>
 *
 * <p><b>限流防护</b>：步骤间间隔 {@code step-interval-ms}（默认 3 秒），防 LLM provider 429 限流。
 * 某步失败不中断整条流水线（降级策略：记录失败，继续下一步）。</p>
 *
 * <p><b>线程隔离</b>：编排走独立线程池 {@code orchestrationExecutor}（core=2/max=3），
 * 不占用单角色派生的 {@code runtimeLlmExecutor} 线程池。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationService {

    private final DerivationEngine engine;
    private final CapabilityLoader capabilityLoader;
    private final AdapterFactory adapterFactory;
    private final ArtifactFileService artifactFileService;
    private final GitService gitService;
    private final CICDTriggerService cicdTriggerService;
    private final CodeValidationService codeValidationService;
    private final CheckpointService checkpointService;

    /** 不可逆操作（部署）前的检查点等待超时：30 分钟 */
    @Value("${eaiselp.orchestration.approval-timeout-ms:1800000}")
    private long approvalTimeoutMs;

    @Value("${eaiselp.orchestration.step-interval-ms:3000}")
    private long stepIntervalMs;

    /** 编排任务内存态（重启丢失，M2 dogfooding 可接受） */
    private final ConcurrentHashMap<Long, OrchestrationState> stateMap = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    /** Fast 模式流水线（6 步） */
    private static final String[][] FAST_PIPELINE = {
            // {role, roleLabel, artifactType}
            {"team-po",       "产品经理(PO)",   "prd"},
            {"team-se",       "系统工程(SE)",    "tech-design"},
            {"team-dev",      "开发(Dev)",      "code"},
            {"team-reviewer", "代码审查",        "review"},
            {"team-qa",       "测试(QA)",       "test"},
            {"team-ops",      "运维(Ops)",      "deploy"},
    };

    /**
     * 创建编排任务（同步返回 ID，异步执行流水线）。
     *
     * @param requirement 一句话需求
     * @param caseId      关联 Case
     * @param tier        模式（目前只支持 fast）
     * @return 编排任务 ID
     */
    public Long start(String requirement, String caseId, String tier) {
        Long id = idGenerator.getAndIncrement();
        OrchestrationState state = new OrchestrationState();
        state.setId(id);
        state.setCaseId(caseId);
        state.setRequirement(requirement);
        state.setTier(tier != null ? tier : "fast");
        state.setStatus("pending");
        state.setCreatedAt(LocalDateTime.now());

        // 初始化步骤列表（默认 fast 预填；runAsync 时 LLM 智能规划可能替换）
        String[][] pipeline = FAST_PIPELINE;
        for (int i = 0; i < pipeline.length; i++) {
            state.getSteps().add(OrchestrationState.StepResult.pending(
                    i + 1, pipeline[i][0], pipeline[i][1], pipeline[i][2]));
        }

        stateMap.put(id, state);
        log.info("[Orchestration] 编排任务创建 id={}, caseId={}, steps={}", id, caseId, pipeline.length);
        return id;
    }

    /**
     * 异步执行编排流水线（在一个异步线程内串行循环调 engine.derive）。
     *
     * <p>每步把前面所有步骤的产出填入 DerivationContext.upstreamArtifacts，
     * ContextAssembler 会渲染成"## 上游产出（必读）"注入 prompt。</p>
     */
    @Async("orchestrationExecutor")
    public void runAsync(Long id, Long tenantId) {
        OrchestrationState state = stateMap.get(id);
        if (state == null) {
            log.error("[Orchestration] 编排任务不存在 id={}", id);
            return;
        }

        TenantContext.set(tenantId);
        state.setStatus("running");
        Map<String, String> upstreamArtifacts = new HashMap<>();

        log.info("[Orchestration] 开始执行编排 id={}, requirement={}", id, state.getRequirement());

        // ★ 智能规划（#2 编排智能化）：用 team-orchestrator LLM 分析需求动态生成流水线
        // 规划失败降级为默认 fast 流水线（start 时已预填）
        boolean planned = planPipelineWithLlm(state);
        List<OrchestrationState.StepResult> steps = state.getSteps();
        int total = steps.size();
        if (planned) {
            log.info("[Orchestration] 智能规划成功 id={}, 步骤数={}", id, total);
        } else {
            log.info("[Orchestration] 智能规划降级为默认 fast 流水线 id={}", id);
        }

        for (int i = 0; i < total; i++) {
            OrchestrationState.StepResult stepResult = steps.get(i);
            String role = stepResult.getRole();
            String roleLabel = stepResult.getRoleLabel();
            String artifactType = stepResult.getArtifactType();
            state.setCurrentRole(role);
            stepResult.setStatus("running");
            stepResult.setStartedAt(LocalDateTime.now());

            try {
                // ★ 检查点人工锁（可靠性治理：不可逆操作前必须人工确认）
                // 部署类步骤（team-ops）执行前暂停，等待检查点审批
                if ("team-ops".equals(role)) {
                    ApprovalDecision decision = awaitApproval(state, stepResult);
                    if (decision == ApprovalDecision.REJECTED || decision == ApprovalDecision.TIMEOUT) {
                        // 拒绝/超时 → 跳过部署步骤，编排继续收尾（其他步骤成果保留）
                        stepResult.setStatus("skipped");
                        stepResult.setError(decision == ApprovalDecision.REJECTED
                                ? "检查点被拒绝，跳过部署" : "审批超时（30分钟），跳过部署");
                        stepResult.setFinishedAt(LocalDateTime.now());
                        state.setCurrentRole(null);
                        log.warn("[Orchestration] 部署步骤被跳过 id={}, 原因={}", id, decision);
                        continue; // 跳过本步骤（team-ops 是最后一步，直接进收尾）
                    }
                    // approved → 继续执行部署
                }

                // 获取角色定义
                AgentDefinition agent = capabilityLoader.getAgent(role);
                if (agent == null) {
                    throw new RuntimeException("角色未注册: " + role);
                }

                // 构建上下文：把前面所有步骤的产出传给当前步骤
                // 指令优先用编排者 LLM 规划的定制指令（智能规划），无则用默认文案
                String instruction = (stepResult.getInstruction() != null && !stepResult.getInstruction().isBlank())
                        ? stepResult.getInstruction()
                        : "这是流水线编排的第 " + (i + 1) + " 步（共 " + total + " 步）。请基于需求和上游产出，产出你的专业内容。";
                DerivationContext ctx = DerivationContext.builder()
                        .task(state.getRequirement())
                        .stage(artifactType)
                        .upstreamArtifacts(upstreamArtifacts.isEmpty() ? null : new HashMap<>(upstreamArtifacts))
                        .extraInstructions(instruction)
                        .build();

                log.info("[Orchestration] 步骤 {}/{} 派生: role={}, case={}", i + 1, total, role, state.getCaseId());

                // 同步调用 engine.derive（在异步线程内阻塞等待 LLM 返回）
                DerivationEngine.DerivationResult result = engine.derive(agent, state.getRequirement(), state.getCaseId(), ctx);

                // 把这一步的产出存入 upstreamArtifacts 供后续步骤使用
                if (result != null && result.getOutput() != null) {
                    // 截断到 4000 字符（与 ContextAssembler 的 upstreamArtifacts 渲染限制对齐）
                    String output = result.getOutput();
                    if (output.length() > 4000) {
                        output = output.substring(0, 4000) + "\n... (已截断)";
                    }
                    upstreamArtifacts.put(roleLabel + "的" + artifactType, output);
                }

                stepResult.setStatus("success");
                stepResult.setFinishedAt(LocalDateTime.now());
                log.info("[Orchestration] 步骤 {}/{} 完成: role={}", i + 1, total, role);

                // ★ 产出落地：把 LLM 产出写入工作区文件系统
                if (result != null && result.getOutput() != null) {
                    try {
                        var written = artifactFileService.writeToWorkspace(
                                state.getCaseId(), role, result.getOutput());
                        log.info("[Orchestration] 产出落地: role={}, 文件数={}", role, written.size());
                    } catch (Exception fe) {
                        log.warn("[Orchestration] 产出落地失败（不阻塞流程）: role={}", role, fe);
                    }
                }

                // 步骤间间隔（防 LLM 429 限流）
                if (i < total - 1 && stepIntervalMs > 0) {
                    Thread.sleep(stepIntervalMs);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stepResult.setStatus("failed");
                stepResult.setError("编排被中断");
                stepResult.setFinishedAt(LocalDateTime.now());
                state.setStatus("failed");
                log.error("[Orchestration] 编排被中断 id={}", id);
                break;
            } catch (Throwable t) {
                // 降级策略：某步失败不中断整条流水线，记录失败继续下一步
                String errMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                stepResult.setStatus("failed");
                stepResult.setError(errMsg);
                stepResult.setFinishedAt(LocalDateTime.now());
                log.error("[Orchestration] 步骤 {}/{} 失败: role={}, error={}", i + 1, total, role, errMsg, t);

                // 失败后也等一下（可能是因为 429 限流，给后续步骤恢复时间）
                try {
                    if (i < total - 1 && stepIntervalMs > 0) {
                        Thread.sleep(stepIntervalMs);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        state.setStatus("done");
        state.setCurrentRole(null);
        state.setFinishedAt(LocalDateTime.now());
        TenantContext.clear();

        int success = (int) state.getSteps().stream().filter(s -> "success".equals(s.getStatus())).count();
        int failed = (int) state.getSteps().stream().filter(s -> "failed".equals(s.getStatus())).count();
        log.info("[Orchestration] 编排完成 id={}, 成功={}, 失败={}", id, success, failed);

        // ★ Git 落地：编排完成后，把工作区 commit + push
        if (success > 0) {
            try {
                // ★ 产出验证（核心价值闭环：AI 产出必须验证，结果记录到编排状态供前端展示）
                try {
                    var vr = codeValidationService.validateWorkspace(state.getCaseId());
                    OrchestrationState.CodeValidationSummary summary = new OrchestrationState.CodeValidationSummary();
                    summary.setAllPassed(vr.isAllPassed());
                    summary.setTotalFiles(vr.getTotalFiles());
                    summary.setPassedFiles(vr.getPassedFiles());
                    summary.setFailedFiles(vr.getFailedFiles());
                    summary.setValidatedAt(vr.getValidatedAt());
                    state.setValidation(summary);
                    log.info("[Orchestration] 产出验证: {}/{} 通过, 全部通过={}",
                            vr.getPassedFiles(), vr.getTotalFiles(), vr.isAllPassed());
                } catch (Exception ve) {
                    log.warn("[Orchestration] 产出验证失败（不阻塞）", ve);
                }

                String commitMsg = "eAISEDP 编排产出: " + state.getCaseId()
                        + " (成功" + success + "/" + state.totalSteps() + ")";
                String commitHash = gitService.commitWorkspace(state.getCaseId(), commitMsg);
                if (commitHash != null) {
                    log.info("[Orchestration] Git commit 成功: {} → {}", state.getCaseId(), commitHash.substring(0, 8));
                    // 远程推送（配置了远程地址才 push）
                    gitService.pushWorkspace(state.getCaseId());
                    // CI/CD 触发（配置了 Webhook URL 才触发，适配 Jenkins/GitLab/GitHub/Gitea 等）
                    var files = artifactFileService.listFiles(state.getCaseId());
                    cicdTriggerService.triggerBuild(state.getCaseId(), commitHash, files, state.getRequirement());
                }
            } catch (Exception ge) {
                log.warn("[Orchestration] Git 落地失败（不阻塞流程）", ge);
            }
        }
    }

    /** 查询编排进度。 */
    public OrchestrationState getState(Long id) {
        return stateMap.get(id);
    }

    /** JSON 解析器（LLM 规划结果解析）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 智能规划流水线（#2 编排智能化）。
     *
     * <p>用 team-orchestrator 角色的 LLM 分析需求，动态生成流水线：
     * 选哪些角色、什么顺序、每步定制指令。小需求少步骤省 token，大需求全流程。</p>
     *
     * <p><b>降级策略</b>：规划失败（LLM 异常/JSON 解析失败/角色不存在/步骤数越界）
     * 一律保留 start() 预填的默认 fast 流水线，编排不中断。</p>
     *
     * @return true=规划成功并替换了步骤列表 / false=降级保留默认
     */
    boolean planPipelineWithLlm(OrchestrationState state) {
        try {
            AgentDefinition orchestrator = capabilityLoader.getAgent("team-orchestrator");
            if (orchestrator == null) {
                log.warn("[Orchestration] team-orchestrator 角色未注册，降级默认流水线");
                return false;
            }

            // 用编排者角色的模型档位调 LLM（prompt 定义在 agents/team-orchestrator.md）
            String tier = orchestrator.getModel() != null ? orchestrator.getModel() : "sonnet";
            String model = adapterFactory.resolveModel(tier);
            LlmAdapter llm = adapterFactory.getLlmAdapter(tier);
            String prompt = orchestrator.getPrompt() + "\n\n---\n## 待规划需求\n" + state.getRequirement();
            LlmAdapter.LlmResponse resp = llm.invoke(model, prompt,
                    LlmAdapter.LlmOptions.builder().timeoutMs(60000L).build());

            // 解析 JSON（清洗可能的 markdown 代码块包裹）
            String content = cleanJson(resp.getContent());
            com.fasterxml.jackson.databind.JsonNode root = OM.readTree(content);
            com.fasterxml.jackson.databind.JsonNode stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray() || stepsNode.size() < 1) {
                log.warn("[Orchestration] LLM 规划 steps 为空，降级");
                return false;
            }
            if (stepsNode.size() > 8) {
                log.warn("[Orchestration] LLM 规划步骤数 {} 超上限 8，降级", stepsNode.size());
                return false;
            }

            // 逐项校验角色存在，构建动态步骤列表
            List<OrchestrationState.StepResult> planned = new java.util.ArrayList<>();
            for (int i = 0; i < stepsNode.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode s = stepsNode.get(i);
                String role = s.path("role").asText(null);
                String artifactType = s.path("artifactType").asText("other");
                String instruction = s.path("instruction").asText(null);
                if (role == null || capabilityLoader.getAgent(role) == null) {
                    log.warn("[Orchestration] LLM 规划的角色未注册: {}，降级", role);
                    return false;
                }
                String label = OrchestrationState.StepResult.ROLE_LABELS.getOrDefault(role, role);
                OrchestrationState.StepResult sr = OrchestrationState.StepResult.pending(
                        i + 1, role, label, artifactType);
                sr.setInstruction(instruction);
                planned.add(sr);
            }

            // 校验通过 → 替换步骤列表 + 更新档位
            state.setSteps(planned);
            String plannedTier = root.path("tier").asText("standard");
            state.setTier(plannedTier);
            log.info("[Orchestration] 智能流水线: tier={}, steps={}",
                    plannedTier, planned.stream().map(s -> s.getRole() + "(" + s.getArtifactType() + ")").toList());
            return true;

        } catch (Exception e) {
            log.warn("[Orchestration] 智能规划异常，降级默认流水线: {}", e.getMessage());
            return false;
        }
    }

    /** 清洗 LLM 返回的 JSON（去掉 markdown 代码块包裹和前后杂文）。 */
    private String cleanJson(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 去 ```json ... ``` 包裹
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) s = s.substring(first + 1, last).trim();
        }
        // 截取第一个 { 到最后一个 }（防前后杂文）
        int l = s.indexOf('{'), r = s.lastIndexOf('}');
        if (l >= 0 && r > l) s = s.substring(l, r + 1);
        return s;
    }

    /** 审批决定。 */
    enum ApprovalDecision { APPROVED, REJECTED, TIMEOUT }

    /**
     * 创建检查点并等待人工审批（可靠性治理：不可逆操作人工锁）。
     *
     * <p>编排状态转为 {@code awaiting_approval}，前端轮询可见并提示去检查点审批页操作。
     * 每 5 秒轮询检查点状态，最长等 {@code approval-timeout-ms}（默认 30 分钟）。</p>
     *
     * <p><b>实现权衡</b>：当前用线程阻塞等待（占编排线程），MVP 可接受
     * （orchestrationExecutor core=2）。生产版应改为"暂停/恢复"模式
     * （编排状态落库 + 审批回调触发继续），配合编排持久化一起做。</p>
     */
    private ApprovalDecision awaitApproval(OrchestrationState state, OrchestrationState.StepResult stepResult) {
        try {
            // 1. 创建 pending 检查点
            Checkpoint cp = checkpointService.create(
                    state.getCaseId(), "orchestration_deploy_" + state.getId(), null);

            // 2. 编排状态 → awaiting_approval（前端可见）
            state.setStatus("awaiting_approval");
            state.setPendingCheckpointId(cp.getId());
            state.setApprovalMessage("部署为不可逆操作，等待人工审批（检查点 #" + cp.getId()
                    + "）。请到「检查点审批」页面确认或拒绝。30 分钟无审批自动跳过。");
            log.info("[Orchestration] 等待部署审批 id={}, checkpointId={}", state.getId(), cp.getId());

            // 3. 轮询等待（5 秒一次）
            long deadline = System.currentTimeMillis() + approvalTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5000);
                Checkpoint cur = checkpointService.getById(cp.getId());
                if (cur == null) continue;
                String st = cur.getStatus();
                if (CheckpointService.STATUS_CONFIRMED.equals(st)) {
                    // 审批通过 → 恢复运行
                    state.setStatus("running");
                    state.setPendingCheckpointId(null);
                    state.setApprovalMessage(null);
                    log.info("[Orchestration] 部署审批通过 id={}", state.getId());
                    return ApprovalDecision.APPROVED;
                }
                if (CheckpointService.STATUS_REJECTED.equals(st)) {
                    state.setPendingCheckpointId(null);
                    state.setApprovalMessage(null);
                    log.info("[Orchestration] 部署审批被拒 id={}", state.getId());
                    return ApprovalDecision.REJECTED;
                }
                // 仍 pending → 继续等
            }
            // 超时
            state.setPendingCheckpointId(null);
            state.setApprovalMessage(null);
            log.warn("[Orchestration] 部署审批超时 id={}", state.getId());
            return ApprovalDecision.TIMEOUT;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.setPendingCheckpointId(null);
            state.setApprovalMessage(null);
            return ApprovalDecision.TIMEOUT;
        } catch (Exception e) {
            // 检查点服务异常：降级放行（不能因审批基础设施故障卡死编排）并 log 告警
            log.error("[Orchestration] 检查点服务异常，降级放行部署 id={}", state.getId(), e);
            state.setStatus("running");
            state.setPendingCheckpointId(null);
            state.setApprovalMessage(null);
            return ApprovalDecision.APPROVED;
        }
    }
}
