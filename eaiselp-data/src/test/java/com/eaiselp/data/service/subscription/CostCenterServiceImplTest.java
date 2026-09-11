package com.eaiselp.data.service.subscription;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.data.entity.Plan;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.mapper.PlanMapper;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.service.subscription.dto.CostCenterVo;
import com.eaiselp.data.service.subscription.impl.CostCenterServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 费用中心 C1 summary 单测（case-20260823-商用化 T10；AC-F2.9 Then 前半——四维用量水位
 * + 当前套餐卡；QA 验收期补位：summary 聚合逻辑此前无专属自动化，draft 隔离后半
 * （C2/C3）已在 InvoiceServiceImplTest#draft隔离 覆盖）。
 *
 * <p>纯 Mockito（SubscriptionPlanServiceImplTest 先例：Mapper mock + TableInfo lambda
 * 缓存初始化）。口径：水位=t_quota 当期行实时值仅展示非账单口径（B5）；trial/未绑定 →
 * 套餐卡 null（AC-F2.9）；SLA 档透传、承诺文案前端集中（D-17）。</p>
 */
@ExtendWith(MockitoExtension.class)
class CostCenterServiceImplTest {

    @Mock TenantMapper tenantMapper;
    @Mock PlanMapper planMapper;
    @Mock QuotaMapper quotaMapper;

    @InjectMocks CostCenterServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Tenant.class);
        TableInfoHelper.initTableInfo(assistant, Plan.class);
        TableInfoHelper.initTableInfo(assistant, Quota.class);
    }

    private Tenant paidTenant(String planCode) {
        Tenant t = new Tenant();
        t.setId(1L);
        t.setEdition("pro");
        t.setPlanCode(planCode);
        return t;
    }

    private Quota watermarkQuota() {
        Quota q = new Quota();
        q.setTenantId(1L);
        q.setPeriod(java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        q.setTokenUsed(4_200_000L);
        q.setTokenLimit(5_000_000L);
        q.setAlertThreshold(80);
        q.setCaseUsed(61);
        q.setCaseLimit(100);
        q.setDerivationUsed(250);
        q.setDerivationLimit(1000);
        q.setStorageUsedMb(20480L);
        q.setStorageLimitMb(51200L);
        return q;
    }

    @Test
    void 绑定付费租户_四维水位与套餐卡齐全() {
        when(tenantMapper.selectById(1L)).thenReturn(paidTenant("pro"));
        when(quotaMapper.selectOne(any())).thenReturn(watermarkQuota());
        Plan plan = new Plan();
        plan.setCode("pro");
        plan.setName("专业版");
        plan.setMonthlyPrice(new BigDecimal("999.00"));
        plan.setTokenOveragePrice(new BigDecimal("8.000000"));
        plan.setSlaLevel("silver");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        CostCenterVo vo = service.summary(1L);

        // 四维水位（AC-F2.9：token/case/派生/存储 used/limit；token 维附 alertThreshold）
        assertNotNull(vo.getQuota());
        assertEquals(4_200_000L, vo.getQuota().getToken().getUsed());
        assertEquals(5_000_000L, vo.getQuota().getToken().getLimit());
        assertEquals(80, vo.getQuota().getToken().getAlertThreshold(),
                "token 维附 alertThreshold（醒目提示阈值沿 t_quota）");
        assertEquals(61, vo.getQuota().getCaseDimension().getUsed());
        assertEquals(100, vo.getQuota().getCaseDimension().getLimit());
        assertEquals(250, vo.getQuota().getDerivation().getUsed());
        assertEquals(1000, vo.getQuota().getDerivation().getLimit());
        assertEquals(20480L, vo.getQuota().getStorage().getUsedMb());
        assertEquals(51200L, vo.getQuota().getStorage().getLimitMb());
        // 套餐卡五字段（AC-F2.9 / AC-F3.1 SLA 档透传——文案前端集中 D-17，后端零文案）
        assertNotNull(vo.getPlan());
        assertEquals("pro", vo.getPlan().getCode());
        assertEquals("专业版", vo.getPlan().getName());
        assertEquals(0, new BigDecimal("999.00").compareTo(vo.getPlan().getMonthlyPrice()));
        assertEquals(0, new BigDecimal("8.000000").compareTo(vo.getPlan().getTokenOveragePrice()));
        assertEquals("silver", vo.getPlan().getSlaLevel());
    }

    @Test
    void trial租户_套餐卡null_水位照常返回() {
        Tenant trial = new Tenant();
        trial.setId(1L);
        trial.setEdition("trial");
        trial.setPlanCode(null);
        when(tenantMapper.selectById(1L)).thenReturn(trial);
        when(quotaMapper.selectOne(any())).thenReturn(watermarkQuota());

        CostCenterVo vo = service.summary(1L);

        assertNull(vo.getPlan(), "trial → 套餐卡 null（AC-F2.9）");
        assertNotNull(vo.getQuota(), "trial 水位照常展示（试用也有配额）");
        assertEquals(4_200_000L, vo.getQuota().getToken().getUsed());
    }

    @Test
    void 当期配额行不存在_quota为null_套餐卡照常() {
        when(tenantMapper.selectById(1L)).thenReturn(paidTenant("pro"));
        when(quotaMapper.selectOne(any())).thenReturn(null);
        Plan plan = new Plan();
        plan.setCode("pro");
        plan.setName("专业版");
        plan.setMonthlyPrice(new BigDecimal("999.00"));
        plan.setSlaLevel("silver");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        CostCenterVo vo = service.summary(1L);

        assertNull(vo.getQuota(), "t_quota 当期行不存在 → quota null（新租户月初未建行）");
        assertNotNull(vo.getPlan(), "套餐卡不依赖配额行");
    }

    @Test
    void 水位列null值_防御为0() {
        when(tenantMapper.selectById(1L)).thenReturn(paidTenant("pro"));
        Quota q = watermarkQuota();
        q.setTokenUsed(null);
        q.setCaseUsed(null);
        q.setDerivationUsed(null);
        q.setStorageUsedMb(null);
        q.setAlertThreshold(null);
        when(quotaMapper.selectOne(any())).thenReturn(q);
        when(planMapper.selectList(any())).thenReturn(List.of(new Plan()));

        CostCenterVo vo = service.summary(1L);

        assertEquals(0L, vo.getQuota().getToken().getUsed(), "null used → 0（nz 防御非 NPE）");
        assertEquals(0, vo.getQuota().getCaseDimension().getUsed());
        assertEquals(0, vo.getQuota().getDerivation().getUsed());
        assertEquals(0L, vo.getQuota().getStorage().getUsedMb());
        assertNull(vo.getQuota().getToken().getAlertThreshold());
    }

    @Test
    void 绑定套餐行逻辑删_套餐卡null_不抛异常() {
        when(tenantMapper.selectById(1L)).thenReturn(paidTenant("pro"));
        when(quotaMapper.selectOne(any())).thenReturn(watermarkQuota());
        when(planMapper.selectList(any())).thenReturn(List.of());

        CostCenterVo vo = service.summary(1L);

        assertNull(vo.getPlan(), "套餐行不可见（逻辑删）→ 套餐卡 null + WARN，不抛异常");
        assertNotNull(vo.getQuota());
    }
}
