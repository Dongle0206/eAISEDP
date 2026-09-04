package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.governance.dto.ComplianceCheckVo;
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

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ComplianceCheckServiceImpl 单测（case-20260821 T3，SE §9.1 锚点 8/9/10/11——
 * AC-F1.8/F1.9/F1.10/F1.11；纯 Mockito，StandardServiceImplTest 先例）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComplianceCheckServiceImplTest {

    @Mock ComplianceCheckMapper baseMapper;
    @Mock AuditService auditService;

    @InjectMocks ComplianceCheckServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ComplianceCheck.class);
    }

    @BeforeEach
    void injectBaseMapper() throws Exception {
        java.lang.reflect.Field f = ComplianceCheckServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    // ==================== 构造工具 ====================

    private static ComplianceCheck check(String framework, String frameworkName, String result) {
        ComplianceCheck c = new ComplianceCheck();
        c.setCheckName("访问控制条款核验");
        c.setFramework(framework);
        c.setFrameworkName(frameworkName);
        c.setClauseRef("A.9.4.1");
        c.setResult(result);
        c.setOwner("王五");
        return c;
    }

    private static ComplianceCheck stored(Long id, String result) {
        ComplianceCheck c = check("iso27001", null, result);
        c.setId(id);
        return c;
    }

    // ==================== 锚点 8：uk 与必填（AC-F1.8） ====================

    @Test
    void 同名创建被拒_uk冲突统一形态() {
        when(baseMapper.insert(any(ComplianceCheck.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_check_tenant_name"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(check("iso27001", null, "pass")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
        assertTrue(ex.getMessage().contains("访问控制条款核验"));
        verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 创建成功_检查日期缺省当天_审计compliance_create() {
        when(baseMapper.insert(any(ComplianceCheck.class))).thenAnswer(inv -> {
            ComplianceCheck c = inv.getArgument(0);
            c.setId(4000L);
            return 1;
        });

        ComplianceCheck created = service.create(check("iso27001", null, "pass"));

        ArgumentCaptor<ComplianceCheck> captor = ArgumentCaptor.forClass(ComplianceCheck.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals(LocalDate.now(), captor.getValue().getCheckDate(), "checkDate 缺省应用层取当天（V7 列可空）");
        verify(auditService).log(eq("compliance_create"), eq("compliance"), eq("4000"), anyString());
        assertNotNull(created.getId());
    }

    @Test
    void 必填缺失_检查项名_框架_结果均400指名() {
        for (String field : new String[]{"checkName", "framework", "result"}) {
            ComplianceCheck c = check("iso27001", null, "pass");
            switch (field) {
                case "checkName" -> c.setCheckName(null);
                case "framework" -> c.setFramework(" ");
                default -> c.setResult(null);
            }
            BizException ex = assertThrows(BizException.class, () -> service.create(c));
            assertEquals(400, ex.getCode(), field);
            assertTrue(ex.getMessage().contains(field), field + "，实际: " + ex.getMessage());
        }
    }

    // ==================== 锚点 9：框架枚举与 custom 联动四例（AC-F1.9） ====================

    @Test
    void 框架非法枚举_400指名与合法值集() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(check("iso27001:2022", null, "pass")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("framework"));
        assertTrue(ex.getMessage().contains("djba2.0"), "提示合法值集");
    }

    @Test
    void custom缺frameworkName_400() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(check("custom", null, "pass")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("frameworkName"));
    }

    @Test
    void custom带frameworkName_200且落库() {
        when(baseMapper.insert(any(ComplianceCheck.class))).thenAnswer(inv -> {
            ComplianceCheck c = inv.getArgument(0);
            c.setId(4001L);
            return 1;
        });

        assertDoesNotThrow(() -> service.create(check("custom", "内部安全规范", "pass")));
        ArgumentCaptor<ComplianceCheck> captor = ArgumentCaptor.forClass(ComplianceCheck.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals("内部安全规范", captor.getValue().getFrameworkName());
    }

    @Test
    void 非custom带frameworkName_400防脏数据() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(check("gdpr", "xx", "pass")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("frameworkName"));
        assertTrue(ex.getMessage().contains("gdpr"));
    }

    // ==================== 锚点 10：result 覆盖式更新（AC-F1.10） ====================

    @Test
    void 结果非法枚举_400() {
        ComplianceCheck c = check("iso27001", null, "unknown");
        BizException ex = assertThrows(BizException.class, () -> service.create(c));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("result"));
        assertTrue(ex.getMessage().contains("pass"));
    }

    @Test
    void 结果覆盖式更新_审计oldResult留痕() {
        when(baseMapper.selectById(4010L)).thenReturn(stored(4010L, "pass"), stored(4010L, "partial"));
        when(baseMapper.update(any(), any())).thenReturn(1);

        ComplianceCheck patch = check("iso27001", null, "partial");
        patch.setEvidenceNote("半数系统达标");
        service.edit(4010L, patch);

        // 落库覆盖：result=partial + 证据
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ComplianceCheck>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(baseMapper).update(any(), cap.capture());
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("partial"), "覆盖式单值当前态");
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("半数系统达标"));

        // 审计 detail 含 oldResult→newResult + 证据（历史唯一留痕，AC-F1.10/AC-AUDIT.1）
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("compliance_update"), eq("compliance"), eq("4010"), detail.capture());
        assertTrue(detail.getValue().contains("\"oldResult\":\"pass\""), "旧 pass 值仅存审计，实际: " + detail.getValue());
        assertTrue(detail.getValue().contains("\"newResult\":\"partial\""));
        assertTrue(detail.getValue().contains("半数系统达标"));
    }

    @Test
    void custom切换回标准框架_framework_name清列() {
        // 编辑 custom→iso27001：framework_name 显式置 null（LambdaUpdateWrapper set null 落库）
        ComplianceCheck stored = check("custom", "内部安全规范", "pass");
        stored.setId(4011L);
        when(baseMapper.selectById(4011L)).thenReturn(stored, check("iso27001", null, "pass"));
        when(baseMapper.update(any(), any())).thenReturn(1);

        service.edit(4011L, check("iso27001", null, "pass"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ComplianceCheck>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(baseMapper).update(any(), cap.capture());
        String sqlSet = cap.getValue().getSqlSet();
        assertNotNull(sqlSet);
        assertTrue(sqlSet.contains("framework_name"), "framework_name 必须显式 set（清空语义）");
    }

    @Test
    void 逻辑删除_审计compliance_delete() {
        when(baseMapper.selectById(4012L)).thenReturn(stored(4012L, "fail"));
        when(baseMapper.deleteById(any(ComplianceCheck.class))).thenReturn(1);

        service.remove(4012L);

        verify(auditService).log(eq("compliance_delete"), eq("compliance"), eq("4012"), anyString());
    }

    @Test
    void 不存在或跨租户_404() {
        when(baseMapper.selectById(9999L)).thenReturn(null);
        assertEquals(404, assertThrows(BizException.class, () -> service.loadOr404(9999L)).getCode());
    }

    // ==================== 锚点 11：复检逾期（AC-F1.11，na 不豁免） ====================

    @Test
    void na结果不豁免逾期_昨天recheck红标() {
        ComplianceCheck na = stored(4020L, "na");
        na.setRecheckDate(LocalDate.now().minusDays(1));

        ComplianceCheckVo vo = service.toVo(na);
        assertTrue(vo.getOverdue(), "result=na 且 recheck_date=昨天 → 逾期红标（na 不豁免）");
    }

    @Test
    void 空recheck无标识_未来日期不标() {
        ComplianceCheck noDate = stored(4021L, "fail");
        assertFalse(service.toVo(noDate).getOverdue(), "recheck_date 空 → 无标识");

        ComplianceCheck future = stored(4022L, "partial");
        future.setRecheckDate(LocalDate.now().plusDays(7));
        assertFalse(service.toVo(future).getOverdue(), "未来日期不标");

        ComplianceCheck today = stored(4023L, "pass");
        today.setRecheckDate(LocalDate.now());
        assertFalse(service.toVo(today).getOverdue(), "到期当日不标（DATE 精度次日红标）");
    }

    @Test
    void overdueOnly筛选_写入recheck_date条件() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<ComplianceCheck> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });

        service.pageFilter(null, null, Boolean.TRUE, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ComplianceCheck>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(), w.capture());
        assertTrue(w.getValue().getSqlSegment().contains("recheck_date"),
                "overdueOnly → recheck_date < 今天，实际: " + w.getValue().getSqlSegment());
    }

    @Test
    void 列表默认id降序() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<ComplianceCheck> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });

        service.pageFilter(null, null, null, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ComplianceCheck>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(), w.capture());
        assertTrue(w.getValue().getSqlSegment().toLowerCase().contains("id desc"), "默认 id DESC（PRD 未锁排序）");
    }
}
