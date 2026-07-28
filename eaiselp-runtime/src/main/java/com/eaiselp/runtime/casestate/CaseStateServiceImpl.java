package com.eaiselp.runtime.casestate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.service.CaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link CaseStateService} 实现：基于 {@link CaseService}（MyBatis-Plus）读写 t_case。
 *
 * <p>多租户隔离：依赖 MyBatis-Plus 租户拦截器自动注入 tenant_id 过滤（ES-003 §9.3，G13），
 * 本实现不在 SQL 里手写 tenant_id 条件。
 *
 * <p>更新策略：用 LambdaUpdateWrapper 精准 SET status + update_by，避免全字段 update 覆盖并发写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseStateServiceImpl implements CaseStateService {

    private final CaseService caseService;

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
