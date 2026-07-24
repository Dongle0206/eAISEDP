package com.eaiselp.runtime.task;

import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.runtime.context.DerivationContext;
import com.eaiselp.runtime.engine.DerivationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 派生异步执行器（M2-DFX，SE 技术方案 §4.1.3）。
 *
 * <p><b>独立 Bean 的原因（SE §4.1.3 关键约束）</b>：@Async 与 @Transactional 一样吃
 * <b>this 自调用失效</b>的亏——若异步方法写在本类内由 Controller 直接调（this），
 * Spring AOP 代理被绕过，@Async 不生效（变成同步）。因此抽到独立 Bean，
 * 由 Controller 注入后<b>跨 Bean 调用</b>（与 M1.2 的 DerivationPersistenceService 同构决策）。
 *
 * <p><b>多租户上下文跨线程传递（ES-003 §9.3 P11）</b>：TenantContext/LoginUser 是 ThreadLocal，
 * 不会自动传到异步线程。此处显式接收 tenantId 并在异步方法内 {@link TenantContext#set} 注入，
 * 确保 DerivationService 的 save/updateById（MyBatis-Plus 租户拦截器）注入正确 tenant_id。
 *
 * <p><b>失败兜底（SE §4.1.2 / D-6）</b>：engine.derive 抛 Throwable 时，
 * taskService.markFailed 显式补写 DB status=failed（engine.derive 内部落库容错不重抛，
 * 失败时 DB 不会自动标 failed）。此处 catch Throwable（含 Error）与 derive() 容错策略一致。
 *
 * <p><b>taskId 传递（D-4 解法）</b>：调 engine.derive 前 {@link DerivationTaskIdHolder#set}，
 * 指挥 DerivationPersistenceService.persist 走 updateById（而非新 INSERT），
 * 实现taskId 与结果行 id 统一。finally 必清防线程池线程复用串号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DerivationAsyncRunner {

    private final DerivationEngine engine;
    private final DerivationTaskService taskService;

    /**
     * 异步执行派生（由 runtimeLlmExecutor 线程池承接）。
     *
     * @param taskId     派生任务 id（= t_derivation.id，createPending 预占）
     * @param agent      角色定义
     * @param task       派生任务文本
     * @param caseId     案例 id（可空）
     * @param ctx        派生上下文
     * @param tenantId   租户 id（跨线程传递，注入 TenantContext 供 MP 租户拦截器）
     */
    @Async("runtimeLlmExecutor")
    public void deriveAsync(Long taskId, AgentDefinition agent, String task,
                            String caseId, DerivationContext ctx, Long tenantId) {
        // 异步方法本身不加 @Transactional（异步边界不持事务，SE §4.1.3）
        // 多租户：异步线程内注入 tenantId，使 MP 租户拦截器注入正确的 tenant_id
        TenantContext.set(tenantId);
        DerivationTaskIdHolder.set(taskId);
        try {
            taskService.markRunning(taskId);                 // pending→running
            DerivationEngine.DerivationResult r = engine.derive(agent, task, caseId, ctx);
            // engine.derive 内部 persist 已 UPDATE 写 success（经 DerivationTaskIdHolder）；此处同步内存态
            taskService.markSuccess(taskId, r);
        } catch (Throwable t) {                              // 含 Error，与 derive() 落库容错策略一致
            log.error("[AsyncDerive] 失败 taskId={}, role={}", taskId, agent.getName(), t);
            taskService.markFailed(taskId, t);
        } finally {
            // 必清：异步线程池线程会被复用，下一任务前必须清理，否则 holder/tenant 串号
            DerivationTaskIdHolder.clear();
            TenantContext.clear();
        }
    }
}
