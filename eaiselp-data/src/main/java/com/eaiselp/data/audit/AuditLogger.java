package com.eaiselp.data.audit;

import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 审计日志异步写入器（M3-2）。
 *
 * <p>{@code @Async("runtimeAuditExecutor")} 委托给独立线程池（在 runtime/auth/admin 各模块配置），
 * 与业务线程池隔离，避免审计日志写入失败影响业务；写入失败只 {@code log.error}，不重抛
 * （reliability-governance §输出兜底：审计日志丢失只告警不阻断主流程）。
 *
 * <p><b>线程池选择</b>：bean 名 {@code runtimeAuditExecutor}，由各 service 模块（runtime/auth/admin）
 * 各自定义 {@code ThreadPoolTaskExecutor}。若运行时未配置该 bean，Spring 会回退到默认 SimpleAsyncTaskExecutor
 * （每次新建线程，开发期可接受，生产期建议显式配置）。
 *
 * <p><b>独立 Bean 而非合并到 AuditServiceImpl</b>：Spring {@code @Async} 代理要求方法所在类是被 Spring
 * 代理的独立 Bean，内部方法调用不走代理。分离 Bean 确保 {@code @Async} 生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogger {

    private final GovernanceLogMapper governanceLogMapper;

    /**
     * 异步写入审计日志到 t_governance_log。
     *
     * <p>失败只 log.error，不重抛（审计日志不应阻断业务流程）。
     */
    @Async("runtimeAuditExecutor")
    public void write(GovernanceLog logEntry) {
        try {
            governanceLogMapper.insert(logEntry);
        } catch (Exception e) {
            // 审计日志写入失败只告警，不影响主业务流程（reliability-governance 兜底）
            log.error("[Audit] 审计日志写入失败: action={}, resourceType={}, resourceId={}, userId={}",
                    logEntry.getAction(), logEntry.getResourceType(),
                    logEntry.getResourceId(), logEntry.getUserId(), e);
        }
    }
}
