package com.eaiselp.runtime.hierarchy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DependencyCycleDetector 三色 DFS 单测（case-20260818 T8，AC-F3.3/F3.4 验收基线，R3 自检）。
 *
 * <p>覆盖：两节点环 / 三节点环 / 路径还原（400 提示数据源）/ 空图 / 自环 / 环序列旋转规范化
 * 去重 / 无环图 / 防御上界（节点&gt;10000、边&gt;50000 拒绝）。</p>
 */
class DependencyCycleDetectorTest {

    // ==================== wouldCycle 新边预检 ====================

    /** 两节点环：既有 101→202，新边 202→101 成环，路径 [202, 101, 202]（AC-F3.3 最小用例）。 */
    @Test
    void wouldCycle_两节点环_路径含首尾() {
        List<long[]> edges = List.of(new long[]{101L, 202L});
        Optional<List<Long>> cycle = DependencyCycleDetector.wouldCycle(edges, 202L, 101L);
        assertTrue(cycle.isPresent(), "202→101 应成环（既有 101→202）");
        assertEquals(List.of(202L, 101L, 202L), cycle.get(), "环路径 [from, to, ..., from] 首尾相同");
    }

    /** 三节点环：既有 101→202、202→303，新边 303→101 成环，路径 [303, 101, 202, 303]。 */
    @Test
    void wouldCycle_三节点环_路径还原() {
        List<long[]> edges = List.of(new long[]{101L, 202L}, new long[]{202L, 303L});
        Optional<List<Long>> cycle = DependencyCycleDetector.wouldCycle(edges, 303L, 101L);
        assertTrue(cycle.isPresent());
        assertEquals(List.of(303L, 101L, 202L, 303L), cycle.get(), "沿 DFS 路径还原三节点环");
    }

    /** 无环：既有链 101→202→303，新边 101→303（顺链）或 901→101（新源头）均不成环。 */
    @Test
    void wouldCycle_无环返回空() {
        List<long[]> edges = List.of(new long[]{101L, 202L}, new long[]{202L, 303L});
        assertTrue(DependencyCycleDetector.wouldCycle(edges, 101L, 303L).isEmpty(), "顺链方向不成环");
        assertTrue(DependencyCycleDetector.wouldCycle(edges, 901L, 101L).isEmpty(), "新源边不成环");
    }

    /** 空图：任何新边不成环；null 边集同样安全。 */
    @Test
    void wouldCycle_空图() {
        assertTrue(DependencyCycleDetector.wouldCycle(List.of(), 1L, 2L).isEmpty());
        assertTrue(DependencyCycleDetector.wouldCycle(null, 1L, 2L).isEmpty());
    }

    /** 自环：from==to 直接判定成环 [x, x]（入口层已 400 硬校验，此处兜底语义）。 */
    @Test
    void wouldCycle_自依赖即环() {
        Optional<List<Long>> cycle = DependencyCycleDetector.wouldCycle(List.of(), 7L, 7L);
        assertTrue(cycle.isPresent());
        assertEquals(List.of(7L, 7L), cycle.get());
    }

    /** 长链可达：10 节点链头尾闭合成环（路径还原跨多跳正确性）。 */
    @Test
    void wouldCycle_十节点链闭合() {
        List<long[]> edges = new ArrayList<>();
        for (long i = 1; i < 10; i++) {
            edges.add(new long[]{i, i + 1});
        }
        Optional<List<Long>> cycle = DependencyCycleDetector.wouldCycle(edges, 10L, 1L);
        assertTrue(cycle.isPresent());
        List<Long> path = cycle.get();
        assertEquals(11, path.size(), "10 节点环 + 首尾重复 = 11 个元素");
        assertEquals(10L, path.get(0), "路径以新边起点 from=10 开头");
        assertEquals(10L, path.get(path.size() - 1), "路径以 from=10 收尾（闭环）");
        for (int i = 1; i <= 10; i++) {
            assertEquals(i, path.get(i), "DFS 路径按 to=1 起依次 1→2→...→10 还原");
        }
    }

    // ==================== findCycles 全图体检 ====================

    /** 两节点环体检：报一条 [101, 202, 101]（最小节点起 rotations）。 */
    @Test
    void findCycles_两节点环() {
        List<List<Long>> cycles = DependencyCycleDetector.findCycles(
                List.of(new long[]{101L, 202L}, new long[]{202L, 101L}));
        assertEquals(1, cycles.size());
        assertEquals(List.of(101L, 202L, 101L), cycles.get(0));
    }

