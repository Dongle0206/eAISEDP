package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.governance.dto.DataQualityRuleVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * DataQualityRuleServiceImpl 单测（case-20260820 T5，SE §9.1 锚点 26~27）。
 *
 * <p>纯 Mockito 方式（hierarchy 包先例）。覆盖：
 * <ul>
 *   <li>锚点 26：uk 同名拒 / assetId 不存在或已删 400 / checkType 非法 400 /
 *       threshold 100.5 与 −1 → 400、边界 0 与 100 合法（AC-F2.5/F2.7）；</li>
 *   <li>锚点 27：登记覆盖式更新 last_* 四列 + 审计旧值→新值+登记人（AC-F2.6）。</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataQualityRuleServiceImplTest {

    @Mock DataQualityRuleMapper baseMapper;
    @Mock DataAssetMapper assetMapper;
    @Mock AuditService auditService;

    @InjectMocks DataQualityRuleServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataQualityRule.class);
        TableInfoHelper.initTableInfo(assistant, DataAsset.class);
    }

    @BeforeEach
    void injectBaseMapper() throws Exception {
        java.lang.reflect.Field f = DataQualityRuleServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
        // 登记审计 detail 的"登记人"取自 LoginUser（AC-F2.6）；测试显式注入
        LoginUser.set(JwtClaims.builder().userId(7L).username("qa").tenantId(1L).build());
    }

    @AfterEach
    void clearLoginUser() {
        LoginUser.clear();
    }

    // ==================== 构造工具 ====================

    private static DataQualityRule rule(String name, Long assetId, String type, String threshold) {
        DataQualityRule r = new DataQualityRule();
        r.setRuleName(name);
        r.setAssetId(assetId);
        r.setCheckType(type);
        r.setThreshold(threshold != null ? new BigDecimal(threshold) : null);
        return r;
    }

    private void stubAssetExists(Long assetId) {
        DataAsset a = new DataAsset();
        a.setId(assetId);
        a.setAssetName("t_order");
        a.setSystemName("ERP");
        when(assetMapper.selectById(assetId)).thenReturn(a);
        when(assetMapper.selectBatchIds(any())).thenReturn(List.of(a));   // 列表/详情摘要批量装配
    }

    private void stubInsertOk() {
        when(baseMapper.insert(any(DataQualityRule.class))).thenAnswer(inv -> {
            DataQualityRule r = inv.getArgument(0);
            r.setId(4401L);
            return 1;
        });
    }

    // ==================== 锚点 26：CRUD 校验链（AC-F2.5/F2.7） ====================

    @Test
    void 同名规则被拒_uk冲突统一形态() {
        stubAssetExists(3301L);
        when(baseMapper.insert(any(DataQualityRule.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_dqr_tenant_name"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(rule("订单表完整性", 3301L, "completeness", "99.5")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"), "统一形态，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("订单表完整性"));
    }

    @Test
    void 资产id不存在_400() {
        when(assetMapper.selectById(99999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(rule("订单表完整性", 99999L, "completeness", "99.5")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("99999"), "指名资产 id，实际: " + ex.getMessage());
        verify(baseMapper, never()).insert(any(DataQualityRule.class));
    }

    @Test
    void 已逻辑删资产id_400_不区分不存在() {
        // MP 逻辑删下 selectById 对已删行返回 null——与"不存在"同一 400 语义（AC-F2.7/Q2 端校验承载）
        when(assetMapper.selectById(3301L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(rule("订单表完整性", 3301L, "completeness", "99.5")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在或已删除"));
    }

    @Test
    void 检查类型非法_400指名字段与合法值集() {
        stubAssetExists(3301L);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(rule("订单表完整性", 3301L, "timeliness_", "99.5")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("checkType"), "指名字段，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("completeness/accuracy/consistency/timeliness"), "指名合法值集合");
    }

    @Test
    void 阈值越界_100_5与负1被拒() {
        stubAssetExists(3301L);

        BizException over = assertThrows(BizException.class,
                () -> service.create(rule("r1", 3301L, "completeness", "100.5")));
        assertEquals(400, over.getCode());
        assertTrue(over.getMessage().contains("threshold"));

        BizException neg = assertThrows(BizException.class,
                () -> service.create(rule("r2", 3301L, "completeness", "-1")));
        assertEquals(400, neg.getCode());
        verify(baseMapper, never()).insert(any(DataQualityRule.class));
    }

    @Test
    void 阈值边界_0与100合法() {
        stubAssetExists(3301L);
        stubInsertOk();

        assertDoesNotThrow(() -> service.create(rule("r_zero", 3301L, "completeness", "0")));
        assertDoesNotThrow(() -> service.create(rule("r_hundred", 3301L, "completeness", "100")));
        verify(baseMapper, times(2)).insert(any(DataQualityRule.class));
    }

    @Test
    void 创建清空最近结果_从未登记为null() {
        stubAssetExists(3301L);
        stubInsertOk();
        DataQualityRule dirty = rule("r_new", 3301L, "completeness", "99.5");
        dirty.setLastResult("pass");   // 恶意/脏入参——创建语义为"从未登记"

        DataQualityRule created = service.create(dirty);

        verify(baseMapper).insert(any(DataQualityRule.class));
        // insert 捕获实体已被清空（对 captor 断言）
        ArgumentCaptor<DataQualityRule> captor = ArgumentCaptor.forClass(DataQualityRule.class);
        verify(baseMapper).insert(captor.capture());
        assertNull(captor.getValue().getLastResult());
        assertNull(captor.getValue().getLastCheckTime());
    }

    @Test
    void 列表筛选_类型结果资产id进SQL_资产摘要批量装配() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<DataQualityRule> p = inv.getArgument(0);
            DataQualityRule r = rule("订单表完整性", 3301L, "completeness", "99.5");
            r.setId(4401L);
            p.setRecords(List.of(r));
            p.setTotal(1);
            return p;
        });
        stubAssetExists(3301L);

        var page = service.pageFilter("completeness", "pass", 3301L, "订单", 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<DataQualityRule>> wc = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(), wc.capture());
        String sql = wc.getValue().getSqlSegment().toLowerCase().replace("_", "");
        assertTrue(sql.contains("checktype"), "类型筛选，实际: " + sql);
        assertTrue(sql.contains("lastresult"), "结果筛选");
        assertTrue(sql.contains("assetid"), "资产 id 筛选");
        assertTrue(sql.contains("rulename"), "关键字 ruleName LIKE");

        assertEquals(1, page.getRecords().size());
        DataQualityRuleVo vo = page.getRecords().get(0);
        assertEquals("t_order", vo.getAssetName(), "关联资产摘要装配");
        assertEquals("ERP", vo.getAssetSystemName());
    }

    // ==================== 锚点 27：登记覆盖式更新（AC-F2.6） ====================

    @Test
    void 登记非法结果_400() {
        when(baseMapper.selectById(4401L)).thenReturn(rule("订单表完整性", 3301L, "completeness", "99.5"));

        BizException ex = assertThrows(BizException.class,
                () -> service.registerCheckResult(4401L, "warn", null, null, null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("pass/fail"), "登记人判定仅 pass|fail，实际: " + ex.getMessage());
    }

    @Test
    void 登记覆盖式更新_四列set_审计旧值到新值加登记人() {
        DataQualityRule old = rule("订单表完整性", 3301L, "completeness", "99.5");
        old.setId(4401L);
        old.setLastResult("pass");
        old.setLastActualValue(new BigDecimal("99.8"));
        old.setLastCheckTime(LocalDateTime.of(2026, 8, 19, 9, 0, 0));
        LocalDateTime t2 = LocalDateTime.of(2026, 8, 20, 9, 0, 0);
        DataQualityRule updated = rule("订单表完整性", 3301L, "completeness", "99.5");
        updated.setId(4401L);
        updated.setLastResult("fail");
        updated.setLastActualValue(new BigDecimal("97.2"));
        updated.setLastCheckTime(t2);
        updated.setLastCheckRemark("字段缺失");
        // 登记内部两次 getById：先读旧值、更新后回读新值
        when(baseMapper.selectById(4401L)).thenReturn(old).thenReturn(updated);
        when(baseMapper.update(isNull(), any())).thenReturn(1);
        stubAssetExists(3301L);

        DataQualityRuleVo vo = service.registerCheckResult(4401L, "fail",
                new BigDecimal("97.2"), t2, "字段缺失");

        // 覆盖式更新：LambdaUpdateWrapper 显式 set last_* 四列（含可空列）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DataQualityRule>> wc =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(baseMapper).update(isNull(), wc.capture());
        String sqlSet = wc.getValue().getSqlSet().toLowerCase().replace("_", "");
        assertTrue(sqlSet.contains("lastresult"), "set last_result，实际: " + sqlSet);
        assertTrue(sqlSet.contains("lastactualvalue"), "set last_actual_value");
        assertTrue(sqlSet.contains("lastchecktime"), "set last_check_time");
        assertTrue(sqlSet.contains("lastcheckremark"), "set last_check_remark");
        assertTrue(wc.getValue().getParamNameValuePairs().containsValue("fail"));
        assertTrue(wc.getValue().getParamNameValuePairs().containsValue(new BigDecimal("97.2")));

        // 审计旧值→新值 + 登记人（"旧值不覆盖审计"，AC-F2.6）
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("dqrule_check_result"), eq("dqrule"), eq("4401"), detail.capture());
        String json = detail.getValue();
        assertTrue(json.contains("99.8"), "旧实测值留痕，实际: " + json);
        assertTrue(json.contains("97.2"), "新实测值");
        assertTrue(json.contains("字段缺失"));
        assertTrue(json.contains("\"operator\":\"qa\""), "登记人（LoginUser.username）");

        assertEquals("fail", vo.getLastResult());
        assertEquals(new BigDecimal("97.2"), vo.getLastActualValue());
    }

    @Test
    void 登记时间缺省当前时刻() {
        DataQualityRule old = rule("订单表完整性", 3301L, "completeness", "99.5");
        old.setId(4401L);
        when(baseMapper.selectById(4401L)).thenReturn(old);
        when(baseMapper.update(isNull(), any())).thenReturn(1);
        stubAssetExists(3301L);
        LocalDateTime before = LocalDateTime.now();

        service.registerCheckResult(4401L, "pass", null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DataQualityRule>> wc =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(baseMapper).update(isNull(), wc.capture());
        assertTrue(wc.getValue().getParamNameValuePairs().values().stream()
                        .anyMatch(v -> v instanceof LocalDateTime t && !t.isBefore(before)),
                "checkTime 缺省取当前时刻");
    }
}
