package com.eaiselp.runtime.casestate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.service.CaseService;
import com.eaiselp.runtime.hierarchy.CaseDoneEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * {@link CaseStateService} 实现：基于 {@code CaseService}（MyBatis-Plus）读写 t_case。
 *
 * <p>多租户隔离：依赖 MyBatis-Plus 租户拦截器自动注入 tenant_id 过滤（ES-003 §9.3，G13），
 * 本实现不在 SQL 里手写 tenant_id 条件。
 *
 * <p>更新策略：用 LambdaUpdateWrapper 精准 SET status + update_by，避免全字段 update 覆盖并发写入。
 *
 * <p>上行汇总（PRJ-002 F8/T16，SE 决策 D-2）：流转成功且目标=done 且 Case 关联项目时发布
 * {@link CaseDoneEvent}，由 ProjectProgressListener 异步重算项目进度。本实现只依赖 Spring 事件
 * 发布器与 hierarchy 包的事件 POJO（数据类非服务），不 import 汇总服务——casestate 是 L1
 * 基础设施，不得感知 L2 汇总实现存在（P12/P3 单向依赖）。发布全程 try-catch：失败仅记 ERROR，
 * 绝不影响状态机（AC-F8.4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseStateServiceImpl implements CaseStateService {

    private final CaseService caseService;
    /** 上行汇总事件发布器（D-2：事件解耦，汇总失败不可能沿调用栈打断 transit） */
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Case transit(String caseId, CaseStatus target, String operator) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalStateTransitionException("caseId 不能为空");
        }
        if (target == null) {
            throw new IllegalStateTransitionException("target 状态不能为空");
        }
        Case c = findCase(caseId);
        CaseStatus current = CaseStatus.fromDbValue(c.getStatus());
        if (current == null) {
            throw new IllegalStateTransitionException(
                    "Case " + caseId + " 当前 status 值非法: " + c.getStatus());
        }
        // 幂等：目标 == 当前，直接返回，不报错（并发重试友好）
        if (current == target) {
            log.info("[CaseState] 幂等流转 caseId={}, status={} (已是目标态)", caseId, current.dbValue());
            c.setStatus(target.dbValue());
            return c;
        }
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(
                    "非法状态流转: " + current.dbValue() + " → " + target.dbValue()
                            + " (caseId=" + caseId + ")");
        }
        // 精准更新：仅 SET status + update_by，按 case_id 定位（tenant_id 由拦截器注入）
        boolean updated = caseService.update(new LambdaUpdateWrapper<Case>()
                .eq(Case::getCaseId, caseId)
                .set(Case::getStatus, target.dbValue())
                .set(Case::getUpdateBy, operator));
        if (!updated) {
            // 极端并发：刚好被删或租户隔离过滤掉。重查一次给出明确错误。
            throw new IllegalStateTransitionException(
                    "Case 状态更新失败（可能已被删除或租户隔离）: " + caseId);
        }
        log.info("[CaseState] 流转成功 caseId={} {} → {} by={}",
                caseId, current.dbValue(), target.dbValue(), operator);
        // ★ T16/F8 上行汇总：目标=done 且关联项目 → 发布 CaseDoneEvent（异步重算项目进度）。
        //   try-catch 包裹：发布失败只记 ERROR，不影响状态机（AC-F8.4 硬约束）
        if (target == CaseStatus.DONE && c.getProjectId() != null) {
            try {
                Long tenantId = c.getTenantId() != null ? c.getTenantId() : TenantContext.get();
                eventPublisher.publishEvent(new CaseDoneEvent(caseId, c.getProjectId(), tenantId));
                log.info("[CaseState] CaseDoneEvent 已发布 caseId={}, projectId={}, tenantId={}",
                        caseId, c.getProjectId(), tenantId);
            } catch (Exception e) {
                log.error("[CaseState] CaseDoneEvent 发布失败（不影响状态流转）caseId={}, projectId={}",
                        caseId, c.getProjectId(), e);
            }
        }
        c.setStatus(target.dbValue());
        c.setUpdateBy(operator);
        return c;
    }

    @Override
    public CaseStatus getCurrentStatus(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return null;
        }
        Case c = caseService.getOne(new LambdaQueryWrapper<Case>()
                .eq(Case::getCaseId, caseId)
                .select(Case::getStatus));
        if (c == null) {
            return null;
        }
        return CaseStatus.fromDbValue(c.getStatus());
    }

    private Case findCase(String caseId) {
        Case c = caseService.getOne(new LambdaQueryWrapper<Case>()
                .eq(Case::getCaseId, caseId));
        if (c == null) {
            throw new IllegalStateTransitionException("Case 不存在或租户隔离: " + caseId);
        }
        return c;
    }
}
