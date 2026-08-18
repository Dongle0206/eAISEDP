package com.eaiselp.runtime.hierarchy;

/**
 * Case 完成事件（PRJ-002 F8 上行汇总触发器，SE 决策 D-2，T14）。
 *
 * <p>Spring 应用事件（普通 POJO 即可，Spring 4.2+）：由 Case 状态流转到 done 的位置发布
 * （批3 在 CaseStateServiceImpl.transit 尾部接线，T16），{@link ProjectProgressListener}
 * 异步消费重算项目进度。</p>
 *
 * <p><b>依赖方向</b>：本类是 hierarchy 与 casestate 两包解耦的桥——casestate 只 import
 * 本 POJO 与 ApplicationEventPublisher，不 import hierarchy 服务（P12/P3 单向依赖，
 * casestate 是 L1 基础设施，不得感知 L2 汇总服务存在）。</p>
 *
 * <p><b>为什么是事件而非同步调用</b>（SE §5.2 D-2）：transit 是状态机纯逻辑，若直接注入
 * ProjectProgressService，汇总抛错会沿调用栈打断状态流转（违反 AC-F8.4 失败不阻塞）；
 * 事件 + 异步监听天然隔离，且全量重算幂等（SE §5.3）保证事件晚到/重放安全，
 * 故不用 @TransactionalEventListener（transit 单条 update 自动提交，无外层事务）。</p>
 */
public class CaseDoneEvent {

    /** Case 业务键（t_case.case_id，VARCHAR） */
    private final String caseId;

    /** 所属项目 ID（t_case.project_id；发布方保证非空——未关联项目的 Case 不发布） */
    private final Long projectId;

    /** 租户 ID（异步监听线程无 TenantContext，消费侧凭此重建租户上下文） */
    private final Long tenantId;

    /** 触发类型：status_done（状态流转）/ link（挂接）/ unlink（解除/删除）等，留痕用 */
    private final String trigger;

    public CaseDoneEvent(String caseId, Long projectId, Long tenantId) {
        this(caseId, projectId, tenantId, "status_done");
    }

    public CaseDoneEvent(String caseId, Long projectId, Long tenantId, String trigger) {
        this.caseId = caseId;
        this.projectId = projectId;
        this.tenantId = tenantId;
        this.trigger = trigger == null || trigger.isBlank() ? "status_done" : trigger;
    }

    public String getCaseId() {
        return caseId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getTrigger() {
        return trigger;
    }
}
