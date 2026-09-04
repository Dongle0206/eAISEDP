package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.service.CaseService;
import com.eaiselp.runtime.governance.dto.RiskDashboardVo;
import com.eaiselp.runtime.governance.dto.RiskVo;
import com.eaiselp.runtime.hierarchy.Program;
import com.eaiselp.runtime.hierarchy.ProgramMapper;
import com.eaiselp.runtime.hierarchy.Project;
import com.eaiselp.runtime.hierarchy.ProjectMapper;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RiskServiceImpl 单测（case-20260821 T2/T10/T12，SE §9.1 锚点 3/4/5/6/7/17/18/19）。
 *
 * <p>纯 Mockito 方式（对齐 StandardServiceImplTest 先例）：
 * <ul>
 *   <li>锚点 3：uk(tenant,risk_name) 拒 / 名称类别 owner 缺失 400（AC-F1.1）；</li>
 *   <li>锚点 4：计算落库与防伪造——库值恒=P×I（AC-F1.5）；</li>
 *   <li>锚点 5：状态机全路径（AC-F1.4 + §0.3-1 消解：mitigating→open 回退合法）；</li>
 *   <li>锚点 6：关联对象三类型回显/非法 type/不存在 id/空数组/逻辑删占位（AC-F1.6）；</li>
 *   <li>锚点 7：overdue 昨天/今天/+30d 三构造 + overdueOnly + closed 后不逾期（AC-F1.7）；</li>
 *   <li>锚点 17/18/19：看板 cells 25 格/等级分布/高风险清单（AC-F1.12~F1.14）。</li>
 * </ul></p>
 *
 * <p>注：Mapper mock 字段名必须为 baseMapper——ServiceImpl.baseMapper 按 field 名注入
 * （StandardServiceImplTest 先例）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskServiceImplTest {

    @Mock RiskMapper baseMapper;
    @Mock ProgramMapper programMapper;
    @Mock ProjectMapper projectMapper;
    @Mock CaseService caseService;
    @Mock AuditService auditService;

    @InjectMocks RiskServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Risk.class);
        TableInfoHelper.initTableInfo(assistant, Case.class);
    }

    @BeforeEach
    void injectBaseMapper() throws Exception {
        java.lang.reflect.Field f = RiskServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    // ==================== 构造工具 ====================

    private static Risk risk(String name, String p, String i) {
        Risk r = new Risk();
        r.setRiskName(name);
        r.setCategory("security");
        r.setProbability(new BigDecimal(p));
        r.setImpact(new BigDecimal(i));
        r.setOwner("张三");
        return r;
    }

    private static Risk stored(Long id, String status, int p, int i) {
        Risk r = risk("R" + id, String.valueOf(p), String.valueOf(i));
        r.setId(id);
        r.setStatus(status);
        r.setRiskValue(p * i);
        r.setRiskLevel(RiskCalculator.riskLevel(p * i).dbValue());
        return r;
    }

    private void stubInsertOk() {
        when(baseMapper.insert(any(Risk.class))).thenAnswer(inv -> {
            Risk r = inv.getArgument(0);
            r.setId(3000L);
            return 1;
        });
    }

    private void stubUpdateOk() {
        when(baseMapper.update(any(), any())).thenReturn(1);
    }

    /** LambdaUpdateWrapper 的 set 值参表（sqlSet 占位符 → 实值）。 */
    private static Map<String, Object> setParams(LambdaUpdateWrapper<Risk> w) {
        return w.getParamNameValuePairs();
    }

    // ==================== 锚点 3：uk 与必填（AC-F1.1/F1.3） ====================

    @Test
    void 同名创建被拒_uk冲突统一形态() {
        when(baseMapper.insert(any(Risk.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_risk_tenant_name"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(risk("数据泄露", "4", "5")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"), "uk 冲突统一形态「...已存在: ...」，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("数据泄露"));
        verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 名称_类别_owner缺失均400指名() {
        for (String field : new String[]{"riskName", "category", "owner"}) {
            Risk r = risk("数据泄露", "4", "5");
            switch (field) {
                case "riskName" -> r.setRiskName(null);
                case "category" -> r.setCategory(null);
                default -> r.setOwner(" ");
            }
            BizException ex = assertThrows(BizException.class, () -> service.create(r));
            assertEquals(400, ex.getCode(), field);
            assertTrue(ex.getMessage().contains(field), field + "，实际: " + ex.getMessage());
        }
    }

    @Test
    void 类别非法枚举_400指名与合法值集() {
        Risk r = risk("R", "4", "5");
        r.setCategory("finance");
        BizException ex = assertThrows(BizException.class, () -> service.create(r));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("category"));
        assertTrue(ex.getMessage().contains("strategy"));
    }

    // ==================== 锚点 4：计算落库与防伪造（AC-F1.5） ====================

    @Test
    void 创建P4I5_落库riskValue20且等级critical_审计含计算值() {
        stubInsertOk();

        Risk created = service.create(risk("数据泄露", "4", "5"));

        ArgumentCaptor<Risk> captor = ArgumentCaptor.forClass(Risk.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals(20, captor.getValue().getRiskValue(), "riskValue=P×I 服务端算");
        assertEquals("critical", captor.getValue().getRiskLevel());
        assertEquals("open", captor.getValue().getStatus(), "创建固定 open");
        assertNull(captor.getValue().getResolutionNote(), "非 closed 状态 resolutionNote 置 NULL");
        assertEquals(20, created.getRiskValue());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("risk_create"), eq("risk"), anyString(), detail.capture());
        assertTrue(detail.getValue().contains("数据泄露"));
        assertTrue(detail.getValue().contains("20"));
        assertTrue(detail.getValue().contains("critical"));
    }

    @Test
    void 编辑P2I3改P5I4_重算覆盖riskValue20_伪造值无绑定入口() {
        when(baseMapper.selectById(3001L)).thenReturn(stored(3001L, "open", 2, 3), stored(3001L, "open", 5, 4));
        stubUpdateOk();

        // 入参实体不带 riskValue/riskLevel（Controller toEntity 不映射）——即使预置伪造值也恒被重算覆盖
        Risk patch = risk("数据泄露", "5", "4");
        patch.setRiskValue(999);
        patch.setRiskLevel("low");
        service.edit(3001L, patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Risk>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(baseMapper).update(any(), cap.capture());
        Map<String, Object> params = setParams(cap.getValue());
        assertTrue(params.containsValue(20), "update set 恒携带重算值 20，实际: " + params);
        assertTrue(params.containsValue("critical"), "update set 恒携带重算等级 critical");
        verify(auditService).log(eq("risk_update"), eq("risk"), eq("3001"), anyString());
    }

    @Test
    void closed编辑任意字段_400终态只读() {
        when(baseMapper.selectById(3002L)).thenReturn(stored(3002L, "closed", 4, 5));

        BizException ex = assertThrows(BizException.class,
                () -> service.edit(3002L, risk("任意", "1", "1")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("终态"));
        verify(baseMapper, never()).update(any(), any());
    }

    @Test
    void 逻辑删除_审计risk_delete() {
        when(baseMapper.selectById(3003L)).thenReturn(stored(3003L, "open", 4, 5));
        when(baseMapper.deleteById(any(Risk.class))).thenReturn(1);

        service.remove(3003L);

        verify(auditService).log(eq("risk_delete"), eq("risk"), eq("3003"), anyString());
    }

    @Test
    void 不存在或跨租户_404() {
        when(baseMapper.selectById(9999L)).thenReturn(null);
        assertEquals(404, assertThrows(BizException.class, () -> service.loadOr404(9999L)).getCode());
    }

    // ==================== 锚点 5：状态机全路径（AC-F1.4 + §0.3-1） ====================

    @Test
    void open到mitigating_200且审计from_to() {
        when(baseMapper.selectById(3010L)).thenReturn(stored(3010L, "open", 4, 5), stored(3010L, "mitigating", 4, 5));
        stubUpdateOk();

        RiskVo vo = service.transit(3010L, "mitigating", null);

        assertEquals("mitigating", vo.getStatus());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("risk_transit"), eq("risk"), eq("3010"), detail.capture());
        assertTrue(detail.getValue().contains("\"from\":\"open\""));
        assertTrue(detail.getValue().contains("\"to\":\"mitigating\""));
    }

    @Test
    void mitigating到closed_必填处置说明() {
        when(baseMapper.selectById(3011L)).thenReturn(stored(3011L, "mitigating", 4, 5));
        stubUpdateOk();

        // 缺说明 → 400
        BizException ex = assertThrows(BizException.class, () -> service.transit(3011L, "closed", " "));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("resolutionNote"));

        // 带说明 → 200，落库值=说明，审计含 from→to + 说明（重置连续 stub：transit 加载→detailVo 回显）
        when(baseMapper.selectById(3011L)).thenReturn(stored(3011L, "mitigating", 4, 5), stored(3011L, "closed", 4, 5));
        RiskVo vo = service.transit(3011L, "closed", "已转移供应商");
        assertEquals("closed", vo.getStatus());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("risk_transit"), eq("risk"), eq("3011"), detail.capture());
        assertTrue(detail.getValue().contains("已转移供应商"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Risk>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(baseMapper).update(any(), cap.capture());
        assertTrue(setParams(cap.getValue()).containsValue("已转移供应商"), "closed 落库 resolution_note");
    }

    @Test
    void mitigating到open_回退合法200_消解表口径() {
        // §0.3-1 消解：裁决"单向"=终态不可回退；mitigating→open 回退合法（AC-F1.4 原文，QA 照此断言）
        when(baseMapper.selectById(3012L)).thenReturn(stored(3012L, "mitigating", 4, 5), stored(3012L, "open", 4, 5));
        stubUpdateOk();

        RiskVo vo = service.transit(3012L, "open", null);
        assertEquals("open", vo.getStatus());
        verify(auditService).log(eq("risk_transit"), eq("risk"), eq("3012"), anyString());
    }

    @Test
    void open到closed_跳级400() {
        when(baseMapper.selectById(3013L)).thenReturn(stored(3013L, "open", 4, 5));

        BizException ex = assertThrows(BizException.class, () -> service.transit(3013L, "closed", "说明"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("跳级"));
        verify(baseMapper, never()).update(any(), any());
    }

    @Test
    void closed任何出边_400终态() {
        when(baseMapper.selectById(3014L)).thenReturn(stored(3014L, "closed", 4, 5));
        for (String target : new String[]{"open", "mitigating"}) {
            BizException ex = assertThrows(BizException.class, () -> service.transit(3014L, target, null));
            assertEquals(400, ex.getCode(), target);
            assertTrue(ex.getMessage().contains("终态"));
        }
    }

    @Test
    void 自流转幂等_不更新不审计() {
        when(baseMapper.selectById(3015L)).thenReturn(stored(3015L, "open", 4, 5));

        RiskVo vo = service.transit(3015L, "open", null);
        assertEquals("open", vo.getStatus());
        verify(baseMapper, never()).update(any(), any());
        verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 未知target_400合法值集提示() {
        when(baseMapper.selectById(3016L)).thenReturn(stored(3016L, "open", 4, 5));
        BizException ex = assertThrows(BizException.class, () -> service.transit(3016L, "done", null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("open"));
    }

    // ==================== 锚点 6：关联对象（AC-F1.6） ====================

    private static final String RELATED_ALL = "[{\"type\":\"program\",\"id\":\"88\"},"
            + "{\"type\":\"project\",\"id\":\"99\"},{\"type\":\"case\",\"id\":\"case-1\"}]";

    private void stubRelatedTargets() {
        Program pg = new Program();
        pg.setId(88L);
        pg.setName("PG1");
        when(programMapper.selectById(88L)).thenReturn(pg);
        Project pj = new Project();
        pj.setId(99L);
        pj.setName("PJ1");
        when(projectMapper.selectById(99L)).thenReturn(pj);
        Case c = new Case();
        c.setId(1L);
        c.setCaseId("case-1");
        c.setTitle("C1");
        when(caseService.getOne(any())).thenReturn(c);
    }

    @Test
    void 关联三类型合法_详情回显对象名() {
        stubRelatedTargets();
        Risk stored = stored(3020L, "open", 4, 5);
        stored.setRelatedObjects(RELATED_ALL);
        when(baseMapper.selectById(3020L)).thenReturn(stored);

        RiskVo vo = service.detailVo(3020L);

        assertEquals(3, vo.getRelatedObjects().size());
        assertTrue(vo.getRelatedObjects().stream().noneMatch(o -> o.isDeleted()));
        assertTrue(vo.getRelatedObjects().stream().anyMatch(o -> "PG1".equals(o.getName())));
        assertTrue(vo.getRelatedObjects().stream().anyMatch(o -> "PJ1".equals(o.getName())));
        assertTrue(vo.getRelatedObjects().stream().anyMatch(o -> "C1".equals(o.getName())));
    }

    @Test
    void 关联非法type_400指名() {
        Risk r = risk("R", "4", "5");
        r.setRelatedObjects("[{\"type\":\"task\",\"id\":\"1\"}]");

        BizException ex = assertThrows(BizException.class, () -> service.create(r));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("type"));
        verify(baseMapper, never()).insert(any(Risk.class));
    }

    @Test
    void 关联program不存在id_400指名() {
        when(programMapper.selectById(404L)).thenReturn(null);
        Risk r = risk("R", "4", "5");
        r.setRelatedObjects("[{\"type\":\"program\",\"id\":\"404\"}]");

        BizException ex = assertThrows(BizException.class, () -> service.create(r));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("404"));
    }

    @Test
    void 关联case不存在caseId_400指名() {
        when(caseService.getOne(any())).thenReturn(null);
        Risk r = risk("R", "4", "5");
        r.setRelatedObjects("[{\"type\":\"case\",\"id\":\"case-ghost\"}]");

        BizException ex = assertThrows(BizException.class, () -> service.create(r));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("case-ghost"));
    }

    @Test
    void 关联空数组合法_不触发校验() {
        stubInsertOk();
        Risk r = risk("R", "4", "5");
        r.setRelatedObjects("[]");

        assertDoesNotThrow(() -> service.create(r));
        verify(programMapper, never()).selectById(any(Long.class));
        verify(projectMapper, never()).selectById(any(Long.class));
        verify(caseService, never()).getOne(any());
    }

    @Test
    void 被关联对象逻辑删后_详情deleted占位_行为不变() {
        // PJ1 已逻辑删（@TableLogic 下 selectById 返回 null）→ 占位不 400
        when(programMapper.selectById(88L)).thenReturn(null);
        Project pj = new Project();
        pj.setId(99L);
        pj.setName("PJ1");
        when(projectMapper.selectById(99L)).thenReturn(pj);
        Case c = new Case();
        c.setCaseId("case-1");
        c.setTitle("C1");
        when(caseService.getOne(any())).thenReturn(c);
        Risk stored = stored(3021L, "open", 4, 5);
        stored.setRelatedObjects(RELATED_ALL);
        when(baseMapper.selectById(3021L)).thenReturn(stored);

        RiskVo vo = assertDoesNotThrow(() -> service.detailVo(3021L), "逻辑删关联不 400（AC-F1.6）");
        assertTrue(vo.getRelatedObjects().stream().anyMatch(o -> "program".equals(o.getType()) && o.isDeleted()));
        assertTrue(vo.getRelatedObjects().stream().anyMatch(o -> "project".equals(o.getType()) && !o.isDeleted()));
    }

    // ==================== 锚点 7：逾期口径（AC-F1.7，D-10） ====================

    @Test
    void overdue_昨天true_今天与加30天false_closed恒false() {
        Risk yesterday = stored(3030L, "open", 4, 5);
        yesterday.setReviewDate(LocalDate.now().minusDays(1));
        Risk today = stored(3031L, "open", 4, 5);
        today.setReviewDate(LocalDate.now());
        Risk future = stored(3032L, "open", 4, 5);
        future.setReviewDate(LocalDate.now().plusDays(30));
        Risk closedOverdue = stored(3033L, "closed", 4, 5);
        closedOverdue.setReviewDate(LocalDate.now().minusDays(1));
        Risk noDate = stored(3034L, "open", 4, 5);

        assertTrue(service.toVo(yesterday).getOverdue(), "昨天 → 逾期");
        assertFalse(service.toVo(today).getOverdue(), "今天 → 不标（DATE 精度次日红标）");
        assertFalse(service.toVo(future).getOverdue(), "+30 天 → 不标");
        assertFalse(service.toVo(closedOverdue).getOverdue(), "closed 后逾期标识消失");
        assertFalse(service.toVo(noDate).getOverdue(), "空复评日期 → 不逾期");
    }

    @Test
    void overdueOnly筛选_写入review_date与status条件() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Risk> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });

        service.pageFilter(null, null, null, Boolean.TRUE, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Risk>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(), w.capture());
        String sql = w.getValue().getSqlSegment();
        assertTrue(sql.contains("review_date"), "overdueOnly → review_date < 今天，实际: " + sql);
        assertTrue(sql.contains("status"), "overdueOnly → status <> closed，实际: " + sql);
    }

    @Test
    void 列表默认排序_risk_value降序id降序() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Risk> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });

        service.pageFilter(null, null, null, null, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Risk>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(), w.capture());
        String sql = w.getValue().getSqlSegment().toLowerCase();
        assertTrue(sql.contains("risk_value desc"), "默认排序 risk_value DESC（QA 断言口径），实际: " + sql);
        assertTrue(sql.contains("id desc"), "并列 id DESC（新建在前）");
    }

    // ==================== 锚点 17/18/19：看板聚合（AC-F1.12~F1.14） ====================

    private static RiskMapper.HeatCell cell(int p, int i, long count) {
        RiskMapper.HeatCell c = new RiskMapper.HeatCell();
        c.setProbability(p);
        c.setImpact(i);
        c.setCount(count);
        return c;
    }

    @Test
    void 看板cells恰25格_仅未closed计入_22格为0() {
        // AC-F1.12 数据集：open{P2,I3}/{P4,I5} + mitigating{P5,I4}；closed{P2,I3} 被 SQL 排除
        when(baseMapper.selectHeatCells()).thenReturn(List.of(cell(2, 3, 1), cell(4, 5, 1), cell(5, 4, 1)));
        when(baseMapper.selectList(any())).thenReturn(List.of());

        RiskDashboardVo vo = service.dashboard();

        assertEquals(25, vo.getCells().size(), "cells 恰 25 格");
        long nonZero = vo.getCells().stream().filter(c -> c.getCount() == 1).count();
        assertEquals(3, nonZero, "3 格计数 1");
        assertEquals(22, vo.getCells().stream().filter(c -> c.getCount() == 0).count(), "22 格 0（closed 不计入）");
        // 元组自描述：抽查 (P2,I3)=1、(P5,I4)=1、(P1,I1)=0；格 riskValue/level 由公式推导
        RiskDashboardVo.Cell c23 = vo.getCells().stream()
                .filter(c -> c.getProbability() == 2 && c.getImpact() == 3).findFirst().orElseThrow();
        assertEquals(1L, c23.getCount());
        assertEquals(6, c23.getRiskValue());
        assertEquals("low", c23.getRiskLevel());
        RiskDashboardVo.Cell c54 = vo.getCells().stream()
                .filter(c -> c.getProbability() == 5 && c.getImpact() == 4).findFirst().orElseThrow();
        assertEquals(20, c54.getRiskValue());
        assertEquals("critical", c54.getRiskLevel());
        // 高风险清单查询口径：level IN(high,critical) AND status<>closed（AC-F1.12/14 排除 closed）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Risk>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectList(w.capture());
        String sql = w.getValue().getSqlSegment();
        assertTrue(sql.contains("risk_level"), "高风险清单按 level 过滤");
        assertTrue(sql.contains("status"), "高风险清单排除 closed");
    }

    @Test
    void 看板等级分布_低1中0高0极高2() {
        // AC-F1.13 数据集（v：6 低、20 极高、20 极高）
        when(baseMapper.selectHeatCells()).thenReturn(List.of(cell(2, 3, 1), cell(4, 5, 1), cell(5, 4, 1)));
        when(baseMapper.selectList(any())).thenReturn(List.of());

        RiskDashboardVo vo = service.dashboard();

        assertEquals(1L, vo.getLevelDistribution().get("low"));
        assertEquals(0L, vo.getLevelDistribution().get("medium"));
        assertEquals(0L, vo.getLevelDistribution().get("high"));
        assertEquals(2L, vo.getLevelDistribution().get("critical"));
    }

    @Test
    void 高风险清单_仅3条_v降序并列id降序_逾期透传() {
        // AC-F1.14 构造集：高(15) + 极高(20)×2（其一较新）+ 中(12)——中(12) 被 SQL level 过滤，
        // DB 按 risk_value DESC, id DESC 返回 [v20 id=2, v20 id=1, v15 id=3]
        Risk r20new = stored(2L, "open", 5, 4);
        r20new.setReviewDate(LocalDate.now().minusDays(1));
        Risk r20old = stored(1L, "mitigating", 4, 5);
        Risk r15 = stored(3L, "open", 3, 5);
        when(baseMapper.selectHeatCells()).thenReturn(List.of());
        when(baseMapper.selectList(any())).thenReturn(List.of(r20new, r20old, r15));

        RiskDashboardVo vo = service.dashboard();

        assertEquals(3, vo.getHighRisks().size(), "仅高+极高，中(12)不在");
        assertEquals(20, vo.getHighRisks().get(0).getRiskValue());
        assertEquals(2L, vo.getHighRisks().get(0).getId(), "并列 v=20 按 id 降序（新创建在前）");
        assertEquals(1L, vo.getHighRisks().get(1).getId());
        assertEquals(15, vo.getHighRisks().get(2).getRiskValue());
        assertTrue(vo.getHighRisks().get(0).getOverdue(), "复评逾期标识透传（AC-F1.14）");
        assertFalse(vo.getHighRisks().get(1).getOverdue());
    }
}
