package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.mapper.CaseMapper;
import com.eaiselp.data.mapper.DerivationMapper;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import com.eaiselp.runtime.hierarchy.dto.DoraBoardVo;
import com.eaiselp.runtime.orchestration.OrchestrationRecord;
import com.eaiselp.runtime.orchestration.OrchestrationRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DORA 效能度量聚合服务（V5 F1，case-20260818 T14——本 Case 最重任务）。
 *
 * <p><b>口径唯一权威 = PRD §4.1.2，落地规则 = SE §4.3</b>；实时聚合 + 5min TTL 进程内缓存
 * （决策 D-1：数据量评估证明预聚合表不必要，TTL=300s 恰满足"延迟≤5 分钟"上界）。</p>
 *
 * <p><b>SQL 形态规约（SE §4.2，沿 PRJ-002 R4）</b>：
 * <ul>
 *   <li><b>不跨 IGNORE_TABLES 边界 JOIN</b>（t_governance_log 不走拦截器，JOIN 改写不可控）——
 *       全部"两段式 IN 查询 + 内存聚合"；IN 分批 500。</li>
 *   <li><b>t_governance_log 手写 tenant_id</b> 等值 + action='case_transit' + create_time 下界，
 *       命中 idx_tenant_action_time；t_case/t_orchestration/t_derivation 走拦截器不写 tenant_id。</li>
 * </ul>
 *
 * <p><b>四指标口径速查</b>（QA 断言构造值：0.1 次/天、36h/48h、33.3%、45min、M=1、≈角标）：
 * <ul>
 *   <li><b>DF</b>：周期内首次流转 done 的去重 Case 数 N ÷ periodDays 自然日。</li>
 *   <li><b>LT</b>：doneTs−t_case.create_time；P50=线性插值（PERCENTILE.INC 语义，[24h,48h]→36h）、
 *       P90=向上取整序位 ceil(0.9N)（保守取大，N=2→48h）；status=done 但无审计的 Case 排除计
 *       excludedCount M（<b>严禁 update_time 冒充</b>，AC-F1.5）。</li>
 *   <li><b>CFR</b>（代理指标）：分母=终态编排 Case 去重（按 case 取最新终态记录）；分子两源合一
 *       防双计——①steps_json 存在 status=failed 且 gateResult=FAIL 的步骤（终判失败终稿不被
 *       重跑覆盖，口径自洽）②auto_check 阻断（编排 failed 且 validation_json.allPassed=false
 *       且无①）；FAIL_WARN 不计；steps_json 解析失败计 parseErrorCount（单卡降级，不 500 整页）。</li>
 *   <li><b>打回率</b>（参考值，非四指标）：分子信号源=<b>t_derivation.gate_result='FAIL'</b>
 *       （Y2 修复：旧实现读最新终态编排 steps_json 的 gateResult，打回重跑后被 PASS 覆盖致恒 0；
 *       t_derivation 每轮派生独立 INSERT，历史 FAIL 行不被覆盖），走 case_id 关联 Case 维度。</li>
 *   <li><b>RT</b>：样本框=周期内 done 且非 CFR 分子；按 caseId+role 分组——有 gate_result 埋点行
 *       → max(PASS finished_at)−min(FAIL finished_at)（精确）；无埋点多行 → 末条 finished_at−
 *       首条 started_at（approximate=true"≈"角标，D-2 不回填历史已裁决）。近似分支的 started_at
 *       写入方=<b>独立异步派生 createPending</b>（提交时刻口径，Y5 确认）；编排路径派生行
 *       started_at 恒 NULL（DerivationPersistenceService §3.6 无字段可写）→ 此类无埋点多行组
 *       整组静默排除（不入样本也不计近似数）。</li>
 *   <li><b>project_id 为空的 Case</b> 在 Case 池阶段即被 IN 条件排除（AC-F1.6 的 C9）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoraMetricsService {

    private final ProjectMapper projectMapper;
    private final ProgramMapper programMapper;
    private final CaseMapper caseMapper;
    private final GovernanceLogMapper governanceLogMapper;
    private final OrchestrationRecordMapper orchestrationRecordMapper;
    private final DerivationMapper derivationMapper;

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 缓存 TTL：300s=5min（"延迟≤5 分钟"的恰好上界，D-1） */
    static final long CACHE_TTL_MS = 300_000L;

    /** IN 分批大小（两段式查询规约） */
    private static final int IN_BATCH = 500;

    /** 终态编排状态集合（CFR 分母） */
    private static final List<String> TERMINAL_ORCH = List.of("done", "failed");

    /** 空态文案（api-contracts §1 契约） */
    private static final String EMPTY_NO_PROJECT = "先创建项目并关联 Case";
    private static final String EMPTY_NO_DATA = "暂无统计数据，完成 Case 后自动生成";

    /** 5min TTL 进程内缓存（key=tenantId|scope|scopeId|periodDays；治理数据非强一致，无主动失效，D-1） */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(DoraBoardVo vo, long expireAt) {
    }

    /** 编排终态记录的内存分析结果（steps_json 解析一次，四指标共用；包内可见供单测直测两源口径）。 */
    record TerminalCase(String caseId, String orchStatus, boolean llmFinalFail,
                        boolean autoCheckBlocked) {
    }

    // ==================== 入口 ====================

    /**
     * DORA 看板聚合（缓存 5min，D-1）。
     *
     * @throws com.eaiselp.common.exception.BizException 400（scope 非法/scope≠all 缺 scopeId/periodDays 非三档）、
     *                                                    404（scopeId 不存在）
     */
    public DoraBoardVo dora(String scope, Long scopeId, Integer periodDays) {
        // 参数三态校验（薄控制器 T19，校验全在本层，api-contracts §1 错误表）
        if (!"project".equals(scope) && !"program".equals(scope) && !"all".equals(scope)) {
            throw new BizException(400, "scope 非法，应为 project/program/all");
        }
        if (!"all".equals(scope) && scopeId == null) {
            throw new BizException(400, "scope=project/program 时 scopeId 必填");
        }
        int days = periodDays == null ? 30 : periodDays;
        if (days != 7 && days != 30 && days != 90) {
            throw new BizException(400, "periodDays 非法，应为 7/30/90");
        }
        long tenantId = TenantContext.get();
        String key = tenantId + "|" + scope + "|" + scopeId + "|" + days;
        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(key);
        if (hit != null && now < hit.expireAt()) {
            return hit.vo();
        }
        long startMs = System.currentTimeMillis();
        DoraBoardVo vo = compute(scope, scopeId, days, tenantId);
        cache.put(key, new CacheEntry(vo, now + CACHE_TTL_MS));
        log.info("[DORA] 缓存未命中实时聚合: tenantId={}, scope={}, scopeId={}, periodDays={}, "
                        + "df样本={}, lt样本={}, cfr样本={}, rt样本={}, 耗时={}ms",
                tenantId, scope, scopeId, days,
                sampleCount(vo.getDeploymentFrequency()), sampleCount(vo.getLeadTime()),
                sampleCount(vo.getChangeFailureRate()), sampleCount(vo.getTimeToRestore()),
                System.currentTimeMillis() - startMs);
        return vo;
    }

    private static Object sampleCount(Object card) {
        if (card instanceof DoraBoardVo.DeploymentFrequencyCard c) {
            return c.getSampleCount();
        }
        if (card instanceof DoraBoardVo.LeadTimeCard c) {
            return c.getSampleCount();
        }
        if (card instanceof DoraBoardVo.ChangeFailureRateCard c) {
            return c.getSampleCount();
        }
        if (card instanceof DoraBoardVo.TimeToRestoreCard c) {
            return c.getSampleCount();
        }
        return "n/a";
    }

    // ==================== 主计算 ====================

    private DoraBoardVo compute(String scope, Long scopeId, int days, long tenantId) {
        DoraBoardVo vo = new DoraBoardVo();
        vo.setScope(scope);
        vo.setScopeId("all".equals(scope) ? null : scopeId);
        vo.setPeriodDays(days);

        // ① scope 三态解析 → 项目 id 池（t_project 走拦截器）
        List<Long> projectPool = resolveProjectPool(scope, scopeId);

        // ② 空态：无项目（引导建项目）/ 有项目无 Case（引导完成 Case）
        if (projectPool.isEmpty()) {
            vo.setEmptyState(EMPTY_NO_PROJECT);
            return vo;
        }
        List<Case> cases = new ArrayList<>();
        for (List<Long> batch : partition(projectPool, IN_BATCH)) {
            cases.addAll(caseMapper.selectList(new LambdaQueryWrapper<Case>()
                    .select(Case::getCaseId, Case::getStatus, Case::getCreateTime)
                    .in(Case::getProjectId, batch)));
        }
        if (cases.isEmpty()) {
            vo.setEmptyState(EMPTY_NO_DATA);
            return vo;
        }
        Map<String, Case> caseById = new LinkedHashMap<>();
        cases.forEach(c -> {
            if (c.getCaseId() != null) {
                caseById.put(c.getCaseId(), c);
            }
        });
        LocalDateTime periodStart = LocalDateTime.now().minusDays(days);

        // ③ done 审计两段式查询：手写 tenant_id + action + create_time 下界（命中 idx_tenant_action_time）
        Map<String, LocalDateTime> firstDoneTs = loadFirstDoneTransit(caseById.keySet(), tenantId, periodStart);

        // ④ DF：周期内首次流转 done 的去重 Case 数 ÷ periodDays
        vo.setDeploymentFrequency(buildDfCard(firstDoneTs.size(), days));

        // ⑤ LT：doneTs−create_time 分位数 + excludedCount（done 无审计的历史 Case，严禁 update_time 冒充）
        vo.setLeadTime(buildLtCard(caseById, firstDoneTs, tenantId));

        // ⑥ CFR：终态编排两源分子（解析失败计 parseErrorCount 单卡降级）
        TerminalAnalysis terminal = loadTerminalCases(caseById.keySet());
        Set<String> cfrFailures = new HashSet<>();
        for (TerminalCase tc : terminal.casesByCaseId().values()) {
            if (tc.llmFinalFail() || tc.autoCheckBlocked()) {
                cfrFailures.add(tc.caseId());
            }
        }
        vo.setChangeFailureRate(buildCfrCard(terminal.casesByCaseId(), cfrFailures, terminal.parseErrors()));

        // ⑦ RT：样本框=周期内 done 且非 CFR 分子；埋点精确/无埋点≈近似
        vo.setTimeToRestore(buildRtCard(firstDoneTs, cfrFailures));

        // ⑧ 打回率（参考值，非四指标）：分子信号源=t_derivation.gate_result='FAIL'（Y2 修复）
        vo.setGateReworkRate(buildReworkCard(terminal.casesByCaseId(), cfrFailures,
                loadFailGateCaseIds(terminal.casesByCaseId().keySet())));
        return vo;
    }

    /** scope 解析：project→单项目（404 校验）/ program→成员项目（404 校验）/ all→租户全量。 */
    private List<Long> resolveProjectPool(String scope, Long scopeId) {
        if ("project".equals(scope)) {
            if (projectMapper.selectById(scopeId) == null) {
                throw new BizException(404, "项目不存在: " + scopeId);
            }
            return List.of(scopeId);
        }
        if ("program".equals(scope)) {
            if (programMapper.selectById(scopeId) == null) {
                throw new BizException(404, "项目群不存在: " + scopeId);
            }
            return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                            .select(Project::getId).eq(Project::getProgramId, scopeId))
                    .stream().map(Project::getId).filter(Objects::nonNull).toList();
        }
        return projectMapper.selectList(new LambdaQueryWrapper<Project>().select(Project::getId))
                .stream().map(Project::getId).filter(Objects::nonNull).toList();
    }

    /**
     * 周期内 case_transit 审计：内存解析 detail.targetStatus='done'，按 caseId 去重取最早
     * （幂等重放防线，F-20）。t_governance_log 在 IGNORE_TABLES——tenant_id 手写。
     */
    private Map<String, LocalDateTime> loadFirstDoneTransit(Set<String> caseIds, long tenantId,
                                                            LocalDateTime periodStart) {
        Map<String, LocalDateTime> firstDone = new HashMap<>();
        for (List<String> batch : partition(new ArrayList<>(caseIds), IN_BATCH)) {
            List<GovernanceLog> audits = governanceLogMapper.selectList(new LambdaQueryWrapper<GovernanceLog>()
                    .select(GovernanceLog::getResourceId, GovernanceLog::getDetail, GovernanceLog::getCreateTime)
                    .eq(GovernanceLog::getTenantId, tenantId)              // 手写 tenant_id（IGNORE_TABLES）
                    .eq(GovernanceLog::getAction, "case_transit")
                    .ge(GovernanceLog::getCreateTime, periodStart)
                    .in(GovernanceLog::getResourceId, batch));
            for (GovernanceLog audit : audits) {
                if (audit.getResourceId() == null || audit.getDetail() == null
                        || audit.getCreateTime() == null) {
                    continue;
                }
                try {
                    if (!"done".equals(OM.readTree(audit.getDetail()).path("targetStatus").asText())) {
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("[DORA] case_transit detail 解析失败（跳过该行）resourceId={}", audit.getResourceId());
                    continue;
                }
                firstDone.merge(audit.getResourceId(), audit.getCreateTime(),
                        (a, b) -> a.isBefore(b) ? a : b);   // 去重取最早
            }
        }
        return firstDone;
    }

    // ==================== 四指标卡构建 ====================

    /** DF 卡：N/periodDays + 分档（0.1 → high 档，分档表与前端常量同口径集中定义）。 */
    private DoraBoardVo.DeploymentFrequencyCard buildDfCard(int n, int days) {
        DoraBoardVo.DeploymentFrequencyCard card = new DoraBoardVo.DeploymentFrequencyCard();
        card.setSampleCount(n);
        if (n > 0) {
            double v = round3(n / (double) days);
            card.setValue(v);
            card.setBand(bandOf(v));
        }
        return card;
    }

    /** DF 分档表（前端常量集中一处镜像；服务端同口径计算，≥1=elite / ≥0.1=high / ≥1/30=medium / else low）。 */
    static String bandOf(double perDay) {
        if (perDay >= 1) {
            return "elite";
        }
        if (perDay >= 0.1) {
            return "high";
        }
        if (perDay >= 1.0 / 30) {
            return "medium";
        }
        return "low";
    }

    /** LT 卡：P50 线性插值 / P90 序位取整 + excludedCount M。 */
    private DoraBoardVo.LeadTimeCard buildLtCard(Map<String, Case> caseById,
                                                 Map<String, LocalDateTime> firstDoneTs, long tenantId) {
        DoraBoardVo.LeadTimeCard card = new DoraBoardVo.LeadTimeCard();
        List<Double> hours = new ArrayList<>();
        for (Map.Entry<String, LocalDateTime> e : firstDoneTs.entrySet()) {
            Case c = caseById.get(e.getKey());
            if (c != null && c.getCreateTime() != null) {
                hours.add(Duration.between(c.getCreateTime(), e.getValue()).toMinutes() / 60.0);
            }
        }
        card.setSampleCount(hours.size());
        if (!hours.isEmpty()) {
            hours.sort(Double::compareTo);
            double p50 = percentileInc(hours, 0.5);
            double p90 = percentileRankCeil(hours, 0.9);
            card.setP50Hours(round1(p50));
            card.setP90Hours(round1(p90));
            card.setDisplay("P50 " + formatHours(p50) + " / P90 " + formatHours(p90));
        }
        // excludedCount：status=done 且周期内无 done 审计的 Case——再查全历史审计确认"从未有"
        //（防止把"审计早于周期"的旧完成误计为不可回溯）；确无任何 done 审计才计 M
        List<String> doneNoPeriodAudit = caseById.entrySet().stream()
                .filter(e -> "done".equals(e.getValue().getStatus()) && !firstDoneTs.containsKey(e.getKey()))
                .map(Map.Entry::getKey).toList();
        int excluded = 0;
        if (!doneNoPeriodAudit.isEmpty()) {
            Set<String> everDone = new HashSet<>();
            for (List<String> batch : partition(doneNoPeriodAudit, IN_BATCH)) {
                for (GovernanceLog audit : governanceLogMapper.selectList(new LambdaQueryWrapper<GovernanceLog>()
                        .select(GovernanceLog::getResourceId, GovernanceLog::getDetail)
                        .eq(GovernanceLog::getTenantId, tenantId)
                        .eq(GovernanceLog::getAction, "case_transit")
                        .in(GovernanceLog::getResourceId, batch))) {
                    try {
                        if (audit.getResourceId() != null && audit.getDetail() != null
                                && "done".equals(OM.readTree(audit.getDetail()).path("targetStatus").asText())) {
                            everDone.add(audit.getResourceId());
                        }
                    } catch (Exception ignore) {
                        // 坏 detail 行按无 targetStatus 处理
                    }
                }
            }
            excluded = (int) doneNoPeriodAudit.stream().filter(id -> !everDone.contains(id)).count();
        }
        card.setExcludedCount(excluded);
        if (excluded > 0) {
            card.setExclusionNote("另有 " + excluded + " 条历史数据不可回溯，已排除");
        }
        return card;
    }

    /** CFR 卡：两源分子防双计 ÷ 终态编排去重（解析失败样本跳过并计 parseErrorCount）。 */
    private DoraBoardVo.ChangeFailureRateCard buildCfrCard(Map<String, TerminalCase> terminalByCase,
                                                           Set<String> cfrFailures, int parseErrors) {
        DoraBoardVo.ChangeFailureRateCard card = new DoraBoardVo.ChangeFailureRateCard();
        card.setSampleCount(terminalByCase.size());
        card.setParseErrorCount(parseErrors);
        if (!terminalByCase.isEmpty()) {
            double v = round3(cfrFailures.size() / (double) terminalByCase.size());
            card.setValue(v);
            card.setPercentDisplay(String.format("%.1f%%", v * 100));
        }
        return card;
    }

    /** RT 卡：埋点精确（max PASS−min FAIL）/ 无埋点≈近似（末条 finished−首条 started）。 */
    private DoraBoardVo.TimeToRestoreCard buildRtCard(Map<String, LocalDateTime> firstDoneTs,
                                                      Set<String> cfrFailures) {
        DoraBoardVo.TimeToRestoreCard card = new DoraBoardVo.TimeToRestoreCard();
        List<String> rtBox = firstDoneTs.keySet().stream()
                .filter(cid -> !cfrFailures.contains(cid)).toList();   // 终判失败无恢复
        if (rtBox.isEmpty()) {
            card.setSampleCount(0);
            card.setApproximateCount(0);
            return card;
        }
        List<Derivation> derivations = new ArrayList<>();
        for (List<String> batch : partition(rtBox, IN_BATCH)) {
            derivations.addAll(derivationMapper.selectList(new LambdaQueryWrapper<Derivation>()
                    .select(Derivation::getId, Derivation::getCaseId, Derivation::getRole,
                            Derivation::getGateResult, Derivation::getStartedAt, Derivation::getFinishedAt)
                    .in(Derivation::getCaseId, batch)
                    .orderByAsc(Derivation::getId)));
        }
        // 按 caseId+role 分组
        Map<String, List<Derivation>> groups = new LinkedHashMap<>();
        for (Derivation d : derivations) {
            if (d.getCaseId() == null || d.getRole() == null) {
                continue;
            }
            groups.computeIfAbsent(d.getCaseId() + "|" + d.getRole(), k -> new ArrayList<>()).add(d);
        }
        List<Double> minutes = new ArrayList<>();
        int approximate = 0;
        for (List<Derivation> group : groups.values()) {
            boolean hasMarked = group.stream().anyMatch(d -> d.getGateResult() != null);
            if (hasMarked) {
                LocalDateTime firstFail = null;
                LocalDateTime lastPass = null;
                for (Derivation d : group) {
                    if ("FAIL".equals(d.getGateResult()) && d.getFinishedAt() != null
                            && (firstFail == null || d.getFinishedAt().isBefore(firstFail))) {
                        firstFail = d.getFinishedAt();
                    }
                    if ("PASS".equals(d.getGateResult()) && d.getFinishedAt() != null
                            && (lastPass == null || d.getFinishedAt().isAfter(lastPass))) {
                        lastPass = d.getFinishedAt();
                    }
                }
                if (firstFail != null && lastPass != null && lastPass.isAfter(firstFail)) {
                    minutes.add(Duration.between(firstFail, lastPass).toMinutes() * 1.0);
                }
            } else if (group.size() > 1) {
                // 无埋点多行：末条 finished_at − 首条 started_at（打回间隙计入，系统性偏大，"≈"）。
                // Y5 口径确认：started_at 唯一写入方=独立异步派生链路（DerivationTaskService.createPending
                // 提交时刻写入，markRunning 刷新为实际开跑时刻），故本分支可近似使用、保留（D-2 不回填历史）；
                // 编排路径派生行 started_at 恒 NULL（engine 同步 persist 无该字段可写，§3.6）——此类组
                // 不满足 startedAt!=null 前置，整组静默排除（不入样本亦不计 approximateCount）。
                Derivation first = group.get(0);
                Derivation last = group.get(group.size() - 1);
                if (first.getStartedAt() != null && last.getFinishedAt() != null) {
                    minutes.add(Duration.between(first.getStartedAt(), last.getFinishedAt()).toMinutes() * 1.0);
                    approximate++;
                }
            }
        }
        card.setSampleCount(minutes.size());
        card.setApproximateCount(approximate);
        if (!minutes.isEmpty()) {
            minutes.sort(Double::compareTo);
            card.setP50Minutes((int) Math.round(percentileInc(minutes, 0.5)));
            card.setAvgMinutes((int) Math.round(minutes.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        }
        return card;
    }

    /** 打回率卡（参考值）：终态 Case 中存在过 FAIL 信号但未终判失败者 ÷ 分母。 */
    private DoraBoardVo.GateReworkRateCard buildReworkCard(Map<String, TerminalCase> terminalByCase,
                                                           Set<String> cfrFailures,
                                                           Set<String> failGateCaseIds) {
        DoraBoardVo.GateReworkRateCard card = new DoraBoardVo.GateReworkRateCard();
        if (terminalByCase.isEmpty()) {
            card.setValue(null);
            return card;
        }
        long reworked = terminalByCase.values().stream()
                .filter(tc -> !cfrFailures.contains(tc.caseId()))
                // Y2 修复：分子信号源=t_derivation.gate_result='FAIL'（每轮派生独立 INSERT，重跑不覆盖）；
                // 旧 steps_json 步骤级 gateResult 在打回重跑后被 PASS 覆盖 → "存在过 FAIL"恒 0
                .filter(tc -> failGateCaseIds.contains(tc.caseId()))
                .count();
        card.setValue(round3(reworked / (double) terminalByCase.size()));
        return card;
    }

    /**
     * 打回率分子信号源（Y2 修复）：t_derivation.gate_result='FAIL' 的 Case 集合。
     *
     * <p>SELECT case_id WHERE gate_result='FAIL' AND case_id IN (...)，走 case_id 关联 Case 维度、
     * IN 分批 500（两段式规约）；t_derivation 走租户拦截器不写 tenant_id。FAIL 精确匹配——
     * FAIL_WARN 不计（与 CFR 口径一致）。打回重跑只新增派生行、markGateResult 仅 UPDATE 当轮行，
     * 历史 FAIL 永久保留（steps_json 无此性质，故弃用）。</p>
     */
    private Set<String> loadFailGateCaseIds(Set<String> caseIds) {
        Set<String> failCases = new HashSet<>();
        for (List<String> batch : partition(new ArrayList<>(caseIds), IN_BATCH)) {
            for (Derivation d : derivationMapper.selectList(new LambdaQueryWrapper<Derivation>()
                    .select(Derivation::getCaseId)
                    .in(Derivation::getCaseId, batch)
                    .eq(Derivation::getGateResult, "FAIL"))) {
                if (d.getCaseId() != null) {
                    failCases.add(d.getCaseId());
                }
            }
        }
        return failCases;
    }

    // ==================== 终态编排分析（CFR 分母/分子 + 打回率） ====================

    /** 终态编排分析结果：可解析的终态 Case 集 + steps_json 解析失败计数（parseErrorCount）。 */
    record TerminalAnalysis(Map<String, TerminalCase> casesByCaseId, int parseErrors) {
    }

    /**
     * 终态编排 Case 加载与分析：按 case 取最新终态记录（id 升序覆盖末条胜出）；
     * steps_json 解析失败 → 该 Case 跳过并计入 parseErrors（指标卡降级表达，不 500 整页）。
     */
    private TerminalAnalysis loadTerminalCases(Set<String> caseIds) {
        Map<String, OrchestrationRecord> latestByCase = new LinkedHashMap<>();
        for (List<String> batch : partition(new ArrayList<>(caseIds), IN_BATCH)) {
            for (OrchestrationRecord r : orchestrationRecordMapper.selectList(
                    new LambdaQueryWrapper<OrchestrationRecord>()
                            .select(OrchestrationRecord::getId, OrchestrationRecord::getCaseId,
                                    OrchestrationRecord::getStatus, OrchestrationRecord::getStepsJson,
                                    OrchestrationRecord::getValidationJson)
                            .in(OrchestrationRecord::getCaseId, batch)
                            .in(OrchestrationRecord::getStatus, TERMINAL_ORCH)
                            .orderByAsc(OrchestrationRecord::getId))) {
                if (r.getCaseId() != null) {
                    latestByCase.put(r.getCaseId(), r);   // id 升序 → 后写覆盖=最新
                }
            }
        }
        Map<String, TerminalCase> result = new LinkedHashMap<>();
        int parseErrors = 0;
        for (Map.Entry<String, OrchestrationRecord> e : latestByCase.entrySet()) {
            TerminalCase tc = analyzeTerminal(e.getValue());
            if (tc != null) {
                result.put(e.getKey(), tc);
            } else {
                parseErrors++;
            }
        }
        return new TerminalAnalysis(result, parseErrors);
    }

    /**
     * 单条终态编排分析（包内可见，单测直测两源口径）：
     * ①llmFinalFail = steps 存在 status=failed 且 gateResult=FAIL（终判失败终稿，不被重跑覆盖）；
     * ②autoCheckBlocked = 编排 failed 且 validation.allPassed=false 且无①（防双计）；
     * FAIL_WARN 不计；steps_json 坏 → null（调用方计 parseErrorCount）。
     */
    static TerminalCase analyzeTerminal(OrchestrationRecord r) {
        boolean llmFinalFail = false;
        try {
            var steps = OM.readTree(r.getStepsJson() == null ? "[]" : r.getStepsJson());
            for (var step : steps) {
                if ("FAIL".equals(step.path("gateResult").asText(null))
                        && "failed".equals(step.path("status").asText())) {
                    llmFinalFail = true;
                }
            }
        } catch (Exception e) {
            log.warn("[DORA] steps_json 解析失败（该 Case 计 parseErrorCount 降级）caseId={}", r.getCaseId());
            return null;
        }
        boolean autoBlocked = false;
        if (!llmFinalFail && "failed".equals(r.getStatus())) {
            try {
                autoBlocked = r.getValidationJson() != null
                        && !OM.readTree(r.getValidationJson()).path("allPassed").asBoolean(true);
            } catch (Exception e) {
                autoBlocked = false;   // validation_json 坏 → ②源无信号，不硬错
            }
        }
        return new TerminalCase(r.getCaseId(), r.getStatus(), llmFinalFail, autoBlocked);
    }

    // ==================== 分位数与工具 ====================

    /**
     * P50 线性插值（Excel PERCENTILE.INC 语义）：rank = 1 + p×(N−1)，
     * floor(rank) 与 ceil(rank) 之间线性插值。[24,48] p=0.5 → rank 1.5 → 36。
     */
    static double percentileInc(List<Double> sortedAsc, double p) {
        int n = sortedAsc.size();
        if (n == 0) {
            return Double.NaN;
        }
        if (n == 1) {
            return sortedAsc.get(0);
        }
        double rank = 1 + p * (n - 1);
        int lo = (int) Math.floor(rank) - 1;
        int hi = (int) Math.ceil(rank) - 1;
        if (lo == hi) {
            return sortedAsc.get(lo);
        }
        double frac = rank - Math.floor(rank);
        return sortedAsc.get(lo) + (sortedAsc.get(hi) - sortedAsc.get(lo)) * frac;
    }

    /** P90 序位口径：第 ceil(p×N) 个样本（1-based，保守取大）；N=2 → 第 2 个 = 48。 */
    static double percentileRankCeil(List<Double> sortedAsc, double p) {
        int n = sortedAsc.size();
        if (n == 0) {
            return Double.NaN;
        }
        int idx = (int) Math.min(n, Math.ceil(p * n));   // 1-based 序位
        return sortedAsc.get(idx - 1);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 小时展示：整数不带小数（36h），非整数一位小数（36.5h）。 */
    private static String formatHours(double h) {
        return h == Math.floor(h) ? String.format("%.0fh", h) : String.format("%.1fh", h);
    }

    /** 通用分批（IN 500 规约）。 */
    static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }
}
