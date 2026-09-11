package com.eaiselp.data.service.subscription;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.PlanMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.subscription.dto.PlanVo;
import com.eaiselp.data.service.subscription.impl.SubscriptionPlanServiceImpl;
import com.eaiselp.common.result.ResultCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SubscriptionPlanService 单测（case-20260823-商用化 T3，SE §9.1 锚点 11/12；
 * AC-F1.1/F1.2/F1.3/F1.4 代码侧 + AC-F3.1 枚举校验侧 + D-9 双向校验 + uk 冲突）。
 *
 * <p>纯 Mockito（data 模块先例 TenantSubscriptionServiceTest——无 H2 基建，
 * Mapper mock + TableInfo lambda 缓存初始化）。features 构造用合法 Map（与 V8 seed 同构）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionPlanServiceImplTest {

    @Mock PlanMapper planMapper;
    @Mock TenantMapper tenantMapper;
    @Mock AuditService auditService;

    @InjectMocks SubscriptionPlanServiceImpl service;

    private static final Map<String, Object> FEATURES = Map.of(
            "max_users", 50, "max_cases", 100, "token_limit", 5_000_000, "case_limit", 100,
            "derivation_limit", 1000, "storage_limit_mb", 51200,
            "layer_overrides", Map.of("strategy_enabled", 1, "program_project_enabled", 1),
            "model_tiers", List.of("sonnet", "haiku"));

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Plan.class);
        TableInfoHelper.initTableInfo(assistant, Tenant.class);
    }

    @BeforeEach
    void setUp() {
        when(planMapper.selectCount(any())).thenReturn(0L);
    }

    // ==================== 构造工具 ====================

    private static Plan plan(String code, String name, String sla) {
        Plan p = new Plan();
        p.setCode(code);
        p.setName(name);
        p.setMonthlyPrice(new BigDecimal("999.00"));
        p.setTokenOveragePrice(new BigDecimal("8.000000"));
        p.setSlaLevel(sla);
        return p;
    }

    // ==================== 锚点 11：创建校验族（AC-F1.1/F1.2/F1.3 + D-9） ====================

    @Test
    void 创建合法落库_字段与提交一致_AC_F1_1() {
        Plan p = plan("pro", "专业版", "silver");
        PlanVo vo = service.create(p, FEATURES);

        assertNotNull(vo);
        assertEquals("pro", vo.getCode());
        assertEquals(new BigDecimal("999.00"), vo.getMonthlyPrice());
        assertEquals(Boolean.TRUE, vo.getEnabled(), "enabled 缺省 true");
        assertEquals(List.of("sonnet", "haiku"), (List<?>) vo.getFeatures().get("model_tiers"),
                "features 结构化回显");
        verify(planMapper).insert(any(Plan.class));
        // 审计 plan_create（公共验收约束 6）
        verify(auditService).log(eq("plan_create"), eq("plan"), any(), any(String.class));
    }

    @Test
    void 创建_features缺必需键_400() {
        Plan p = plan("pro", "专业版", "silver");
        Map<String, Object> bad = Map.of("max_users", 10); // 缺其余必需键
        BizException ex = assertThrows(BizException.class, () -> service.create(p, bad));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("缺少必需键"), "指名缺失键: " + ex.getMessage());
        verify(planMapper, never()).insert(any());
    }

    @Test
    void 创建_features值域非法_400() {
        Plan p = plan("pro", "专业版", "silver");
        Map<String, Object> bad = new java.util.HashMap<>(FEATURES);
        bad.put("model_tiers", List.of("high", "mid")); // PRD 示意名非法（DBA R5：真实档位名）
        BizException ex = assertThrows(BizException.class, () -> service.create(p, bad));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("model_tiers"), "指名 model_tiers 值域: " + ex.getMessage());
    }

    @Test
    void 创建_features未知键忽略_合法() {
        Plan p = plan("pro", "专业版", "silver");
        Map<String, Object> extra = new java.util.HashMap<>(FEATURES);
        extra.put("future_key", "whatever"); // 未知键忽略（向前兼容）
        assertDoesNotThrow(() -> service.create(p, extra));
    }

    @Test
    void 创建_code枚举外_400指名_AC_F1_2() {
        Plan p = plan("team", "团队版", "silver");
        BizException ex = assertThrows(BizException.class, () -> service.create(p, FEATURES));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("team"));
        assertTrue(ex.getMessage().contains("starter"), "提示合法值集");
    }

    @Test
    void 创建_标准档同code在架已存在_400_AC_F1_2() {
        when(planMapper.selectCount(any())).thenReturn(1L);
        Plan p = plan("starter", "入门版二", "bronze");
        BizException ex = assertThrows(BizException.class, () -> service.create(p, FEATURES));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("在架"), "标准档同 code 在架唯一: " + ex.getMessage());
    }

    @Test
    void 创建_custom多份合法_AC_F1_2() {
        when(planMapper.selectCount(any())).thenReturn(99L); // custom 不查重（selectCount 高也放行）
        Plan p = plan("custom", "定制版B", "gold");
        p.setEditionOverride("pro");
        assertDoesNotThrow(() -> service.create(p, FEATURES));
    }

    @Test
    void 创建_金额负值_400_AC_F1_3() {
        Plan neg1 = plan("pro", "专业版", "silver");
        neg1.setMonthlyPrice(new BigDecimal("-1"));
        BizException ex1 = assertThrows(BizException.class, () -> service.create(neg1, FEATURES));
        assertEquals(400, ex1.getCode());
        assertTrue(ex1.getMessage().contains("monthlyPrice"));

        Plan neg2 = plan("pro", "专业版", "silver");
        neg2.setTokenOveragePrice(new BigDecimal("-0.01"));
        BizException ex2 = assertThrows(BizException.class, () -> service.create(neg2, FEATURES));
        assertEquals(400, ex2.getCode());
        assertTrue(ex2.getMessage().contains("tokenOveragePrice"));
    }

    @Test
    void 创建_金额超DECIMAL域_400指名_D6() {
        Plan over = plan("pro", "专业版", "silver");
        over.setMonthlyPrice(new BigDecimal("1000000000000")); // 1e12 > 999,999,999,999.99
        BizException ex = assertThrows(BizException.class, () -> service.create(over, FEATURES));
        assertEquals(400, ex.getCode(), "超域 400 指名非 500（QA 构造 1e12 断言口径）");
        assertTrue(ex.getMessage().contains("DECIMAL(14,2)"));
    }

    @Test
    void 创建_零值合法_免费档_AC_F1_3() {
        Plan free = plan("custom", "免费定制档", "bronze");
        free.setEditionOverride("starter");
        free.setMonthlyPrice(BigDecimal.ZERO);
        free.setTokenOveragePrice(BigDecimal.ZERO);
        assertDoesNotThrow(() -> service.create(free, FEATURES));
    }

    @Test
    void 创建_slaLevel枚举外_400_AC_F3_1() {
        Plan p = plan("pro", "专业版", "platinum");
        BizException ex = assertThrows(BizException.class, () -> service.create(p, FEATURES));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("slaLevel"));

        Plan nullSla = plan("pro", "专业版", null);
        assertThrows(BizException.class, () -> service.create(nullSla, FEATURES));
    }

    @Test
    void 创建_custom未填editionOverride_400_D9() {
        Plan p = plan("custom", "定制版", "gold");
        BizException ex = assertThrows(BizException.class, () -> service.create(p, FEATURES));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("editionOverride"));
    }

    @Test
    void 创建_custom的editionOverride枚举外_400_D9() {
        Plan p = plan("custom", "定制版", "gold");
        p.setEditionOverride("trial"); // trial 不可作 override
        assertThrows(BizException.class, () -> service.create(p, FEATURES));
    }

    @Test
    void 创建_非custom带editionOverride_400_D9双向() {
        Plan p = plan("pro", "专业版", "silver");
        p.setEditionOverride("enterprise");
        BizException ex = assertThrows(BizException.class, () -> service.create(p, FEATURES));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("必须为空"));
    }

    @Test
    void 创建_uk名称冲突_400统一形态() {
        org.springframework.dao.DuplicateKeyException dke =
                new org.springframework.dao.DuplicateKeyException("uk_plan_name");
        when(planMapper.insert(any(Plan.class))).thenThrow(dke);
        Plan p = plan("pro", "专业版", "silver");
        BizException ex = assertThrows(BizException.class, () -> service.create(p, FEATURES));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // ==================== 锚点 12：编辑/上下架（AC-F1.4 + P4 语义） ====================

    @Test
    void 编辑_下架复活到在架_同code已存在_400() {
        // 下架套餐（id=101 code=pro）编辑为 enabled=true，但已有另一份在架 pro → 400（D-1 目标态查重）
        when(planMapper.selectById(101L)).thenReturn(stored("pro", false));
        when(planMapper.selectCount(any())).thenReturn(1L);
        Plan patch = plan("pro", "专业版改", "silver");
        patch.setEnabled(true);
        BizException ex = assertThrows(BizException.class, () -> service.update(101L, patch, FEATURES));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("在架"));
    }

    @Test
    void 编辑_含enabled下架_全量落库_AC_F1_4() {
        Plan updatedStored = stored("pro", false);
        updatedStored.setId(102L);
        when(planMapper.selectById(102L)).thenReturn(updatedStored);
        when(planMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        Plan patch = plan("pro", "专业版", "silver");
        patch.setEnabled(false); // 下架
        PlanVo vo = service.update(102L, patch, FEATURES);

        assertFalse(vo.getEnabled(), "下架生效");
        // 审计 plan_update detail 含 old→new（约束 6）
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("plan_update"), eq("plan"), eq("102"), detail.capture());
        assertTrue(detail.getValue().contains("\"old\""), "old→new 快照");
    }

    @Test
    void 编辑_商品目录语义_不触碰t_invoice_t_quota() {
        // P4 冻结：编辑不回写已生成账单与当期 t_quota——本服务仅依赖 PlanMapper（结构上不可能联动写）
        Plan exist = stored("pro", true);
        exist.setId(103L);
        when(planMapper.selectById(103L)).thenReturn(exist);
        when(planMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        Plan patch = plan("pro", "专业版新价", "silver");
        patch.setMonthlyPrice(new BigDecimal("1999.00"));
        assertDoesNotThrow(() -> service.update(103L, patch, FEATURES));
    }

    @Test
    void 详情_不存在_40400() {
        when(planMapper.selectById(404L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.detail(404L));
        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    // ==================== T6 前置：assertModelTierAllowed（AC-F1.7 逻辑侧） ====================

    @Test
    void 档位校验_绑定套餐_tier不在范围_40004() {
        Tenant t = new Tenant();
        t.setId(1L);
        t.setEdition("pro");
        t.setPlanCode("pro");
        when(tenantMapper.selectById(1L)).thenReturn(t);
        when(planMapper.selectList(any())).thenReturn(List.of(storedWithFeatures("pro", true)));

        BizException ex = assertThrows(BizException.class,
                () -> service.assertModelTierAllowed(1L, "opus"));
        assertEquals(ResultCode.MODEL_TIER_FORBIDDEN, ex.getCode(), "opus ∉ [sonnet,haiku] → 40004");
        assertTrue(ex.getMessage().contains("opus"));
    }

    @Test
    void 档位校验_tier在范围_放行() {
        Tenant t = new Tenant();
        t.setId(1L);
        t.setEdition("pro");
        t.setPlanCode("pro");
        when(tenantMapper.selectById(1L)).thenReturn(t);
        when(planMapper.selectList(any())).thenReturn(List.of(storedWithFeatures("pro", true)));
        assertDoesNotThrow(() -> service.assertModelTierAllowed(1L, "sonnet"));
        assertDoesNotThrow(() -> service.assertModelTierAllowed(1L, "haiku"));
    }

    @Test
    void 档位校验_未绑定trial租户_不限_AC_F1_7现状回归() {
        Tenant trial = new Tenant();
        trial.setId(2L);
        trial.setEdition("trial");
        trial.setPlanCode(null);
        when(tenantMapper.selectById(2L)).thenReturn(trial);
        assertDoesNotThrow(() -> service.assertModelTierAllowed(2L, "opus"), "未绑定 trial 任意放行");
    }

    @Test
    void 档位校验_套餐逻辑删_防御放行_WARN_D10() {
        Tenant t = new Tenant();
        t.setId(3L);
        t.setEdition("pro");
        t.setPlanCode("pro");
        when(tenantMapper.selectById(3L)).thenReturn(t);
        when(planMapper.selectList(any())).thenReturn(List.of()); // 套餐查不到（逻辑删）
        assertDoesNotThrow(() -> service.assertModelTierAllowed(3L, "opus"), "解析失败防御放行（R7）");
    }

    // ==================== 工具 ====================

    private static Plan stored(String code, boolean enabled) {
        Plan p = plan(code, code + "版", "silver");
        p.setId(1000L);
        p.setEnabled(enabled);
        return p;
    }

    private static Plan storedWithFeatures(String code, boolean enabled) {
        Plan p = stored(code, enabled);
        p.setFeatures("{\"model_tiers\":[\"sonnet\",\"haiku\"]}");
        return p;
    }
}
