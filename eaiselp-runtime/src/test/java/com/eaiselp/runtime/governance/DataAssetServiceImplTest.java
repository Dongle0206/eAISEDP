package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DataAssetServiceImpl 单测（case-20260820 T4，SE §9.1 锚点 22~25）。
 *
 * <p>纯 Mockito 方式（hierarchy 包先例）。覆盖：
 * <ul>
 *   <li>锚点 22：uk(tenant,system,name) 同系统同名拒 / 跨系统同名合法（AC-F2.1）；</li>
 *   <li>锚点 23：枚举非法值（view/secret）400 指名 + 5×4 全组合合法（AC-F2.2）；</li>
 *   <li>锚点 24：类型/等级进 SQL、标签内存过滤、关键字 name+system（AC-F2.3/F2.4）；</li>
 *   <li>锚点 25：删除联动规则逻辑删 + 审计 ruleIds 可辨识（AC-F2.7）。</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataAssetServiceImplTest {

    @Mock DataAssetMapper baseMapper;
    @Mock DataQualityRuleMapper dqRuleMapper;
    @Mock AuditService auditService;

    @InjectMocks DataAssetServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataAsset.class);
        TableInfoHelper.initTableInfo(assistant, DataQualityRule.class);
    }

    @BeforeEach
    void injectBaseMapper() throws Exception {
        java.lang.reflect.Field f = DataAssetServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    // ==================== 构造工具 ====================

    private static DataAsset asset(String system, String name, String type, String sens) {
        DataAsset a = new DataAsset();
        a.setSystemName(system);
        a.setAssetName(name);
        a.setAssetType(type);
        a.setSensitivity(sens);
        a.setOwner("张三");
        return a;
    }

    private static DataAsset stored(Long id, String system, String name, String sens, String tagsJson) {
        DataAsset a = asset(system, name, "table", sens);
        a.setId(id);
        a.setTags(tagsJson);
        return a;
    }

    private void stubInsertOk() {
        when(baseMapper.insert(any(DataAsset.class))).thenAnswer(inv -> {
            DataAsset a = inv.getArgument(0);
            a.setId(9300L);
            return 1;
        });
    }

    // ==================== 锚点 22：uk（AC-F2.1） ====================

    @Test
    void 同系统同名被拒_uk冲突统一形态() {
        when(baseMapper.insert(any(DataAsset.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_asset_tenant_system_name"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(asset("ERP", "t_order", "table", "sensitive")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"), "统一形态，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("ERP/t_order"));
    }

    @Test
    void 跨系统同名合法() {
        stubInsertOk();

        DataAsset created = service.create(asset("CRM", "t_order", "table", "internal"));

        assertNotNull(created.getId());
        verify(auditService).log(eq("asset_create"), eq("asset"), anyString(), anyString());
    }

    // ==================== 锚点 23：枚举校验（AC-F2.2） ====================

    @Test
    void 资产类型非法_400指名字段与合法值集() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(asset("ERP", "t_order", "view", "sensitive")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("assetType"), "指名字段，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("database/table/api/report/file"), "指名合法值集合");
    }

    @Test
    void 敏感等级非法_400指名字段与合法值集() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(asset("ERP", "t_order", "table", "secret")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("sensitivity"));
        assertTrue(ex.getMessage().contains("public/internal/sensitive/confidential"));
    }

    @Test
    void 枚举5乘4全组合_创建合法且详情回显一致() {
        stubInsertOk();
        String[] types = {"database", "table", "api", "report", "file"};
        String[] sens = {"public", "internal", "sensitive", "confidential"};

        for (String t : types) {
            for (String s : sens) {
                DataAsset created = assertDoesNotThrow(() ->
                        service.create(asset("ERP", "a_" + t + "_" + s, t, s)),
                        "合法组合被拒: " + t + "/" + s);
                assertEquals(t, created.getAssetType());
                assertEquals(s, created.getSensitivity());
            }
        }
        verify(baseMapper, times(20)).insert(any(DataAsset.class));
    }

    // ==================== 锚点 24：四维筛选（AC-F2.3/F2.4） ====================

    @Test
    void 类型与等级筛选进SQL_关键字name加system() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<DataAsset> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });

        service.pageFilter("api", "confidential", null, "订单", 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<DataAsset>> wc = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(), wc.capture());
        String sql = wc.getValue().getSqlSegment().toLowerCase().replace("_", "");
        assertTrue(sql.contains("assettype"), "类型筛选，实际: " + sql);
        assertTrue(sql.contains("sensitivity"), "等级筛选");
        assertTrue(sql.contains("assetname"), "关键字命中资产名");
        assertTrue(sql.contains("systemname"), "关键字命中所属系统");
    }

    @Test
    void 标签筛选_单选命中内存过滤() {
        DataAsset a1 = stored(3301L, "ERP", "t_order", "sensitive", "[\"客户数据\",\"日报\"]");
        DataAsset a2 = stored(3302L, "CRM", "t_customer", "internal", "[\"客户数据\"]");
        DataAsset a3 = stored(3303L, "OA", "t_report", "public", "[\"周报\"]");
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<DataAsset> p = inv.getArgument(0);
            p.setRecords(List.of(a1, a2, a3));
            p.setTotal(3);
            return p;
        });

        assertEquals(2, service.pageFilter(null, null, "客户数据", null, 1, 20)
                .getRecords().size(), "标签「客户数据」命中两条（AC-F2.4）");
        assertEquals(1, service.pageFilter(null, null, "日报", null, 1, 20)
                .getRecords().size(), "标签「日报」命中一条");
        assertEquals(0, service.pageFilter(null, null, "月报", null, 1, 20)
                .getRecords().size(), "未录入标签不命中");
    }

    @Test
    void Vo_标签JSON解析还原() {
        var vo = service.toVo(stored(3301L, "ERP", "t_order", "sensitive", "[\"客户数据\",\"日报\"]"));
        assertEquals(List.of("客户数据", "日报"), vo.getTags());
        assertEquals(List.of(), service.toVo(stored(3302L, "ERP", "x", "public", null)).getTags(),
                "无标签 → 空列表");
    }

    // ==================== 锚点 25：删除联动（AC-F2.7） ====================

    @Test
    void 资产逻辑删_关联规则同步逻辑删_审计含ruleIds() {
        DataAsset exist = stored(3301L, "ERP", "t_order", "sensitive", null);
        when(baseMapper.selectById(3301L)).thenReturn(exist);
        when(baseMapper.deleteById(3301L)).thenReturn(1);
        DataQualityRule r1 = new DataQualityRule();
        r1.setId(4401L);
        r1.setAssetId(3301L);
        DataQualityRule r2 = new DataQualityRule();
        r2.setId(4402L);
        r2.setAssetId(3301L);
        when(dqRuleMapper.selectList(any())).thenReturn(List.of(r1, r2));

        service.remove(3301L);

        // MP IService.removeById → baseMapper.deleteById(entity)（逻辑删路径，实测路由到实体重载）
        verify(baseMapper).deleteById(any(DataAsset.class));
        verify(dqRuleMapper).delete(any());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("asset_delete"), eq("asset"), eq("3301"), detail.capture());
        String json = detail.getValue();
        assertTrue(json.contains("\"ruleIds\":[4401,4402]"), "审计 detail 含联动 ruleIds 数组可辨识，实际: " + json);
    }

    @Test
    void 资产逻辑删_无关联规则_不触发联动删除() {
        when(baseMapper.selectById(3301L)).thenReturn(stored(3301L, "ERP", "t_order", "sensitive", null));
        when(baseMapper.deleteById(3301L)).thenReturn(1);
        when(dqRuleMapper.selectList(any())).thenReturn(List.of());

        service.remove(3301L);

        verify(dqRuleMapper, never()).delete(any());
        verify(auditService).log(eq("asset_delete"), eq("asset"), eq("3301"), anyString());
    }

    /**
     * S3（评审）：编辑清空标签与可空文本——①tags 前端提交空数组 → Controller toJson([])
     * 返回 {@code "[]}（非 null）→ updateById 落库；②owner/description 置 null 提交时
     * MP updateById 忽略 null → 由 UpdateWrapper 显式 set null 补清空（baseMapper.update 第二参）。
     */
    @Test
    void 编辑清空标签与可空文本_空数组与显式null落库() {
        when(baseMapper.selectById(3301L))
                .thenReturn(stored(3301L, "ERP", "t_order", "sensitive", "[\"核心\",\"订单\"]"));
        when(baseMapper.updateById(any(DataAsset.class))).thenReturn(1);
        when(baseMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        DataAsset patch = asset("ERP", "t_order", "table", "sensitive");
        patch.setOwner(null);        // 清空 owner（null 提交）
        patch.setDescription(null);  // 清空 description（null 提交）
        patch.setTags("[]");         // 模拟 Controller toEntity：PUT tags=[] → toJson "[]"（S3）
        service.edit(3301L, patch);

        // 1. 主体 updateById：tags "[]" 非 null 正常落库（清空标签生效）
        ArgumentCaptor<DataAsset> captor = ArgumentCaptor.forClass(DataAsset.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("[]", captor.getValue().getTags(),
                "清空标签必须落 \"[]\"（S3：null 会被 MP updateById 忽略导致清空失效）");
        // 2. owner/description 为 null → 追加 UpdateWrapper 显式 set null（baseMapper.update 被调）
        verify(baseMapper, times(1)).update(any(), any(Wrapper.class));
    }

    /** S3 反向：owner/description 均非 null 时不追加 UpdateWrapper（单条 updateById 足够，零行为回归）。 */
    @Test
    void 编辑可空文本非空_不追加显式null更新() {
        when(baseMapper.selectById(3301L)).thenReturn(stored(3301L, "ERP", "t_order", "sensitive", null));
        when(baseMapper.updateById(any(DataAsset.class))).thenReturn(1);

        DataAsset patch = asset("ERP", "t_order", "table", "sensitive");
        patch.setOwner("李四");
        patch.setDescription("订单主表");
        service.edit(3301L, patch);

        verify(baseMapper).updateById(any(DataAsset.class));
        verify(baseMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void 详情聚合_规则数与各规则最近结果() {
        when(baseMapper.selectById(3301L)).thenReturn(stored(3301L, "ERP", "t_order", "sensitive", null));
        DataQualityRule r1 = new DataQualityRule();
        r1.setId(4401L);
        r1.setRuleName("订单表完整性");
        r1.setCheckType("completeness");
        r1.setThreshold(java.math.BigDecimal.valueOf(99.5));
        r1.setLastResult("fail");
        r1.setLastActualValue(java.math.BigDecimal.valueOf(97.2));
        when(dqRuleMapper.selectList(any())).thenReturn(List.of(r1));

        var vo = service.detailVo(3301L);

        assertNotNull(vo.getRules());
        assertEquals(1, vo.getRules().getCount());
        assertEquals("订单表完整性", vo.getRules().getItems().get(0).getRuleName());
        assertEquals("fail", vo.getRules().getItems().get(0).getLastResult(), "AC-F2.6 Then：详情展示规则及 fail 结果");
    }
}
