package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.governance.dto.BusinessCaseVo;
import com.eaiselp.runtime.governance.dto.PortfolioVo;
import com.eaiselp.runtime.hierarchy.Strategy;
import com.eaiselp.runtime.hierarchy.StrategyMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BusinessCaseServiceImpl 单测（case-20260821 T4/T11/T13，SE §9.1 锚点 12/13/14/15/16/20——
 * AC-F2.1~F2.8 + F2.10~F2.12；纯 Mockito，StandardServiceImplTest 先例）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessCaseServiceImplTest {

    @Mock BusinessCaseMapper baseMapper;
    @Mock StrategyMapper strategyMapper;
    @Mock AuditService auditService;

    @InjectMocks BusinessCaseServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BusinessCase.class);
    }

    @BeforeEach
    void injectBaseMapper() throws Exception {
        java.lang.reflect.Field f = BusinessCaseServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    // ==================== 构造工具 ====================

    /** PRD §4.4.1 构造值：{成本100, 运营20, 收益60, R5,I3,C0.8,E6} → {净40, 2.5, 20.00, 2.00}。 */
    private static BusinessCase prdCase() {
        BusinessCase c = new BusinessCase();
        c.setCaseName("数据中台建设");
        c.setOnetimeCost(new BigDecimal("100"));
        c.setAnnualOpCost(new BigDecimal("20"));
        c.setAnnualBenefit(new BigDecimal("60"));
        c.setReach(new BigDecimal("5"));
        c.setImpact(new BigDecimal("3"));
        c.setConfidence(new BigDecimal("0.8"));
        c.setEffort(new BigDecimal("6"));
        return c;
    }

    private static BusinessCase stored(Long id, String status) {
        BusinessCase c = prdCase();
        c.setId(id);
        c.setStatus(status);
        c.setNetBenefit(new BigDecimal("40"));
        c.setPaybackYears(new BigDecimal("2.5"));
        c.setRoiPercent(new BigDecimal("20.00"));
        c.setRiceScore(new BigDecimal("2.00"));
        return c;
    }

    private void stubInsertOk() {
        when(baseMapper.insert(any(BusinessCase.class))).thenAnswer(inv -> {
            BusinessCase c = inv.getArgument(0);
            c.setId(5000L);
            return 1;
        });
    }

    private void stubUpdateOk() {
        when(baseMapper.update(any(), any())).thenReturn(1);
    }

    // ==================== 锚点 12：uk 与计算字段回归（AC-F2.1） ====================

    @Test
    void 同名创建被拒_uk冲突统一形态() {
        when(baseMapper.insert(any(BusinessCase.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_bizcase_tenant_name"));

        BizException ex = assertThrows(BizException.class, () -> service.create(prdCase()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
        assertTrue(ex.getMessage().contains("数据中台建设"));
        verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 创建构造值回归_净40回收2点5ROI20点00RICE2点00() {
        stubInsertOk();

        BusinessCase created = service.create(prdCase());

        ArgumentCaptor<BusinessCase> captor = ArgumentCaptor.forClass(BusinessCase.class);
        verify(baseMapper).insert(captor.capture());
        BusinessCase saved = captor.getValue();
        assertEquals(0, saved.getNetBenefit().compareTo(new BigDecimal("40")), "net=60−20=40");
        assertEquals(0, saved.getPaybackYears().compareTo(new BigDecimal("2.5")), "100÷40=2.5");
        assertEquals(0, saved.getRoiPercent().compareTo(new BigDecimal("20.00")), "（40×3−100)/100=20.00%");
        assertEquals(0, saved.getRiceScore().compareTo(new BigDecimal("2.00")), "5×3×0.8÷6=2.00");
        assertEquals("draft", saved.getStatus(), "创建固定 draft");
        assertNull(saved.getRejectedReason());
        assertNull(saved.getDecisionNote());
        // 详情回显同值（toVo）
        BusinessCaseVo vo = service.toVo(created);
        assertEquals(0, vo.getRoiPercent().compareTo(new BigDecimal("20.00")));
        assertEquals(0, vo.getRiceScore().compareTo(new BigDecimal("2.00")));
        verify(auditService).log(eq("bizcase_create"), eq("bizcase"), anyString(), anyString());
    }

    @Test
    void 双N语义_净0回收null_零成本回收0点0且ROInull() {
        stubInsertOk();
        // {成本100, 收益20/运营20（净0）} → payback null；{成本0, 净30} → payback 0.0 + roi null
        BusinessCase net0 = prdCase();
        net0.setAnnualBenefit(new BigDecimal("20"));
        service.create(net0);
        ArgumentCaptor<BusinessCase> c1 = ArgumentCaptor.forClass(BusinessCase.class);
        verify(baseMapper, times(1)).insert(c1.capture());
        assertNull(c1.getValue().getPaybackYears(), "净 0 → N/A（不可投）");
        assertEquals(0, c1.getValue().getRoiPercent().compareTo(new BigDecimal("-100.00")),
                "净 0 → (0×3−100)/100=−100.00 负 ROI 合法");

        BusinessCase zeroCost = prdCase();
        zeroCost.setOnetimeCost(BigDecimal.ZERO);
        zeroCost.setAnnualBenefit(new BigDecimal("50"));
        service.create(zeroCost);
        ArgumentCaptor<BusinessCase> c2 = ArgumentCaptor.forClass(BusinessCase.class);
        verify(baseMapper, times(2)).insert(c2.capture());
        assertEquals(0, c2.getValue().getPaybackYears().compareTo(new BigDecimal("0.0")),
                "onetime=0 且净>0 → 0.0（零成本）非 null");
        assertNull(c2.getValue().getRoiPercent(), "onetime=0 → ROI N/A（除零防御）");
    }

    @Test
    void 金额负值_三字段均400() {
        List<java.util.function.BiConsumer<BusinessCase, BigDecimal>> amountSetters = List.of(
                (c, v) -> c.setOnetimeCost(v), (c, v) -> c.setAnnualOpCost(v), (c, v) -> c.setAnnualBenefit(v));
        for (java.util.function.BiConsumer<BusinessCase, BigDecimal> setter : amountSetters) {
            BusinessCase c = prdCase();
            setter.accept(c, new BigDecimal("-1"));
            BizException ex = assertThrows(BizException.class, () -> service.create(c));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("负"));
        }
    }

    @Test
    void RICE因子与confidence边界_0_11_1点5_非步进均400() {
        List<java.util.function.BiConsumer<BusinessCase, BigDecimal>> factorSetters = List.of(
                (c, v) -> c.setReach(v), (c, v) -> c.setImpact(v), (c, v) -> c.setEffort(v));
        for (String bad : new String[]{"0", "11", "1.5"}) {
            for (java.util.function.BiConsumer<BusinessCase, BigDecimal> setter : factorSetters) {
                BusinessCase c = prdCase();
                setter.accept(c, new BigDecimal(bad));
                assertEquals(400, assertThrows(BizException.class, () -> service.create(c)).getCode(),
                        "factor=" + bad);
            }
        }
        for (String bad : new String[]{"0.05", "0.15", "0.85", "0", "1.1"}) {
            BusinessCase c = prdCase();
            c.setConfidence(new BigDecimal(bad));
            BizException ex = assertThrows(BizException.class, () -> service.create(c));
            assertEquals(400, ex.getCode(), "confidence=" + bad);
            assertTrue(ex.getMessage().contains("confidence"), "confidence=" + bad);
        }
    }

    @Test
    void confidence十档全过() {
        stubInsertOk();
        for (int i = 1; i <= 10; i++) {
            BusinessCase c = prdCase();
            c.setConfidence(BigDecimal.valueOf(i, 1));
            assertDoesNotThrow(() -> service.create(c), "c=0." + i);
        }
    }

    // ==================== 锚点 13：状态机全路径（AC-F2.6） ====================

    @Test
    void 主链_draft到approved到executing到done() {
        stubUpdateOk();
        long id = 5010L;
        // draft→approved
        when(baseMapper.selectById(id)).thenReturn(stored(id, "draft"), stored(id, "approved"));
        assertEquals("approved", service.transit(id, "approved", null).getStatus());
        // approved→executing
        when(baseMapper.selectById(id)).thenReturn(stored(id, "approved"), stored(id, "executing"));
        assertEquals("executing", service.transit(id, "executing", null).getStatus());
        // executing→done
        when(baseMapper.selectById(id)).thenReturn(stored(id, "executing"), stored(id, "done"));
        assertEquals("done", service.transit(id, "done", null).getStatus());
        verify(auditService, times(3)).log(eq("bizcase_transit"), eq("bizcase"), anyString(), anyString());
    }

    @Test
    void draft直接到executing_跳级400() {
        when(baseMapper.selectById(5011L)).thenReturn(stored(5011L, "draft"));
        BizException ex = assertThrows(BizException.class, () -> service.transit(5011L, "executing", null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("跳级"));
        verify(baseMapper, never()).update(any(), any());
    }

    @Test
    void approved到rejected_撤销400() {
        when(baseMapper.selectById(5012L)).thenReturn(stored(5012L, "approved"));
        assertEquals(400, assertThrows(BizException.class,
                () -> service.transit(5012L, "rejected", "原因")).getCode());
    }

    @Test
    void 终态出边_400_rejected与done() {
        when(baseMapper.selectById(5013L)).thenReturn(stored(5013L, "rejected"));
        for (String target : new String[]{"draft", "approved", "executing", "done"}) {
            assertEquals(400, assertThrows(BizException.class,
                    () -> service.transit(5013L, target, null)).getCode(), "rejected→" + target);
        }
        when(baseMapper.selectById(5014L)).thenReturn(stored(5014L, "done"));
        assertEquals(400, assertThrows(BizException.class,
                () -> service.transit(5014L, "draft", null)).getCode(), "done→draft");
    }

    @Test
    void draft到rejected_必填原因_缺400_带则200且落库() {
        stubUpdateOk();
        when(baseMapper.selectById(5015L)).thenReturn(stored(5015L, "draft"));

        BizException ex = assertThrows(BizException.class, () -> service.transit(5015L, "rejected", " "));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("rejectedReason"));

        when(baseMapper.selectById(5015L)).thenReturn(stored(5015L, "draft"), stored(5015L, "rejected"));
        assertEquals("rejected", service.transit(5015L, "rejected", "收益假设不成立").getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<BusinessCase>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(baseMapper).update(any(), cap.capture());
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("收益假设不成立"), "rejected_reason 落库");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("bizcase_transit"), eq("bizcase"), eq("5015"), detail.capture());
        assertTrue(detail.getValue().contains("收益假设不成立"), "审计含 rejectedReason（AC-F2.6）");
    }

    @Test
    void 自流转幂等_不更新不审计() {
        when(baseMapper.selectById(5016L)).thenReturn(stored(5016L, "draft"));
        service.transit(5016L, "draft", null);
        verify(baseMapper, never()).update(any(), any());
        verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    // ==================== 锚点 14：编辑与删除限制（AC-F2.7） ====================

    @Test
    void approved改输入_400已批准不可改() {
        when(baseMapper.selectById(5020L)).thenReturn(stored(5020L, "approved"));
        BizException ex = assertThrows(BizException.class, () -> service.edit(5020L, prdCase()));
        assertEquals(400, ex.getCode());
        assertEquals("已批准，输入不可改，请复盘或新建案例（决策记录请走 decision-note 端点）", ex.getMessage());
        verify(baseMapper, never()).update(any(), any());
    }

    @Test
    void rejected与done编辑_400终态只读() {
        when(baseMapper.selectById(5021L)).thenReturn(stored(5021L, "rejected"));
        assertEquals(400, assertThrows(BizException.class, () -> service.edit(5021L, prdCase())).getCode());
        when(baseMapper.selectById(5022L)).thenReturn(stored(5022L, "done"));
        assertEquals(400, assertThrows(BizException.class, () -> service.edit(5022L, prdCase())).getCode());
    }

    @Test
    void executing改输入_400同approved口径() {
        when(baseMapper.selectById(5023L)).thenReturn(stored(5023L, "executing"));
        assertEquals(400, assertThrows(BizException.class, () -> service.edit(5023L, prdCase())).getCode());
    }

    @Test
    void draft编辑_200且计算字段重算() {
        when(baseMapper.selectById(5024L)).thenReturn(stored(5024L, "draft"), stored(5024L, "draft"));
        stubUpdateOk();

        BusinessCase patch = prdCase();
        patch.setOnetimeCost(new BigDecimal("100"));
        patch.setAnnualBenefit(new BigDecimal("30"));   // 净 10 → payback 100/10=10.0；roi (10×3−100)/100=−70.00
        service.edit(5024L, patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<BusinessCase>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(baseMapper).update(any(), cap.capture());
        java.util.Map<String, Object> params = cap.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue(new BigDecimal("10.0")), "重算 payback=10.0，实际: " + params);
        assertTrue(params.containsValue(new BigDecimal("-70.00")), "重算 roi=−70.00");
        verify(auditService).log(eq("bizcase_update"), eq("bizcase"), eq("5024"), anyString());
    }

    @Test
    void 决策记录更新_approved可200_审计旧值到新值() {
        when(baseMapper.selectById(5025L)).thenReturn(stored(5025L, "approved"), stored(5025L, "approved"));
        stubUpdateOk();

        BusinessCaseVo vo = service.updateDecisionNote(5025L, "Q4 启动");
        assertNotNull(vo);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("bizcase_decision_note"), eq("bizcase"), eq("5025"), detail.capture());
        assertTrue(detail.getValue().contains("Q4 启动"));
        assertTrue(detail.getValue().contains("newDecisionNote"), "detail 含旧值→新值（AC-AUDIT.1）");
    }

    @Test
    void 决策记录更新_终态400_空note400() {
        when(baseMapper.selectById(5026L)).thenReturn(stored(5026L, "rejected"));
        assertEquals(400, assertThrows(BizException.class,
                () -> service.updateDecisionNote(5026L, "x")).getCode());
        when(baseMapper.selectById(5027L)).thenReturn(stored(5027L, "done"));
        assertEquals(400, assertThrows(BizException.class,
                () -> service.updateDecisionNote(5027L, "x")).getCode());
        assertEquals(400, assertThrows(BizException.class,
                () -> service.updateDecisionNote(5027L, " ")).getCode());
    }

    @Test
    void 删除_draft成功_非draft被拒() {
        when(baseMapper.selectById(5028L)).thenReturn(stored(5028L, "draft"));
        when(baseMapper.deleteById(any(BusinessCase.class))).thenReturn(1);
        assertDoesNotThrow(() -> service.remove(5028L));
        verify(auditService).log(eq("bizcase_delete"), eq("bizcase"), eq("5028"), anyString());

        when(baseMapper.selectById(5029L)).thenReturn(stored(5029L, "approved"));
        BizException ex = assertThrows(BizException.class, () -> service.remove(5029L));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("draft"));
    }

    @Test
    void 不存在或跨租户_404() {
        when(baseMapper.selectById(9999L)).thenReturn(null);
        assertEquals(404, assertThrows(BizException.class, () -> service.loadOr404(9999L)).getCode());
    }

    // ==================== 锚点 15：关联战略（AC-F2.8） ====================

    @Test
    void 关联战略_存id回显标题_无效id400_空数组合法() {
        stubInsertOk();
        Strategy s1 = new Strategy();
        s1.setId(1L);
        s1.setTitle("数字化转型");
        Strategy s2 = new Strategy();
        s2.setId(5L);
        s2.setTitle("降本增效");
        when(strategyMapper.selectById(1L)).thenReturn(s1);
        when(strategyMapper.selectById(5L)).thenReturn(s2);

        // 存 id → 成功且详情回显标题
        BusinessCase c = prdCase();
        c.setRelatedStrategyIds("[1,5]");
        service.create(c);
        when(baseMapper.selectById(5000L)).thenReturn(c);
        BusinessCaseVo vo = service.detailVo(5000L);
        assertEquals(2, vo.getRelatedStrategies().size());
        assertTrue(vo.getRelatedStrategies().stream().anyMatch(s -> "数字化转型".equals(s.getTitle())
                && !s.isDeleted()));

        // 无效 id → 400 指名
        BusinessCase bad = prdCase();
        bad.setRelatedStrategyIds("[999]");
        BizException ex = assertThrows(BizException.class, () -> service.create(bad));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("999"));

        // 空数组 → 合法
        BusinessCase empty = prdCase();
        empty.setRelatedStrategyIds("[]");
        assertDoesNotThrow(() -> service.create(empty));
    }

    @Test
    void 战略逻辑删_详情deleted占位_计算流转不受影响() {
        when(strategyMapper.selectById(1L)).thenReturn(null);   // 逻辑删 → selectById null
        BusinessCase c = stored(5030L, "draft");
        c.setRelatedStrategyIds("[1]");
        when(baseMapper.selectById(5030L)).thenReturn(c);

        BusinessCaseVo vo = assertDoesNotThrow(() -> service.detailVo(5030L), "战略逻辑删不 400");
        assertEquals(1, vo.getRelatedStrategies().size());
        assertTrue(vo.getRelatedStrategies().get(0).isDeleted(), "deleted=true 占位（AC-F2.8）");
        assertEquals(0, vo.getRiceScore().compareTo(new BigDecimal("2.00")), "计算字段不受影响");
    }

    @Test
    void strategyId筛选_JSON内存过滤命中() {
        BusinessCase hit = stored(5031L, "draft");
        hit.setRelatedStrategyIds("[1,5]");
        BusinessCase miss = stored(5032L, "draft");
        miss.setRelatedStrategyIds("[7]");
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<BusinessCase> p = inv.getArgument(0);
            p.setRecords(List.of(hit, miss));
            p.setTotal(2);
            return p;
        });

        var page = service.pageFilter(null, 1L, null, 1, 20);
        assertEquals(1, page.getRecords().size(), "仅命中含战略 1 的案例（分页后内存过滤，D-8）");
        assertEquals(5031L, page.getRecords().get(0).getId());
    }

    // ==================== 锚点 16：防伪造（AC-F2.4 Then） ====================

    @Test
    void 提交伪造riceScore999_被重算覆盖() {
        stubInsertOk();
        BusinessCase forged = prdCase();
        forged.setRiceScore(new BigDecimal("999"));    // 模拟伪造值直塞实体（无绑定入口时同样恒被覆盖）
        forged.setPaybackYears(new BigDecimal("88.8"));
        service.create(forged);

        ArgumentCaptor<BusinessCase> captor = ArgumentCaptor.forClass(BusinessCase.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getRiceScore().compareTo(new BigDecimal("2.00")),
                "库值恒=公式值 2.00，伪造 999 被重算覆盖");
        assertEquals(0, captor.getValue().getPaybackYears().compareTo(new BigDecimal("2.5")));
    }

    // ==================== 锚点 20：投资组合聚合（AC-F2.10/F2.11/F2.12） ====================

    @Test
    void 组合视图_排序投资口径与全量分布双断言() {
        // AC-F2.10：RICE 100→50→2 降序（DB 按 rice_score DESC, id DESC 返回）
        BusinessCase r100 = stored(5040L, "approved");
        r100.setRiceScore(new BigDecimal("100.00"));
        r100.setOnetimeCost(new BigDecimal("100"));
        r100.setAnnualOpCost(new BigDecimal("20"));
        r100.setNetBenefit(new BigDecimal("40"));
        BusinessCase r50 = stored(5041L, "draft");
        r50.setRiceScore(new BigDecimal("50.00"));
        r50.setOnetimeCost(new BigDecimal("10"));
        r50.setNetBenefit(new BigDecimal("5"));
        BusinessCase r2 = stored(5042L, "rejected");
        r2.setRiceScore(new BigDecimal("2.00"));
        r2.setOnetimeCost(new BigDecimal("999"));
        r2.setNetBenefit(new BigDecimal("999"));
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<BusinessCase> p = inv.getArgument(0);
            p.setRecords(List.of(r100, r50, r2));
            p.setTotal(3);
            return p;
        });
        // AC-F2.11：投资口径（approved/executing/done）——draft{10,5} 与 rejected{999,999} 不计
        PortfolioVo.Summary summary = new PortfolioVo.Summary();
        summary.setTotalOnetimeCost(new BigDecimal("100"));
        summary.setTotalAnnualOpCost(new BigDecimal("20"));
        summary.setTotalAnnualNetBenefit(new BigDecimal("40"));
        when(baseMapper.selectInvestmentSummary()).thenReturn(summary);
        // AC-F2.12：全量五态分布
        PortfolioVo.StatusCount d1 = new PortfolioVo.StatusCount();
        d1.setStatus("draft");
        d1.setCnt(1L);
        PortfolioVo.StatusCount d2 = new PortfolioVo.StatusCount();
        d2.setStatus("approved");
        d2.setCnt(1L);
        PortfolioVo.StatusCount d3 = new PortfolioVo.StatusCount();
        d3.setStatus("rejected");
        d3.setCnt(1L);
        when(baseMapper.selectStatusDistribution()).thenReturn(List.of(d1, d2, d3));

        PortfolioVo vo = service.portfolio(1, 20);

        // AC-F2.10：全量清单含 rejected/draft，RICE 降序
        assertEquals(3, vo.getCases().getRecords().size(), "cases 全量（含 rejected/done）");
        assertEquals(0, vo.getCases().getRecords().get(0).getRiceScore().compareTo(new BigDecimal("100.00")));
        assertEquals(0, vo.getCases().getRecords().get(1).getRiceScore().compareTo(new BigDecimal("50.00")));
        assertEquals(0, vo.getCases().getRecords().get(2).getRiceScore().compareTo(new BigDecimal("2.00")));
        // 排序口径钉死在查询 wrapper（rice_score DESC, id DESC）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<BusinessCase>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(), w.capture());
        String sql = w.getValue().getSqlSegment().toLowerCase();
        assertTrue(sql.contains("rice_score desc"), "默认排序 rice_score DESC，实际: " + sql);
        assertTrue(sql.contains("id desc"));

        // AC-F2.11：投资口径四项——总投入 100、净 40、3 年 120（draft/rejected 不计）
        assertEquals(0, vo.getSummary().getTotalOnetimeCost().compareTo(new BigDecimal("100")));
        assertEquals(0, vo.getSummary().getTotalAnnualOpCost().compareTo(new BigDecimal("20")));
        assertEquals(0, vo.getSummary().getTotalAnnualNetBenefit().compareTo(new BigDecimal("40")));
        assertEquals(0, vo.getSummary().getTotalThreeYearNetBenefit().compareTo(new BigDecimal("120")),
                "3 年净收益 = 3×Σnet = 120");

        // AC-F2.12：状态分布全量五态（与汇总口径有意不同——同用例双断言）
        assertEquals(1L, vo.getStatusDistribution().get("draft"));
        assertEquals(1L, vo.getStatusDistribution().get("approved"));
        assertEquals(1L, vo.getStatusDistribution().get("rejected"));
        assertEquals(0L, vo.getStatusDistribution().get("executing"));
        assertEquals(0L, vo.getStatusDistribution().get("done"));
    }

    @Test
    void 组合视图_空集COALESCE零_3年恒等于0() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<BusinessCase> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });
        PortfolioVo.Summary empty = new PortfolioVo.Summary();
        empty.setTotalOnetimeCost(BigDecimal.ZERO);
        empty.setTotalAnnualOpCost(BigDecimal.ZERO);
        empty.setTotalAnnualNetBenefit(BigDecimal.ZERO);
        when(baseMapper.selectInvestmentSummary()).thenReturn(empty);
        when(baseMapper.selectStatusDistribution()).thenReturn(List.of());

        PortfolioVo vo = service.portfolio(1, 20);
        assertEquals(0, vo.getSummary().getTotalThreeYearNetBenefit().compareTo(BigDecimal.ZERO));
        assertEquals(0L, vo.getStatusDistribution().get("draft"), "空集五态全 0");
    }
}
