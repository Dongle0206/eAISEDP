package com.eaiselp.runtime.orchestration;

import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.runtime.context.DerivationContext;
import com.eaiselp.runtime.engine.DerivationEngine;
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

        // 初始化步骤列表
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

        String[][] pipeline = FAST_PIPELINE;
        for (int i = 0; i < pipeline.length; i++) {
            String[] step = pipeline[i];
            String role = step[0];
            String roleLabel = step[1];
            String artifactType = step[2];

            OrchestrationState.StepResult stepResult = state.getSteps().get(i);
            state.setCurrentRole(role);
            stepResult.setStatus("running");
            stepResult.setStartedAt(LocalDateTime.now());

            try {
                // 获取角色定义
                AgentDefinition agent = capabilityLoader.getAgent(role);
                if (agent == null) {
                    throw new RuntimeException("角色未注册: " + role);
                }

                // 构建上下文：把前面所有步骤的产出传给当前步骤
                DerivationContext ctx = DerivationContext.builder()
                        .task(state.getRequirement())
                        .stage(artifactType)
                        .upstreamArtifacts(upstreamArtifacts.isEmpty() ? null : new HashMap<>(upstreamArtifacts))
                        .extraInstructions("这是流水线编排的第 " + (i + 1) + " 步（共 " + pipeline.length
                                + " 步）。请基于需求和上游产出，产出你的专业内容。")
                        .build();

                log.info("[Orchestration] 步骤 {}/{} 派生: role={}, case={}", i + 1, pipeline.length, role, state.getCaseId());

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
                log.info("[Orchestration] 步骤 {}/{} 完成: role={}", i + 1, pipeline.length, role);

                // 步骤间间隔（防 LLM 429 限流）
                if (i < pipeline.length - 1 && stepIntervalMs > 0) {
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
                log.error("[Orchestration] 步骤 {}/{} 失败: role={}, error={}", i + 1, pipeline.length, role, errMsg, t);

                // 失败后也等一下（可能是因为 429 限流，给后续步骤恢复时间）
                try {
                    if (i < pipeline.length - 1 && stepIntervalMs > 0) {
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
    }

    /** 查询编排进度。 */
    public OrchestrationState getState(Long id) {
        return stateMap.get(id);
    }
}
