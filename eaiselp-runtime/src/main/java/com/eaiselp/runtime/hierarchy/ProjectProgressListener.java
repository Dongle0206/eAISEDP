package com.eaiselp.runtime.hierarchy;

import com.eaiselp.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Case 完成事件监听器（PRJ-002 T14，SE 决策 D-2）。
 *
 * <p>消费 {@link CaseDoneEvent} → 异步重算项目进度。事件发布方（casestate.transit，批3 接线）
 * 的状态流转<b>永远</b>不被汇总失败影响：本监听器在独立线程池执行，且 catch Throwable 全吞
 * 只记 ERROR（AC-F8.4 硬约束）——即便发布方 try-catch 失效，异常也止步于异步边界之外。</p>
 *
 * <p><b>线程池复用评估结论（批2 任务书裁决）</b>：复用 {@code runtimeAuditExecutor}
 * （core=2/max=8/queue=200）而非新建 progressExecutor——汇总重算是低频毫秒级 DB 操作，
 * 与审计日志同量级；CallerRunsPolicy 兜底（队列满时同步执行）对本监听器无害：
 * 监听器自身 catch Throwable，同步执行也打不断已完成的 transit。避免线程池膨胀
 * （平台已有 runtimeLlm/orchestration/runtimeAudit 三池，四池边际收益为负）。</p>
 *
 * <p><b>租户上下文</b>：异步线程无 web/编排上下文，凭事件携带的 tenantId 切换，
 * finally 恢复原值（池化线程防 ThreadLocal 泄漏）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectProgressListener {

    private final ProjectProgressService progressService;

    @Async("runtimeAuditExecutor")
    @EventListener
    public void onCaseDone(CaseDoneEvent event) {
        Long prev = TenantContext.get();
        boolean switched = event.getTenantId() != null && !event.getTenantId().equals(prev);
        if (switched) {
            TenantContext.set(event.getTenantId());
        }
        try {
            progressService.recalculate(event.getProjectId());
        } catch (Throwable t) {
            // AC-F8.4：汇总失败只记 ERROR，不阻塞/不重抛（状态流转早已完成，进度待重算修复）
            log.error("[Progress] 汇总失败（不阻塞主流程，待重算修复）projectId={}, caseId={}, trigger={}",
                    event.getProjectId(), event.getCaseId(), event.getTrigger(), t);
        } finally {
            if (switched) {
                TenantContext.set(prev);
            }
        }
    }
}
