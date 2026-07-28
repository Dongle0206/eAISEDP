package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaiselp.common.ratelimit.RateLimit;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.entity.Checkpoint;
import com.eaiselp.data.service.ArtifactService;
import com.eaiselp.data.service.CaseService;
import com.eaiselp.data.service.CheckpointService;
import com.eaiselp.data.service.DerivationService;
import com.eaiselp.runtime.casestate.CaseStatus;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 看板统计 REST API（M2 SP-1 Web 工作台首页数据源）。
 *
 * <p>提供三类看板数据：
 * <ul>
 *   <li>{@code GET /api/v1/dashboard/overview} — 总览（Case 总数 / 状态分布 / 派生总数 /
 *       token 总消耗 / 检查点待审数）；</li>
 *   <li>{@code GET /api/v1/dashboard/case-stats} — Case 状态分布
 *       （drafting / deriving / reviewing / testing / deploying / done）；</li>
 *   <li>{@code GET /api/v1/dashboard/derivation-stats} — 派生统计
 *       （按角色分组的派生次数 + token 消耗）。</li>
 * </ul>
 *
 * <p>权限：读类接口需 {@code artifact:view}（看板为平台总览，对齐产物查看权限口径）。
 *
 * <p>限流：看板为高频查询接口，按用户维度限 60 次/分（防轮询打爆，SE §4.2.3 通用配置）。
 *
 * <p>查询实现：用 MyBatis-Plus 的 {@code count} + {@code groupBy}（{@code listMaps}），
 * 避免逐状态 N+1 查询（每个 status 一条 count → 6 条 SQL 是 N+1，改 1 条 groupBy）。
 *
 * <p>多租户隔离（ES-003 §9.3 P11，G13）：所有统计经 MyBatis-Plus 租户拦截器
 * 自动按 TenantContext 注入 tenant_id 过滤，看板数据严格限定在当前租户范围内。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final CaseService caseService;
    private final DerivationService derivationService;
    private final ArtifactService artifactService;
    private final CheckpointService checkpointService;

    /**
     * 总览：Case 总数 / 各状态数 / 派生总数 / token 总消耗 / 检查点待审数。
     *
     * <p>Case 状态分布复用 {@link #caseStats()} 的单条 groupBy 结果（不重复查询）。
     */
    @GetMapping("/overview")
    @RequirePermission("artifact:view")
    @RateLimit(name = "dashboard-overview", key = RateLimit.KeyType.USER,
            capacity = 60, refillPerMin = 60,
            message = "查询请求过于频繁，请稍后再试")
    public R<OverviewVo> overview() {
        OverviewVo vo = new OverviewVo();
        // Case 总数 + 状态分布（单条 groupBy，避免 N+1）
        Map<String, Long> statusMap = countCaseByStatus();
        vo.setStatusDistribution(statusMap);
        vo.setCaseTotal(statusMap.values().stream().mapToLong(Long::longValue).sum());
        // 派生总数 + token 总消耗（单条 groupBy 全表聚合）
        List<Map<String, Object>> roleStats = derivationService.countAndTokensByRole();
        long derivationTotal = 0L;
        long tokenTotal = 0L;
        for (Map<String, Object> row : roleStats) {
            derivationTotal += toLong(row.get("count"));
            tokenTotal += toLong(row.get("totalTokens"));
        }
        vo.setDerivationTotal(derivationTotal);
        vo.setTokenTotal(tokenTotal);
        // 产物总数（过程资产总量）
        vo.setArtifactTotal(artifactService.count());
        // 检查点待审数（status=pending）
        vo.setCheckpointPending(checkpointService.count(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Checkpoint>()
                        .eq(Checkpoint::getStatus, CheckpointService.STATUS_PENDING)));
        return R.ok(vo);
    }

    /**
     * Case 状态分布（drafting / deriving / reviewing / testing / deploying / done 各多少）。
     *
     * <p>用一条 groupBy status 的 SQL 取全部状态计数，补齐零计数状态（保证 6 个枚举值齐全，
     * 前端无需处理 key 缺失）。
     */
    @GetMapping("/case-stats")
    @RequirePermission("artifact:view")
    @RateLimit(name = "dashboard-case-stats", key = RateLimit.KeyType.USER,
            capacity = 60, refillPerMin = 60,
            message = "查询请求过于频繁，请稍后再试")
    public R<Map<String, Long>> caseStats() {
        return R.ok(countCaseByStatus());
    }

    /**
     * 派生统计（按角色分组的派生次数 + token 消耗）。
     *
     * <p>返回每行含 role / count / totalTokens 三个字段，前端可直接渲染角色消耗排行。
     */
    @GetMapping("/derivation-stats")
    @RequirePermission("artifact:view")
    @RateLimit(name = "dashboard-derivation-stats", key = RateLimit.KeyType.USER,
            capacity = 60, refillPerMin = 60,
            message = "查询请求过于频繁，请稍后再试")
    public R<List<Map<String, Object>>> derivationStats() {
        return R.ok(derivationService.countAndTokensByRole());
    }

    // -------- helpers --------

    /**
     * 单条 groupBy status 取 Case 状态计数（避免逐状态 N+1）。
     *
     * <p>结果补齐 {@link CaseStatus} 全部 6 个枚举值（零计数补 0），用 LinkedHashMap 保序。
     */
    private Map<String, Long> countCaseByStatus() {
        // 1. 预置全部 CaseStatus 枚举值为 0（保证齐全 + 保序）
        Map<String, Long> result = new LinkedHashMap<>();
        for (CaseStatus s : CaseStatus.values()) {
            result.put(s.dbValue(), 0L);
        }
        // 2. 单条 groupBy 覆盖实际计数
        QueryWrapper<com.eaiselp.data.entity.Case> qw = new QueryWrapper<com.eaiselp.data.entity.Case>()
                .select("status", "COUNT(*) AS cnt")
                .groupBy("status");
        List<Map<String, Object>> rows = caseService.listMaps(qw);
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            if (status == null) {
                continue;
            }
            result.put(status.toString(), toLong(row.get("cnt")));
        }
        return result;
    }

    /** 安全转 long：兼容 Number / String（不同 DB 驱动返回类型差异）。 */
    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 总览 VO。 */
    @Data
    public static class OverviewVo {
        /** Case 总数。 */
        private long caseTotal;
        /** Case 状态分布（status → count，6 个枚举值齐全）。 */
        private Map<String, Long> statusDistribution;
        /** 派生总数（所有角色合计）。 */
        private long derivationTotal;
        /** token 总消耗（input + output 合计）。 */
        private long tokenTotal;
        /** 产物总数（过程资产总量）。 */
        private long artifactTotal;
        /** 待审检查点数（status=pending）。 */
        private long checkpointPending;
    }
}
