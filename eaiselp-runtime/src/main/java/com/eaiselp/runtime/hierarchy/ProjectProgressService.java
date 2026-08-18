package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.mapper.CaseMapper;
import com.eaiselp.runtime.casestate.CaseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 项目进度汇总服务（PRJ-002 T15，核心机制②，SE §5.3）。
 *
 * <p><b>算法：全量重算（幂等）而非增量加减</b>——两条标准 count 读 DB 当前真值 + 一条
 * LambdaUpdateWrapper set 三列写回。事件重复/并发交错时"最后写胜出=正确值"（R6），
 * 读-算-写 &lt;10ms 窗口内他事务提交的毫秒级误差可接受（事件低频），P1 预留手动重算入口兜底。</p>
 *
 * <p><b>SQL 形态规约（SE §11 R4，禁子查询 UPDATE）</b>：MyBatis-Plus 租户拦截器会改写全部
 * 业务表 SQL，子查询/复杂 UPDATE 是解析重灾区——本服务刻意写成拦截器友好的
 * "两条标准 count + 一条 LambdaUpdateWrapper 显式 set"形态，禁止任何原子 UPDATE 子查询优化。</p>
 *
 * <p><b>进度口径（AC-F8.1/8.2/8.3 三处一致）</b>：progress = ⌊done×100/total⌋，total=0 → 0。
 * Project 实体三列 updateStrategy=NEVER 只挡实体驱动的 updateById，本服务的显式 set 不受
 * 约束（这是汇总服务唯一合法写入路径，AC-F3.2 双保险的另一半）。</p>
 *
 * <p><b>租户上下文</b>：查询/更新均走拦截器（tenant_id 自动注入），调用方必须处于目标租户
 * TenantContext 下（编排/web 线程天然满足；异步入口 {@link #recalculateAsync} 自行切换）。
 * 项目群/战略层聚合不落库（PRD §7.11：展示层实时聚合）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectProgressService {

    private final CaseMapper caseMapper;
    private final ProjectMapper projectMapper;

    /**
     * 单项目进度全量重算（幂等，可安全重复调用）。
     *
     * <p>count 自动过滤 is_deleted（@TableLogic），逻辑删的 Case 不计入分子分母（AC-F8.3）。</p>
     *
     * @param projectId 项目 ID（须处于该项目的租户上下文中调用）
     */
    public void recalculate(Long projectId) {
        if (projectId == null) {
            return;
        }
        // 两条标准 count（禁子查询，R4）：total = 项目下全部未删 Case，done = 其中 done 态
        long total = caseMapper.selectCount(new LambdaQueryWrapper<Case>()
                .eq(Case::getProjectId, projectId));
        long done = caseMapper.selectCount(new LambdaQueryWrapper<Case>()
                .eq(Case::getProjectId, projectId)
                .eq(Case::getStatus, CaseStatus.DONE.dbValue()));
        int progress = total == 0 ? 0 : (int) Math.floor(done * 100.0 / total);
        // 一条 LambdaUpdateWrapper set 三列：拦截器自动加 tenant_id；显式 set 不受实体
        // updateStrategy=NEVER 约束（汇总是进度三列唯一合法写入方）
        projectMapper.update(null, new LambdaUpdateWrapper<Project>()
                .eq(Project::getId, projectId)
                .set(Project::getCaseTotal, (int) total)
                .set(Project::getCaseDone, (int) done)
                .set(Project::getProgress, progress));
        log.info("[Progress] 重算 projectId={}, total={}, done={}, progress={}",
                projectId, total, done, progress);
    }

    /**
     * 异步重算入口（供挂接/解除/删除等同步路径调用，批3 T17 接线）。
     *
     * <p>复用 {@code runtimeAuditExecutor} 线程池（core=2/max=8/queue=200，CallerRunsPolicy）
     * 而非新建 progressExecutor——评估结论（批2 任务书裁决，倾向复用避免线程池膨胀）：
     * <ul>
     *   <li>汇总重算是低频毫秒级 DB 操作，与审计日志同量级，无隔离的刚性需求
     *       （不像 LLM/编排需要防互相饿死）；</li>
     *   <li>CallerRunsPolicy 兜底语义可接受：队列满时退化为提交线程同步执行，
     *       本方法 catch Throwable 全吞（AC-F8.4），同步执行也绝不会打断调用方事务语义；</li>
     *   <li>省一个 core=1/max=2 的常驻线程池（SE §3.6 原方案），运行期线程数不膨胀。</li>
     * </ul>
     * 租户上下文在此切换并在 finally 恢复（异步线程是池化复用的，防 ThreadLocal 泄漏）。</p>
     *
     * @param projectId 项目 ID
     * @param tenantId  项目所属租户（异步线程无上下文，显式传入）
     */
    @Async("runtimeAuditExecutor")
    public void recalculateAsync(Long projectId, Long tenantId) {
        Long prev = TenantContext.get();
        boolean switched = tenantId != null && !tenantId.equals(prev);
        if (switched) {
            TenantContext.set(tenantId);
        }
        try {
            recalculate(projectId);
        } catch (Throwable t) {
            // AC-F8.4：汇总失败绝不阻塞调用方，只记 ERROR 待手动重算修复
            log.error("[Progress] 异步重算失败（不阻塞主流程，待重算修复）projectId={}, tenantId={}",
                    projectId, tenantId, t);
        } finally {
            if (switched) {
                TenantContext.set(prev);
            }
        }
    }
}
