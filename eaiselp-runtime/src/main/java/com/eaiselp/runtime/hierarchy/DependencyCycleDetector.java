package com.eaiselp.runtime.hierarchy;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 跨项目依赖环检测器（V5 F3 三色 DFS，决策 D-6，case-20260818 T8）。
 *
 * <p><b>纯逻辑类</b>：无 Mapper 依赖、无 Spring 依赖，单测友好；规模评估（SE §6.4）——
 * 规格 100 项目/500 边下 O(V×(V+E)) ≈ 6 万步 &lt;10ms，无需 Tarjan/拓扑排序等重型算法，
 * 天然支持路径还原（成环提示需要路径而非仅布尔，AC-F3.3 的 400 提示数据源）。</p>
 *
 * <p><b>只进强边</b>：入参 strongEdges 仅含 dependency_type='depends_on' 的边
 * （relates_to 豁免不进图，AC-F3.4）——调用方（DependencyService）负责过滤。</p>
 *
 * <p><b>迭代实现（显式栈）</b>：DFS 采用显式栈而非方法递归——逻辑深度上界 max(1000,V)
 * 允许 V=10000 的深链，而 JVM 默认栈 (~1MB) 在数千帧即 StackOverflow，递归形态会让
 * "上界内正常工作"的防御承诺失效（边界值用例实测教训）；迭代化后逻辑深度上界成为
 * 唯一真实约束。</p>
 *
 * <p><b>防御上界</b>（SE §6.4，R3）：节点 &gt;10000 / 边 &gt;50000 抛
 * {@link GraphSizeExceededException}（上层翻译 400"依赖规模超限"）；遍历深度 &gt;
 * max(1000, V) 视为异常输入——记 ERROR 截断该分支（正常量级永不到达，纯防御）。</p>
 */
@Slf4j
public final class DependencyCycleDetector {

    /** 防御上界：节点数（dogfooding 量级 100，上界 100 倍余量） */
    static final int MAX_NODES = 10_000;

    /** 防御上界：边数（dogfooding 量级 500，上界 100 倍余量） */
    static final int MAX_EDGES = 50_000;

    private DependencyCycleDetector() {
    }

    /** 依赖规模超限（防御上界触发，上层翻译为 400 业务码）。 */
    public static class GraphSizeExceededException extends IllegalStateException {
        public GraphSizeExceededException(String message) {
            super(message);
        }
    }

    // ==================== 新边预检（AC-F3.3 登记前置） ====================

    /**
     * 新边预检：在既有强依赖图（不含新边）上，新增 from→to 是否成环。
     *
     * <p>判定 = 从 to 出发能否回到 from（to ⟶* from ⟹ 加边 from→to 闭合成环）；
     * 成环时沿父指针链还原环序列 {@code [from, to, ..., from]}（含首尾 from，
     * 与 api-contracts cyclePathIds 形态一致，是 400 提示"项目A→项目C→项目B→项目A"的数据源）。</p>
     *
     * @param strongEdges 既有强边集合，每个元素 [fromId, toId]（仅 depends_on）
     * @param from        新边依赖方（blocks 登记换向后的规范向）
     * @param to          新边被依赖方
     * @return 成环返回环路径（首尾相同）；不成环返回 empty；深度越界记 ERROR 返回 empty（防御）
     * @throws GraphSizeExceededException 节点&gt;10000 或边&gt;50000
     */
    public static Optional<List<Long>> wouldCycle(List<long[]> strongEdges, long from, long to) {
        // 自依赖即环（入口层已 400 硬校验，此处兜底返回 [x, x]）
        if (from == to) {
            return Optional.of(List.of(from, from));
        }
        Graph g = buildGraph(strongEdges);
        // 可达性 DFS（GRAY 上的回边不重入：目标 from 恒未入栈——命中即止于出栈判定，无漏报）
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        Deque<long[]> stack = new ArrayDeque<>();   // [node, depth]
        parent.put(to, null);
        stack.push(new long[]{to, 0});
        while (!stack.isEmpty()) {
            long[] cur = stack.pop();
            long node = cur[0];
            int depth = (int) cur[1];
            if (node == from) {
                // 沿父指针链还原 [to, ..., from]，前置新边起点 from
                List<Long> path = new ArrayList<>();
                for (Long n = node; n != null; n = parent.get(n)) {
                    path.add(n);
                }
                java.util.Collections.reverse(path);
                List<Long> cycle = new ArrayList<>(path.size() + 1);
                cycle.add(from);
                cycle.addAll(path);
                return Optional.of(cycle);
            }
            if (depth > g.maxDepth()) {
                log.error("[CycleCheck] wouldCycle 遍历深度越界（{}>{}），按不成环返回——疑似脏数据，请核查依赖规模",
                        depth, g.maxDepth());
                return Optional.empty();
            }
            if (!visited.add(node)) {
                continue;   // 已展开过的节点（多次入栈，首跳展开）
            }
            for (long next : g.adj().getOrDefault(node, List.of())) {
                if (!visited.contains(next) && !parent.containsKey(next)) {
                    parent.put(next, node);
                    stack.push(new long[]{next, depth + 1});
                }
            }
        }
        return Optional.empty();
    }

