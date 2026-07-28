package com.eaiselp.data.audit;

/**
 * 审计日志服务（M3-2，GRC 治理：操作可追溯 who/when/what）。
 *
 * <p>所有关键操作（登录、Case 创建/流转、检查点确认/拒绝、派生、用户管理）调用本服务记录审计日志。
 *
 * <p><b>异步写入</b>：本服务的方法内部委托给 {@link AuditLogger}（{@code @Async} 独立线程池），
 * 不阻塞主流程；写入失败只 {@code log.error}，不影响业务（reliability-governance 兜底）。
 *
 * <p><b>上下文来源</b>：
 * <ul>
 *   <li>{@code userId/username/tenantId} 从 {@code LoginUser}（JWT claims）取，防客户端伪造（ES-003 §9.3 G13）。</li>
 *   <li>{@code ipAddress} 从 {@code RequestContextHolder} 取当前请求 IP。</li>
 * </ul>
 *
 * <p><b>放置位置</b>：放在 data 模块而非 common，因为要注入 {@code GovernanceLogMapper}（common 不依赖 data，
 * 避免反向依赖违反 ADR-001 P3）。data 模块是 library，被 runtime/auth/admin 共同依赖，
 * 各 service 模块 Controller 可直接注入本接口（同进程 bean 注入，ES-001 §4.4）。
 */
public interface AuditService {

    /**
     * 记录审计日志（成功，无 detail）。
     *
     * @param action       操作动作（如 login_success / case_create）
     * @param resourceType 资源类型（如 case / user）
     * @param resourceId   资源标识（如 caseId / userId，可为 null）
     */
    default void log(String action, String resourceType, String resourceId) {
        log(action, resourceType, resourceId, null, "success", null);
    }

    /**
     * 记录审计日志（成功，带 detail）。
     *
     * @param action       操作动作
     * @param resourceType 资源类型
     * @param resourceId   资源标识
     * @param detail       详情（before/after 快照或扩展上下文，可为 null）
     */
    default void log(String action, String resourceType, String resourceId, String detail) {
        log(action, resourceType, resourceId, detail, "success", null);
    }

    /**
     * 记录审计日志（自定义 result）。
     *
     * @param action       操作动作
     * @param resourceType 资源类型
     * @param resourceId   资源标识
     * @param detail       详情（可为 null）
     * @param result       结果：success / failure
     */
    default void log(String action, String resourceType, String resourceId, String detail, String result) {
        log(action, resourceType, resourceId, detail, result, null);
    }

    /**
     * 记录审计日志（全字段）。
     *
     * @param action       操作动作
     * @param resourceType 资源类型
     * @param resourceId   资源标识
     * @param detail       详情 JSON 文本（可为 null）
     * @param result       结果：success / failure
     * @param errorMsg     失败时的错误信息（result=failure 时填，可为 null）
     */
    void log(String action, String resourceType, String resourceId, String detail, String result, String errorMsg);
}
