package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.data.entity.Checkpoint;
import com.eaiselp.data.mapper.CheckpointMapper;
import com.eaiselp.data.service.CheckpointService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * {@link CheckpointService} 实现。
 *
 * <p>confirm/reject 用条件更新（WHERE status='pending'）保证状态机原子性：
 * 只能从 pending 流转到 confirmed/rejected，已处理的检查点不会被重复操作（防并发双确认）。
 *
 * <p>多租户隔离：依赖 MyBatis-Plus 租户拦截器自动注入 tenant_id（ES-003 §9.3，G13）。
 */
@Slf4j
@Service
public class CheckpointServiceImpl extends ServiceImpl<CheckpointMapper, Checkpoint> implements CheckpointService {

    @Override
    public boolean confirm(Long checkpointId, String confirmedBy, String comment) {
        if (checkpointId == null) {
            return false;
        }
        // 条件更新：仅 pending → confirmed（原子，防并发双确认）
        boolean ok = this.update(new LambdaUpdateWrapper<Checkpoint>()
                .eq(Checkpoint::getId, checkpointId)
                .eq(Checkpoint::getStatus, STATUS_PENDING)
                .set(Checkpoint::getStatus, STATUS_CONFIRMED)
                .set(Checkpoint::getConfirmedAt, LocalDateTime.now())
                .set(Checkpoint::getConfirmedBy, confirmedBy)
                .set(Checkpoint::getComment, comment));
        if (ok) {
            log.info("[Checkpoint] 确认 id={} by={} comment={}", checkpointId, confirmedBy, comment);
        } else {
            log.warn("[Checkpoint] 确认失败（不存在或非 pending）id={}", checkpointId);
        }
        return ok;
    }

    @Override
    public boolean reject(Long checkpointId, String confirmedBy, String comment) {
        if (checkpointId == null) {
            return false;
        }
        // 条件更新：仅 pending → rejected（原子，防并发双拒绝）
        boolean ok = this.update(new LambdaUpdateWrapper<Checkpoint>()
                .eq(Checkpoint::getId, checkpointId)
                .eq(Checkpoint::getStatus, STATUS_PENDING)
                .set(Checkpoint::getStatus, STATUS_REJECTED)
                .set(Checkpoint::getConfirmedAt, LocalDateTime.now())
                .set(Checkpoint::getConfirmedBy, confirmedBy)
                .set(Checkpoint::getComment, comment));
        if (ok) {
            log.info("[Checkpoint] 拒绝 id={} by={} comment={}", checkpointId, confirmedBy, comment);
        } else {
            log.warn("[Checkpoint] 拒绝失败（不存在或非 pending）id={}", checkpointId);
        }
        return ok;
    }
}
