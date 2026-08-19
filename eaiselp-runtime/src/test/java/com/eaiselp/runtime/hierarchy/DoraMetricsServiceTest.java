package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.mapper.CaseMapper;
import com.eaiselp.data.mapper.DerivationMapper;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import com.eaiselp.runtime.hierarchy.dto.DoraBoardVo;
import com.eaiselp.runtime.orchestration.OrchestrationRecord;
import com.eaiselp.runtime.orchestration.OrchestrationRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DoraMetricsService 单测（case-20260818 T14，AC-F1.1~F1.6 口径锁定，R2 自检核心）。
 *
 * <p>小样本造数逐指标断言（口径唯一权威=PRD §4.1.2、落地规则=SE §4.3）：
 * <ul>
 *   <li>DF：3 个去重 done / 30 天 = 0.1 次/天，band=high（AC-F1.1）。</li>
 *   <li>LT：样本 [24h,48h] → P50=36h（线性插值）/P90=48h（ceil(0.9N) 序位）；M=1 排除提示（AC-F1.2/F1.5）。</li>
 *   <li>CFR：3 终态 1 失败 = 33.3%；FAIL_WARN 不计；auto_check 源与防双计（analyzeTerminal 直测，AC-F1.3）。</li>
 *   <li>RT：埋点精确 45min（max PASS−min FAIL）；无埋点近似"≈"（末条 finished−首条 started，AC-F1.4/F1.5）。</li>
 *   <li>空态两档 + 参数三态 400 + 5min TTL 缓存命中不重查（AC-F1.6 / D-1）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DoraMetricsServiceTest {

    @Mock ProjectMapper projectMapper;
    @Mock ProgramMapper programMapper;
    @Mock CaseMapper caseMapper;
    @Mock GovernanceLogMapper governanceLogMapper;
    @Mock OrchestrationRecordMapper orchestrationRecordMapper;
    @Mock DerivationMapper derivationMapper;

    @InjectMocks
    DoraMetricsService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Case.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, Program.class);
        TableInfoHelper.initTableInfo(assistant, GovernanceLog.class);
        TableInfoHelper.initTableInfo(assistant, OrchestrationRecord.class);
        TableInfoHelper.initTableInfo(assistant, Derivation.class);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }

    private static Case c(String caseId, String status, LocalDateTime createTime) {
        Case c = new Case();
        c.setCaseId(caseId);
        c.setStatus(status);
        c.setCreateTime(createTime);
        return c;
    }

    private static GovernanceLog doneAudit(String caseId, LocalDateTime at) {
        GovernanceLog g = new GovernanceLog();
        g.setResourceId(caseId);
        g.setDetail("{\"targetStatus\":\"done\",\"operator\":\"qa\"}");
        g.setCreateTime(at);
        return g;
    }

    private static GovernanceLog transitAudit(String caseId, String targetStatus, LocalDateTime at) {
        GovernanceLog g = new GovernanceLog();
        g.setResourceId(caseId);
        g.setDetail("{\"targetStatus\":\"" + targetStatus + "\",\"operator\":\"qa\"}");
        g.setCreateTime(at);
        return g;
    }

    private static OrchestrationRecord orch(String caseId, String status, String stepsJson, String validationJson) {
        OrchestrationRecord r = new OrchestrationRecord();
        r.setId(1L);
        r.setCaseId(caseId);
        r.setStatus(status);
        r.setStepsJson(stepsJson);
        r.setValidationJson(validationJson);
        return r;
    }

    private static Derivation deriv(String caseId, String role, String gateResult,
                                    LocalDateTime startedAt, LocalDateTime finishedAt) {
        Derivation d = new Derivation();
        d.setId((long) (Math.random() * 100000));
        d.setCaseId(caseId);
        d.setRole(role);
        d.setGateResult(gateResult);
        d.setStartedAt(startedAt);
        d.setFinishedAt(finishedAt);
        return d;
    }

    // ==================== AC-F1.1 DF：3/30=0.1 次/天，band=high ====================

    @Test
    void DF_三个去重done_30天_0_1次每天() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                c("C1", "done", now().minusDays(40)),
                c("C2", "in_progress", now().minusDays(10)),
                c("C3", "done", now().minusDays(20)),
                c("C4", "done", now().minusDays(5))));
        // C1 幂等重放两条 done 审计 → 按 caseId 去重取最早只计一次（F-20）
        when(governanceLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                doneAudit("C1", now().minusDays(30)),
                doneAudit("C1", now().minusDays(29)),
                doneAudit("C3", now().minusDays(15)),
                doneAudit("C4", now().minusDays(2)),
                transitAudit("C2", "reviewing", now().minusDays(9))));   // 非 done 流转不计
        when(orchestrationRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(derivationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        DoraBoardVo vo = service.dora("project", 1L, 30);

        assertNull(vo.getEmptyState());
        assertNotNull(vo.getDeploymentFrequency());
        assertEquals(3, vo.getDeploymentFrequency().getSampleCount(), "去重后 3 个 done Case");
        assertEquals(0.1, vo.getDeploymentFrequency().getValue(), 1e-9, "3/30=0.1 次/天（AC-F1.1 断言值）");
        assertEquals("high", vo.getDeploymentFrequency().getBand(), "0.1 落 high 档");
        assertEquals("次/天", vo.getDeploymentFrequency().getUnit());
    }

    // ==================== AC-F1.2/F1.5 LT：P50 插值 36h / P90 序位 48h / M=1 ====================

    @Test
    void LT_样本24和48小时_P50插值36_P90序位48_排除M为1() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                c("C1", "done", now().minusHours(72)),       // doneTs-72h... done 审计在 48h 前 → LT=24h
                c("C2", "done", now().minusHours(96)),       // → LT=48h
                c("C3", "done", now().minusDays(50))));      // status=done 但无任何审计 → M=1
        when(governanceLogMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        doneAudit("C1", now().minusHours(48)),
                        doneAudit("C2", now().minusHours(48))))
                .thenReturn(List.of());                      // 第二次（全历史确认 C3 无审计）→ 空
        when(orchestrationRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(derivationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        DoraBoardVo vo = service.dora("project", 1L, 30);

        var lt = vo.getLeadTime();
        assertNotNull(lt);
        assertEquals(2, lt.getSampleCount(), "LT 样本=有 done 审计的 2 个 Case");
        assertEquals(36.0, lt.getP50Hours(), 1e-9, "P50 线性插值：[24,48] rank1.5 → 36h（AC-F1.2）");
        assertEquals(48.0, lt.getP90Hours(), 1e-9, "P90 序位：ceil(0.9×2)=2 → 48h（保守取大）");
        assertEquals("P50 36h / P90 48h", lt.getDisplay());
        assertEquals(1, lt.getExcludedCount(), "C3 无审计不可回溯 → M=1（严禁 update_time 冒充）");
        assertEquals("另有 1 条历史数据不可回溯，已排除", lt.getExclusionNote());
    }

    // ==================== AC-F1.3 CFR：1/3=33.3%，FAIL_WARN 不计 ====================

    @Test
    void CFR_三终态一失败_33_3percent_FAIL_WARN不计() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                c("C1", "done", now().minusDays(10)),
                c("C2", "done", now().minusDays(8)),
                c("C3", "done", now().minusDays(6))));
        when(governanceLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                doneAudit("C1", now().minusDays(9)),
                doneAudit("C2", now().minusDays(7)),
                doneAudit("C3", now().minusDays(5))));
        when(orchestrationRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                // C1：llm_review 终判失败（status=failed 且 gateResult=FAIL 的步骤）→ 分子
                orch("C1", "failed",
                        "[{\"role\":\"team-dev\",\"status\":\"success\"},"
                                + "{\"role\":\"team-reviewer\",\"status\":\"failed\",\"gateResult\":\"FAIL\"}]",
                        null),
                // C2：FAIL_WARN 放行不计入分子（gateResult=FAIL_WARN ≠ FAIL）
                orch("C2", "done",
                        "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"FAIL_WARN\"}]",
                        null),
                // C3：正常 PASS
                orch("C3", "done",
                        "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"PASS\"}]",
                        null)));
        // RT 样本框=done 且非 CFR 分子（C2/C3）；无派生行 → RT 空
        when(derivationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        DoraBoardVo vo = service.dora("project", 1L, 30);

        var cfr = vo.getChangeFailureRate();
        assertNotNull(cfr);
        assertEquals(3, cfr.getSampleCount(), "分母=终态编排 Case 去重 3");
        assertEquals(0.333, cfr.getValue(), 1e-9, "1/3=0.333（AC-F1.3 断言值）");
        assertEquals("33.3%", cfr.getPercentDisplay());
        assertEquals(0, cfr.getParseErrorCount());
        assertEquals("门禁终判失败口径（代理指标）", cfr.getProxyNote());
    }

    /** analyzeTerminal 直测：auto_check 阻断源（编排 failed + allPassed=false + 无①）与两源防双计。 */
    @Test
    void CFR_analyzeTerminal_两源防双计() {
        // ① llm_review 终判 FAIL → 分子；同记录 validation allPassed=false 也不双计（②被①抑制）
        var llmFail = DoraMetricsService.analyzeTerminal(orch("C1", "failed",
                "[{\"role\":\"team-reviewer\",\"status\":\"failed\",\"gateResult\":\"FAIL\"}]",
                "{\"allPassed\":false}"));
        assertTrue(llmFail.llmFinalFail());
        assertFalse(llmFail.autoCheckBlocked(), "两源合一防双计：有①时②不叠加");

        // ② auto_check 阻断：编排 failed + validation allPassed=false + 无①信号
        var autoBlocked = DoraMetricsService.analyzeTerminal(orch("C2", "failed",
                "[{\"role\":\"team-dev\",\"status\":\"success\"}]",
                "{\"allPassed\":false}"));
        assertFalse(autoBlocked.llmFinalFail());
        assertTrue(autoBlocked.autoCheckBlocked(), "auto_check 阻断进分子");

        // 编排 done + allPassed=false：非阻断（终判成功，验证失败仅记录）
        var doneButInvalid = DoraMetricsService.analyzeTerminal(orch("C3", "done",
                "[{\"role\":\"team-dev\",\"status\":\"success\"}]",
                "{\"allPassed\":false}"));
        assertFalse(doneButInvalid.llmFinalFail());
        assertFalse(doneButInvalid.autoCheckBlocked(), "编排 done 不进 auto_check 源");

        // FAIL_WARN：不算失败信号（Y2 后分子信号源=t_derivation，steps_json 仅保留终判①源判定）
        var warn = DoraMetricsService.analyzeTerminal(orch("C4", "done",
                "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"FAIL_WARN\"}]",
                null));
        assertFalse(warn.llmFinalFail(), "FAIL_WARN 不计（gateResult=FAIL 才计）");

        // steps_json 坏 → null（调用方计 parseErrorCount，单卡降级不 500）
        assertNull(DoraMetricsService.analyzeTerminal(orch("C5", "done", "不是JSON", null)));
    }

    // ==================== AC-F1.4/F1.5 RT：埋点精确 45min + 无埋点≈近似 ====================

    @Test
    void RT_埋点精确45min_无埋点近似角标() {
        LocalDateTime base = now().minusDays(3);
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                c("C1", "done", now().minusDays(10)),
                c("C2", "done", now().minusDays(8)),
                c("C3", "failed", now().minusDays(6))));   // 终判失败 → 不入 RT 样本框
        when(governanceLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                doneAudit("C1", now().minusDays(9)),
                doneAudit("C2", now().minusDays(7)),
                doneAudit("C3", now().minusDays(5))));
        when(orchestrationRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                orch("C1", "done", "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"PASS\"}]", null),
                orch("C2", "done", "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"PASS\"}]", null),
                orch("C3", "failed",
                        "[{\"role\":\"team-reviewer\",\"status\":\"failed\",\"gateResult\":\"FAIL\"}]", null)));
        // C1 有埋点：FAIL@10:00 → PASS@10:45 = 45min 精确；C2 无埋点两行：09:00→11:00 = 120min 近似
        when(derivationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                deriv("C1", "team-reviewer", "FAIL", base.withHour(10).withMinute(0), base.withHour(10).withMinute(0)),
                deriv("C1", "team-reviewer", "PASS", null, base.withHour(10).withMinute(45)),
                deriv("C2", "team-reviewer", null, base.withHour(9).withMinute(0), base.withHour(10).withMinute(0)),
                deriv("C2", "team-reviewer", null, base.withHour(10).withMinute(0), base.withHour(11).withMinute(0))));

        DoraBoardVo vo = service.dora("project", 1L, 30);

        var rt = vo.getTimeToRestore();
        assertNotNull(rt);
        assertEquals(2, rt.getSampleCount(), "RT 样本=C1(精确)+C2(近似)；C3 终判失败排除");
        assertEquals(1, rt.getApproximateCount(), "C2 无埋点 → 近似样本 1 条（'≈'角标数据源）");
        // 样本 [45, 120]：P50 插值 82.5 → 83；均值 82.5 → 83
        assertEquals(83, rt.getP50Minutes());
        assertEquals(83, rt.getAvgMinutes());
    }

    /** RT 埋点口径单值断言：仅一 Case（AC-F1.4 的 45min 标准场景）。 */
    @Test
    void RT_单样本45分钟() {
        LocalDateTime base = now().minusDays(2);
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(c("C1", "done", now().minusDays(5))));
        when(governanceLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(doneAudit("C1", now().minusDays(4))));
        when(orchestrationRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                orch("C1", "done", "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"PASS\"}]", null)));
        when(derivationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                deriv("C1", "team-reviewer", "FAIL", base.withHour(10).withMinute(0), base.withHour(10).withMinute(0)),
                deriv("C1", "team-reviewer", "PASS", null, base.withHour(10).withMinute(45))));

        var rt = service.dora("project", 1L, 30).getTimeToRestore();

        assertEquals(1, rt.getSampleCount());
        assertEquals(45, rt.getP50Minutes(), "10:45−10:00=45min（AC-F1.4 断言值）");
        assertEquals(45, rt.getAvgMinutes());
        assertEquals(0, rt.getApproximateCount(), "有埋点不近似");
    }

    /**
     * Y5 口径锁定：无埋点多行但 started_at=NULL（编排路径派生行——engine 同步 persist 无字段可写）
     * → 整组静默排除：不入样本、也不计 approximateCount（近似分支只服务 createPending 写入
     * started_at 的独立异步派生行）。
     */
    @Test
    void RT_无埋点且startedAt为NULL_编排口径整组排除() {
        LocalDateTime base = now().minusDays(2);
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(c("C1", "done", now().minusDays(5))));
        when(governanceLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(doneAudit("C1", now().minusDays(4))));
        when(orchestrationRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                orch("C1", "done", "[{\"role\":\"team-dev\",\"status\":\"success\"}]", null)));
        // 两行均无埋点且 started_at=NULL（编排路径落库形态）→ 不近似
        when(derivationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                deriv("C1", "team-dev", null, null, base.withHour(9).withMinute(0)),
                deriv("C1", "team-dev", null, null, base.withHour(11).withMinute(0))));

        var rt = service.dora("project", 1L, 30).getTimeToRestore();

        assertEquals(0, rt.getSampleCount(), "started_at=NULL 的无埋点组整组排除");
        assertEquals(0, rt.getApproximateCount(), "排除不计近似数（'≈'角标数据源不含此类）");
        assertNull(rt.getP50Minutes());
        assertNull(rt.getAvgMinutes());
    }

    // ==================== Y2 打回率：分子信号源=t_derivation.gate_result='FAIL' ====================

    /**
     * Y2 回归锁定：打回重跑后 steps_json 被终轮 PASS 覆盖（旧信号源恒 0 的现场），
     * 但 t_derivation 留有历史 FAIL 行 → 打回率分子仍应计该 Case（1/2=0.5）。
     */
    @Test
    void 打回率_分子走t_derivation的FAIL行_重跑覆盖stepsJson不丢信号() {
        LocalDateTime base = now().minusDays(3);
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                c("C1", "done", now().minusDays(10)),
                c("C2", "done", now().minusDays(8))));
        when(governanceLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                doneAudit("C1", now().minusDays(9)),
                doneAudit("C2", now().minusDays(7))));
        when(orchestrationRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                // C1：曾被打回（历史 FAIL），重跑通过后 steps_json 只剩终轮 PASS——旧口径读它恒 0
                orch("C1", "done",
                        "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"PASS\"}]", null),
                // C2：正常一次通过
                orch("C2", "done",
                        "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"PASS\"}]", null)));
        // 两次派生查询按调用序 stub：⑦ RT 查询回全量行；⑧ 打回分子查询（DB 侧 eq gate_result='FAIL'）
        // 只会回 FAIL 行——mock 不解析 wrapper，由 stub 顺序模拟 DB 过滤语义
        when(derivationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                deriv("C1", "team-reviewer", "FAIL", base.withHour(9).withMinute(0), base.withHour(9).withMinute(30)),
                deriv("C1", "team-reviewer", "PASS", null, base.withHour(10).withMinute(15)),
                deriv("C2", "team-reviewer", "PASS", base.withHour(9).withMinute(0), base.withHour(9).withMinute(30)))
        ).thenReturn(List.of(
                deriv("C1", "team-reviewer", "FAIL", base.withHour(9).withMinute(0), base.withHour(9).withMinute(30))));

        var rework = service.dora("project", 1L, 30).getGateReworkRate();

        assertNotNull(rework);
        assertEquals(0.5, rework.getValue(), 1e-9, "1/2：C1 有历史 FAIL 派生行 → 计入打回分子（Y2 修复）");
        assertEquals("门禁打回率（参考值，非四指标）", rework.getNote());
    }

    /** Y2 反向锁定：终判失败 Case（CFR 分子）即使有 FAIL 派生行也不重复计入打回分子。 */
    @Test
    void 打回率_CFR终判失败不重复计入分子() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                c("C1", "failed", now().minusDays(10)),
                c("C2", "done", now().minusDays(8))));
        when(governanceLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                doneAudit("C1", now().minusDays(9)),
                doneAudit("C2", now().minusDays(7))));
        when(orchestrationRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                // C1：门禁终判失败 → CFR 分子
                orch("C1", "failed",
                        "[{\"role\":\"team-reviewer\",\"status\":\"failed\",\"gateResult\":\"FAIL\"}]", null),
                orch("C2", "done",
                        "[{\"role\":\"team-reviewer\",\"status\":\"success\",\"gateResult\":\"PASS\"}]", null)));
        // ⑦ RT 查询回全量行；⑧ 打回分子查询只回 FAIL 行（C1）——但 C1 已是 CFR 分子被排除
        when(derivationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                deriv("C1", "team-reviewer", "FAIL", null, now().minusDays(9)),
                deriv("C2", "team-reviewer", "PASS", null, now().minusDays(7))))
        .thenReturn(List.of(
                deriv("C1", "team-reviewer", "FAIL", null, now().minusDays(9))));

        var rework = service.dora("project", 1L, 30).getGateReworkRate();

        // C1 已是 CFR 分子被排除、C2 无 FAIL 行 → 分子 0（终判失败≠打回）
        assertEquals(0.0, rework.getValue(), 1e-9, "终判失败 Case 不计入打回分子");
    }

    // ==================== AC-F1.6 空态两档 ====================

    @Test
    void 空态_无项目_先创建项目并关联Case() {
        when(projectMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        DoraBoardVo vo = service.dora("all", null, 30);

        assertEquals("先创建项目并关联 Case", vo.getEmptyState());
        assertNull(vo.getDeploymentFrequency());
        assertNull(vo.getLeadTime());
        assertNull(vo.getChangeFailureRate());
        assertNull(vo.getTimeToRestore());
        assertNull(vo.getGateReworkRate());
    }

    @Test
    void 空态_有项目无Case_暂无统计数据() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        DoraBoardVo vo = service.dora("project", 1L, 30);

        assertEquals("暂无统计数据，完成 Case 后自动生成", vo.getEmptyState());
        assertNull(vo.getDeploymentFrequency());
    }

    /** project_id 为空的 Case 不入池：IN 条件天然排除（AC-F1.6 的 C9）。 */
    @Test
    void C9_未关联项目的Case不入统计() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        // caseMapper 只会收到 project_id IN (1) 的查询——模拟返回空（C9 的 Case 根本查不回来）
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        DoraBoardVo vo = service.dora("project", 1L, 30);
        assertEquals("暂无统计数据，完成 Case 后自动生成", vo.getEmptyState(), "无关联 Case → 空态");
    }

    // ==================== 参数三态校验（api-contracts §1 错误表） ====================

    @Test
    void 参数校验_400() {
        assertEquals(400, assertThrows(BizException.class, () -> service.dora("tenant", null, 30)).getCode());
        assertEquals("scope 非法，应为 project/program/all",
                assertThrows(BizException.class, () -> service.dora("tenant", null, 30)).getMessage());
        assertEquals("scope=project/program 时 scopeId 必填",
                assertThrows(BizException.class, () -> service.dora("project", null, 30)).getMessage());
        assertEquals("periodDays 非法，应为 7/30/90",
                assertThrows(BizException.class, () -> service.dora("all", null, 15)).getMessage());
        // 404：scopeId 不存在
        assertEquals(404, assertThrows(BizException.class, () -> service.dora("project", 999L, 30)).getCode());
    }

    // ==================== D-1 缓存：TTL 内命中不重查 ====================

    @Test
    void 缓存_命中不重查() {
        when(projectMapper.selectList(any(Wrapper.class))).thenReturn(List.of(project(1L)));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        DoraBoardVo first = service.dora("all", null, 30);
        DoraBoardVo second = service.dora("all", null, 30);

        assertSame(first, second, "TTL 300s 内返回同一缓存实例");
        verify(caseMapper, times(1)).selectList(any(Wrapper.class));
        verify(projectMapper, times(1)).selectList(any(Wrapper.class));
    }

    /** 缓存 key 四元组隔离：不同 periodDays 各自计算。 */
    @Test
    void 缓存_key四元组隔离() {
        when(projectMapper.selectList(any(Wrapper.class))).thenReturn(List.of(project(1L)));
        when(caseMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.dora("all", null, 30);
        service.dora("all", null, 7);

        verify(caseMapper, times(2)).selectList(any(Wrapper.class));
    }

    // ==================== 分位数纯函数（口径锁死） ====================

    @Test
    void 分位数_PERCENTILE_INC与序位口径() {
        assertEquals(36.0, DoraMetricsService.percentileInc(List.of(24.0, 48.0), 0.5), 1e-9);
        assertEquals(24.0, DoraMetricsService.percentileInc(List.of(24.0), 0.5), 1e-9);
        assertEquals(48.0, DoraMetricsService.percentileRankCeil(List.of(24.0, 48.0), 0.9), 1e-9);
        assertEquals(24.0, DoraMetricsService.percentileRankCeil(List.of(24.0), 0.9), 1e-9);
        // 三样本插值：[10,20,30] P50=20；P90 序位 ceil(2.7)=3 → 30
        assertEquals(20.0, DoraMetricsService.percentileInc(List.of(10.0, 20.0, 30.0), 0.5), 1e-9);
        assertEquals(30.0, DoraMetricsService.percentileRankCeil(List.of(10.0, 20.0, 30.0), 0.9), 1e-9);
    }

    /** 分批工具：IN 500 规约。 */
    @Test
    void 分批_500一组() {
        List<Integer> big = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            big.add(i);
        }
        List<List<Integer>> parts = DoraMetricsService.partition(big, 500);
        assertEquals(3, parts.size());
        assertEquals(500, parts.get(0).size());
        assertEquals(200, parts.get(2).size());
    }

    private static Project project(Long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("项目" + id);
        p.setStatus("in_progress");
        return p;
    }
}
