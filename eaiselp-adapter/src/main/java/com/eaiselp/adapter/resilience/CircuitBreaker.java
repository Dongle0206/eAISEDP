package com.eaiselp.adapter.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轻量熔断器（单一 provider 粒度，线程安全）。
 *
 * <p>三状态有限自动机：
 * <ul>
 *   <li>{@link State#CLOSED}：放行所有请求；失败累计达 {@value #FAILURE_THRESHOLD} 次切 OPEN。</li>
 *   <li>{@link State#OPEN}：拒绝请求；超过 {@value #OPEN_RESET_MS} 后切 HALF_OPEN 放行探针。</li>
 *   <li>{@link State#HALF_OPEN}：放行探针；成功则回 CLOSED，失败则回 OPEN。</li>
 * </ul>
 *
 * <p>设计要点（线程安全，无锁）：
 * <ul>
 *   <li>状态机用 {@link AtomicReference}&lt;State&gt; 承载，状态切换走 CAS，避免悲观锁。</li>
 *   <li>{@link AtomicInteger failureCount} 与 {@link AtomicLong openSince} 为独立计数器，
 *       仅在 allowRequest/recordFailure 的临界判断里读取，最坏情况是并发下多放一个探针请求，
 *       对熔断器语义可接受（熔断为保护性兜底，不要求精确计数）。</li>
 * </ul>
 *
 * <p>无 Spring 依赖：纯 POJO，由 {@link LlmCircuitBreaker} 按 provider 维护生命周期。
 */
public class CircuitBreaker {

    /** 失败次数阈值，达此值 CLOSED→OPEN。 */
    static final int FAILURE_THRESHOLD = 5;
    /** OPEN 状态持续时长（毫秒），超过后切 HALF_OPEN 放探针。30 秒。 */
    static final long OPEN_RESET_MS = 30_000L;

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    /** 进入 OPEN 状态的时间戳（毫秒）。0 表示未进入过 OPEN。 */
    private final AtomicLong openSince = new AtomicLong(0L);

    /**
     * 是否放行本次请求。
     * <ul>
     *   <li>CLOSED → true。</li>
     *   <li>OPEN → 若距 openSince 已超 {@value #OPEN_RESET_MS} 则 CAS 切 HALF_OPEN 放探针，否则拒绝。</li>
     *   <li>HALF_OPEN → true（放探针）。</li>
     * </ul>
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            // 超时则尝试切 HALF_OPEN 放探针；未超时拒绝
            if (System.currentTimeMillis() - openSince.get() > OPEN_RESET_MS) {
                // CAS 切 HALF_OPEN；并发下仅一个线程切成功，其余仍按 OPEN 拒绝（保守）
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    return true;
                }
                // CAS 失败说明被别的线程切走了，按当前状态再判一次
                return state.get() != State.OPEN;
            }
            return false;
        }
        // HALF_OPEN：放探针
        return true;
    }

    /**
     * 记录一次成功：重置失败计数并回 CLOSED。
     *
     * <p>无论当前是 CLOSED/HALF_OPEN 都回 CLOSED（HALF_OPEN 探针成功即恢复）。
     * OPEN 理论上不应出现成功（被 allowRequest 挡掉），但若出现也按恢复处理。
     */
    public void recordSuccess() {
        failureCount.set(0);
        state.set(State.CLOSED);
        openSince.set(0L);
    }

    /**
     * 记录一次失败：累计失败计数，达 {@value #FAILURE_THRESHOLD} 次切 OPEN。
     *
     * <p>HALF_OPEN 下探针失败也切 OPEN（重置冷却计时）。
     */
    public void recordFailure() {
        int count = failureCount.incrementAndGet();
        State current = state.get();
        if (current == State.HALF_OPEN) {
            // 探针失败，立即切回 OPEN 并重置冷却
            tripOpen();
        } else if (count >= FAILURE_THRESHOLD && current != State.OPEN) {
            tripOpen();
        }
    }

    private void tripOpen() {
        // 进入 OPEN：无条件刷新时间戳（HALF_OPEN→OPEN 也必须重置冷却计时，
        // 否则旧 openSince 导致 30s 冷却被绕过——Reviewer D1 阻断修复）
        openSince.set(System.currentTimeMillis());
        state.set(State.OPEN);
    }

    /** 当前状态（观测/测试用）。 */
    public State getState() {
        return state.get();
    }

    /** 当前失败计数（观测/测试用）。 */
    public int getFailureCount() {
        return failureCount.get();
    }
}