    // ==================== 全图体检（历史脏数据可见可治，AC-F3.4） ====================

    /**
     * 全图体检：找当前强依赖图中全部成环链路。
     *
     * <p>算法：对每个节点 s 发起"只经过 ≥s 节点"的 DFS——每个简单环只会从其最小节点出发
     * 被发现一次（天然规范化为最小节点起 rotations），LinkedHashSet 二次去重兜底重复边；
     * 自环边 (x,x) 报 {@code [x, x]}。正常情况返回空（可见可治，PRD §4.3.3）。</p>
     *
     * @param strongEdges 既有强边集合，每个元素 [fromId, toId]
     * @return 去重后的环路径列表（每条首尾相同）
     * @throws GraphSizeExceededException 节点&gt;10000 或边&gt;50000
     */
    public static List<List<Long>> findCycles(List<long[]> strongEdges) {
        Graph g = buildGraph(strongEdges);
        Set<List<Long>> cycles = new LinkedHashSet<>();
        for (long s : g.sortedNodes()) {
            enumerateCyclesFrom(s, g.adj(), cycles, g.maxDepth());
        }
        return new ArrayList<>(cycles);
    }

    /** 迭代式环枚举：显式栈维护 (节点, 邻接迭代器) 帧；只走 &gt;start 的节点（更小留给以它为根的遍历）。 */
    private static void enumerateCyclesFrom(long start, Map<Long, List<Long>> adj,
                                            Set<List<Long>> cycles, int maxDepth) {
        record Frame(long node, java.util.Iterator<Long> it) {
        }
        Deque<Long> path = new ArrayDeque<>();
        Set<Long> onPath = new HashSet<>();
        Deque<Frame> frames = new ArrayDeque<>();
        path.addLast(start);
        onPath.add(start);
        frames.push(new Frame(start, adj.getOrDefault(start, List.of()).iterator()));
        while (!frames.isEmpty()) {
            Frame f = frames.peek();
            if (f.it().hasNext()) {
                long next = f.it().next();
                if (next == start) {
                    List<Long> cycle = new ArrayList<>(path);   // 闭环：path + start（最小节点起）
                    cycle.add(start);
                    cycles.add(cycle);
                } else if (next > start && !onPath.contains(next)) {
                    if (frames.size() > maxDepth) {
                        log.error("[CycleCheck] findCycles 遍历深度越界（{}>{}），截断该分支——疑似脏数据，请核查依赖规模",
                                frames.size(), maxDepth);
                        continue;
                    }
                    path.addLast(next);
                    onPath.add(next);
                    frames.push(new Frame(next, adj.getOrDefault(next, List.of()).iterator()));
                }
            } else {
                frames.pop();
                onPath.remove(path.removeLast());
            }
        }
    }

    // ==================== 图构建与防御 ====================

    private static Graph buildGraph(List<long[]> strongEdges) {
        if (strongEdges == null || strongEdges.isEmpty()) {
            return new Graph(new TreeSet<>(), new HashMap<>(), 1000);
        }
        if (strongEdges.size() > MAX_EDGES) {
            throw new GraphSizeExceededException("依赖规模超限，请先清理依赖数据（边数>" + MAX_EDGES + "）");
        }
        Map<Long, List<Long>> adj = new HashMap<>();
        TreeSet<Long> nodes = new TreeSet<>();
        for (long[] e : strongEdges) {
            if (e == null || e.length < 2) {
                continue;
            }
            adj.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
            nodes.add(e[0]);
            nodes.add(e[1]);
        }
        if (nodes.size() > MAX_NODES) {
            throw new GraphSizeExceededException("依赖规模超限，请先清理依赖数据（节点数>" + MAX_NODES + "）");
        }
        return new Graph(nodes, adj, Math.max(1000, nodes.size()));
    }

    /** 邻接表 + 排序节点集 + 遍历深度上界 max(1000, V)。 */
    private record Graph(TreeSet<Long> sortedNodes, Map<Long, List<Long>> adj, int maxDepth) {
    }
}
