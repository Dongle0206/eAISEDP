package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Checkpoint;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检查点服务接口（MyBatis-Plus IService 模式，对齐 {@link DerivationService} 风格）。
 *
 * <p>承载 GRC 第一道防线「不可逆操作人工锁」（reliability-governance）：
 * 不可逆操作执行前必须先创建 pending 检查点，人工 confirm 后才能继续；reject 则阻断。
 *
 * <p>状态语义（t_checkpoint.status）：
 * <ul>
 *   <li>{@code pending} — 已创建，等待人工确认（默认值）。</li>
 *   <li>{@code confirmed} — 已确认，操作可继续执行。</li>
 *   <li>{@code rejected} — 已拒绝，操作被阻断。</li>
 * </ul>
 *
 * <p>多租户隔离：依赖 MyBatis-Plus 租户拦截器自动注入 tenant_id（ES-003 §9.3，G13）。
 */
public interface CheckpointService extends IService<Checkpoint> {

    /** 检查点状态常量（与 t_checkpoint.status 列存值一致）。 */
    String STATUS_PENDING = "pending";
    String STATUS_CONFIRMED = "confirmed";
    String STATUS_REJECTED = "rejected";

    /**
     * 创建 pending 检查点（不可逆操作前调用）。
     *
     * @param caseId       Case 业务 ID
     * @param operation    操作标识（如 deploy_production / ddl_change，对齐 IRREVERSIBLE_OPS）
     * @param derivationId 关联派生记录 ID（追溯来源），可为 null
     * @return 已创建的 pending 检查点
     */
    default Checkpoint create(String caseId, String operation, Long derivationId) {
        Checkpoint cp = new Checkpoint();
        cp.setCaseId(caseId);
        cp.setOperation(operation);
        cp.setDerivationId(derivationId);
        cp.setStatus(STATUS_PENDING);
        cp.setRequestedAt(LocalDateTime.now());
        this.save(cp);
        return cp;
    }

    /**
     * 确认检查点（status → confirmed + confirmed_at + confirmed_by + comment）。
     *
     * <p>仅 pending 状态可确认；非 pending 返回 false（上层据此返回业务错误，不在此抛异常）。
     *
     * @param checkpointId 检查点主键 ID
     * @param confirmedBy  确认人标识
     * @param comment      备注（可为 null）
     * @return 确认成功返回 true；检查点不存在或非 pending 返回 false
     */
    boolean confirm(Long checkpointId, String confirmedBy, String comment);

    /**
     * 拒绝检查点（status → rejected + confirmed_at + confirmed_by + comment）。
     *
     * @param checkpointId 检查点主键 ID
     * @param confirmedBy  拒绝人标识
     * @param comment      拒绝理由（建议必填，但本层不强制）
     * @return 拒绝成功返回 true；检查点不存在或非 pending 返回 false
     */
    boolean reject(Long checkpointId, String confirmedBy, String comment);

    /**
     * 按 caseId 查检查点列表（按请求时间倒序）。
     */
    default List<Checkpoint> listByCaseId(String caseId) {
        return this.list(new LambdaQueryWrapper<Checkpoint>()
                .eq(Checkpoint::getCaseId, caseId)
                .orderByDesc(Checkpoint::getRequestedAt));
    }

    /**
     * 按 caseId 查待确认（pending）的检查点列表。
     */
    default List<Checkpoint> findPendingByCaseId(String caseId) {
        return this.list(new LambdaQueryWrapper<Checkpoint>()
                .eq(Checkpoint::getCaseId, caseId)
                .eq(Checkpoint::getStatus, STATUS_PENDING)
                .orderByDesc(Checkpoint::getRequestedAt));
    }
}
