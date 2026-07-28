package com.eaiselp.runtime.casestate;

import com.eaiselp.data.entity.Case;

/**
 * Case 状态机服务（M2 SP-3 轻量级方案）。
 *
 * <p>封装 Case 状态流转的核心校验逻辑：基于 {@link CaseStatus#canTransitionTo(CaseStatus)}
 * 判定合法性，校验通过才更新 t_case.status；非法流转抛 {@link IllegalStateTransitionException}。
 *
 * <p>不做事务编排外的副作用（如发事件/写审计），保持单一职责。多租户隔离由 MyBatis-Plus
 * 租户拦截器自动注入 tenant_id（ES-003 §9.3，G13），本服务不感知 tenant_id。
 */
public interface CaseStateService {

    /**
     * Case 状态流转：校验 {@code canTransitionTo} 合法后更新 t_case.status。
     *
     * <p>幂等：目标状态 == 当前状态时不报错，直接返回当前 Case（并发重试友好）。
     *
     * @param caseId   Case 业务 ID（t_case.case_id，非主键 id）
     * @param target   目标状态枚举
     * @param operator 操作人标识（用于审计字段 update_by，可为 null）
     * @return 流转后的 Case（最新状态）
     * @throws IllegalStateTransitionException 当前状态不存在或流转路径非法
     */
    Case transit(String caseId, CaseStatus target, String operator);

    /**
     * 查询 Case 当前状态枚举。
     *
     * @param caseId Case 业务 ID
     * @return 当前状态枚举；Case 不存在或 status 值非法返回 null
     */
    CaseStatus getCurrentStatus(String caseId);
}