    /** 三节点环体检：101→202→303→101 报 [101, 202, 303, 101]。 */
    @Test
    void findCycles_三节点环() {
        List<List<Long>> cycles = DependencyCycleDetector.findCycles(List.of(
                new long[]{101L, 202L}, new long[]{202L, 303L}, new long[]{303L, 101L}));
        assertEquals(1, cycles.size());
        assertEquals(List.of(101L, 202L, 303L, 101L), cycles.get(0));
    }

    /** 去重：同一环的等价 rotations 只报一次（最小节点 5 的环以 5 起始规范化）。 */
    @Test
    void findCycles_旋转规范化去重() {
        // 环 5→9→7→5：最小节点 5 → 规范化 [5, 9, 7, 5]，且只发现一次
        List<List<Long>> cycles = DependencyCycleDetector.findCycles(List.of(
                new long[]{9L, 7L}, new long[]{7L, 5L}, new long[]{5L, 9L}));
        assertEquals(1, cycles.size(), "同一简单环按最小节点旋转规范化后 Set 去重");
        assertEquals(List.of(5L, 9L, 7L, 5L), cycles.get(0));
    }

    /** 重复边去重：同一 (a,b) 边出现两次不产生重复环报告。 */
    @Test
    void findCycles_重复边不重复报() {
        List<List<Long>> cycles = DependencyCycleDetector.findCycles(List.of(
                new long[]{101L, 202L}, new long[]{101L, 202L}, new long[]{202L, 101L}));
        assertEquals(1, cycles.size(), "重复边经 Set 去重后只报一条环");
    }

    /** 自环体检：边 (x,x) 报 [x, x]。 */
    @Test
    void findCycles_自环() {
        List<List<Long>> cycles = DependencyCycleDetector.findCycles(
                List.of(new long[]{42L, 42L}, new long[]{1L, 2L}));
        assertEquals(1, cycles.size());
        assertEquals(List.of(42L, 42L), cycles.get(0));
    }

    /** 空图/无环图：返回空列表（正常情况可见可治口径）。 */
    @Test
    void findCycles_空图与无环() {
        assertTrue(DependencyCycleDetector.findCycles(List.of()).isEmpty());
        assertTrue(DependencyCycleDetector.findCycles(null).isEmpty());
        assertTrue(DependencyCycleDetector.findCycles(List.of(
                new long[]{1L, 2L}, new long[]{1L, 3L}, new long[]{2L, 4L})).isEmpty(), "DAG 无环");
    }

    /** 两个独立环：分别报告，互不吞并。 */
    @Test
    void findCycles_两个独立环() {
        List<List<Long>> cycles = DependencyCycleDetector.findCycles(List.of(
                new long[]{1L, 2L}, new long[]{2L, 1L},
                new long[]{10L, 20L}, new long[]{20L, 10L}));
        assertEquals(2, cycles.size());
    }

    // ==================== 防御上界（R3） ====================

    /** 节点数 >10000 → GraphSizeExceededException（上层翻译 400 依赖规模超限）。 */
    @Test
    void 防御上界_节点超限() {
        List<long[]> edges = new ArrayList<>();
        for (long i = 1; i <= 10001; i++) {
            edges.add(new long[]{i, i + 1});
        }
        assertThrows(DependencyCycleDetector.GraphSizeExceededException.class,
                () -> DependencyCycleDetector.wouldCycle(edges, 1L, 2L));
        assertThrows(DependencyCycleDetector.GraphSizeExceededException.class,
                () -> DependencyCycleDetector.findCycles(edges));
    }

    /** 边数 >50000 → GraphSizeExceededException（两节点复用不触发节点上界）。 */
    @Test
    void 防御上界_边数超限() {
        List<long[]> edges = new ArrayList<>();
        for (int i = 0; i < 50001; i++) {
            edges.add(new long[]{1L, 2L});
        }
        assertThrows(DependencyCycleDetector.GraphSizeExceededException.class,
                () -> DependencyCycleDetector.wouldCycle(edges, 3L, 4L));
    }

    /** 上界内正常工作：恰好 10000 节点（9999 边链）不抛（边界值验证）。 */
    @Test
    void 防御上界_边界内不抛() {
        List<long[]> edges = new ArrayList<>();
        for (long i = 1; i <= 9999; i++) {
            edges.add(new long[]{i, i + 1});   // 节点 1..10000，恰好 10000 个
        }
        assertDoesNotThrow(() -> DependencyCycleDetector.wouldCycle(edges, 1L, 2L));
    }
}
