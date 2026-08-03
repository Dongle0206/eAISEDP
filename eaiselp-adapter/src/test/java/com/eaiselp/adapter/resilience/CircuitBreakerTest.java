package com.eaiselp.adapter.resilience;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CircuitBreaker 状态流转单测（Reviewer D1 阻断修复后补）。
 * 覆盖：CLOSED→OPEN→HALF_OPEN→OPEN→冷却 闭环。
 */
class CircuitBreakerTest {

    @Test
    void closedToOpenAfterThreshold() {
        CircuitBreaker cb = new CircuitBreaker();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.allowRequest());

        // 4 次失败不跳
        for (int i = 0; i < 4; i++) {
            cb.recordFailure();
            assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        }
        // 第 5 次跳 OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowRequest());
    }

    @Test
    void openBlocksUntilTimeoutThenHalfOpen() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker();
        // 触发 OPEN
        for (int i = 0; i < CircuitBreaker.FAILURE_THRESHOLD; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowRequest());

        // 等待超过 OPEN_RESET_MS（缩短为 100ms 方便测试）
        Thread.sleep(CircuitBreaker.OPEN_RESET_MS + 50);
        // 超时后应该切 HALF_OPEN 放探针
        assertTrue(cb.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    @Test
    void halfOpenSuccessRestoresClosed() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker();
        for (int i = 0; i < CircuitBreaker.FAILURE_THRESHOLD; i++) cb.recordFailure();
        Thread.sleep(CircuitBreaker.OPEN_RESET_MS + 50);
        cb.allowRequest(); // 切 HALF_OPEN
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // 探针成功 → CLOSED
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getFailureCount());
    }

    @Test
    void halfOpenFailureReTripsOpenWithRefreshedTimestamp() throws InterruptedException {
        // Reviewer D1 阻断的核心验证：HALF_OPEN→OPEN 时 openSince 必须刷新
        CircuitBreaker cb = new CircuitBreaker();
        for (int i = 0; i < CircuitBreaker.FAILURE_THRESHOLD; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        Thread.sleep(CircuitBreaker.OPEN_RESET_MS + 50);
        cb.allowRequest(); // 切 HALF_OPEN
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // 探针失败 → 重新切 OPEN，openSince 必须刷新
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // 立即再调 allowRequest，应该被拒绝（冷却时间未到，不会立即放探针）
        assertFalse(cb.allowRequest(), "冷却时间未到不应放行探针——openSince 必须在 HALF_OPEN→OPEN 时刷新");
    }

    @Test
    void recordSuccessResetsOpenSince() {
        CircuitBreaker cb = new CircuitBreaker();
        for (int i = 0; i < CircuitBreaker.FAILURE_THRESHOLD; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getFailureCount());

        // recordSuccess 后再触发失败，failureCount 从 0 重新计数
        cb.recordFailure();
        assertEquals(1, cb.getFailureCount());
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState()); // 1 次不跳
    }

    @Test
    void concurrentFailuresTripOpenSafely() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker();
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(cb::recordFailure);
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        // 10 > THRESHOLD(5)，必须 OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowRequest());
    }
}
