package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.governance.dto.StandardVo;
import com.eaiselp.runtime.hierarchy.ArchitecturePrinciple;
import com.eaiselp.runtime.hierarchy.ArchitecturePrincipleMapper;
import com.eaiselp.runtime.hierarchy.QualityGateRule;
import com.eaiselp.runtime.hierarchy.QualityGateRuleMapper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * StandardServiceImpl 单测（case-20260820 T2，SE §9.1 锚点 10/11/14/15）。
 *
 * <p>纯 Mockito 方式（对齐 hierarchy 包先例，@SpringBootTest 全模块无先例）：
 * <ul>
 *   <li>锚点 10：uk(tenant,code,version) 同编号同版本拒 / 异版本共存（AC-F1.1）；</li>
 *   <li>锚点 11：编号缺省生成 STD-NNNN 续推 + 冲突重试 ≤3（AC-F1.1）；</li>
 *   <li>锚点 14：draft 可编辑、published/deprecated 编辑拒、逻辑删+审计（AC-F1.5）；</li>
 *   <li>锚点 15：原则 P99 不存在 400 / 停用门禁 400 / 空数组合法（AC-F1.6+§4.5 翻译）。</li>
 * </ul>
 * 批B T12/T13 增补（SE §9.1）：
 * <ul>
 *   <li>锚点 12：状态机全路径——draft→published、published→deprecated（无原因 400）、
 *       draft→deprecated、deprecated→published 400、published→draft 400（AC-F1.2）；</li>
 *   <li>锚点 13：发布自动取代——FOR UPDATE 查询路径 + 旧版自动 deprecated（原因含
 *       "被 {code} {新版本} 取代"）+ 双审计 + 至多一个 published（AC-F1.4）；</li>
 *   <li>锚点 16：逻辑删后 gateName 反查走 D-9 手写 SQL 返回"已删除"占位行（AC-F1.7）；
 *       另含 S3 详情被引用门禁解析（悬空 name 占位）。</li>
 * </ul></p>
 *
 * <p>注：Mapper mock 字段名必须为 baseMapper——ServiceImpl.baseMapper 按 field 名注入
 * （MilestoneServiceImplTransitTest 先例：@RequiredArgsConstructor 构造注入后 baseMapper
 * 字段需反射补注）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StandardServiceImplTest {

    @Mock StandardMapper baseMapper;
    @Mock ArchitecturePrincipleMapper principleMapper;
    @Mock QualityGateRuleMapper gateRuleMapper;
    @Mock AuditService auditService;

    @InjectMocks StandardServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Standard.class);
        TableInfoHelper.initTableInfo(assistant, ArchitecturePrinciple.class);
        TableInfoHelper.initTableInfo(assistant, QualityGateRule.class);
    }

    @BeforeEach
    void injectBaseMapper() throws Exception {
        // ServiceImpl.baseMapper（protected 泛型字段）显式注入（构造注入不覆盖父类字段，先例同）
        java.lang.reflect.Field f = StandardServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    // ==================== 构造工具 ====================

    private static Standard std(String code, String version) {
        Standard s = new Standard();
        s.setStandardCode(code);
        s.setVersion(version);
        s.setTitle("接口设计规范");
        s.setContent("# 规范正文");
        return s;
    }

    private static Standard stored(String code, String version, String status) {
        Standard s = std(code, version);
        s.setId(1932L);
        s.setStatus(status);
        return s;
    }

    /** 关联校验中性桩：原则均存在、门禁均启用（具体用例按需覆盖）。 */
    private void stubAssociationsOk() {
        when(principleMapper.selectCount(any())).thenReturn(1L);
        QualityGateRule rule = new QualityGateRule();
        rule.setName("接口文档完备性检查");
        rule.setEnabled(1);
        when(gateRuleMapper.selectOne(any())).thenReturn(rule);
    }

    private void stubInsertOk() {
        when(baseMapper.insert(any(Standard.class))).thenAnswer(inv -> {
            Standard s = inv.getArgument(0);
            s.setId(9000L);
            return 1;
        });
    }

    // ==================== 锚点 10：uk(tenant,code,version)（AC-F1.1） ====================

    @Test
    void 同编号同版本创建被拒_uk冲突统一形态() {
        stubAssociationsOk();
        when(baseMapper.insert(any(Standard.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("uk_std_tenant_code_version"));

        BizException ex = assertThrows(BizException.class, () -> service.create(std("STD-0001", "v1.0")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"), "uk 冲突统一形态「...已存在: ...」，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("STD-0001"));
        verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 同编号异版本共存_创建成功且状态draft() {
        stubAssociationsOk();
        stubInsertOk();

        Standard created = service.create(std("STD-0001", "v2.0"));

        assertEquals("draft", created.getStatus(), "创建固定 draft（S2）");
        assertNotNull(created.getId());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("standard_create"), eq("standard"), anyString(), detail.capture());
        assertTrue(detail.getValue().contains("STD-0001"));
        assertTrue(detail.getValue().contains("v2.0"));
    }

    // ==================== 锚点 11：编号缺省生成 STD-NNNN（AC-F1.1） ====================

    @Test
    void 编号缺省生成_四位续推() {
        stubAssociationsOk();
        stubInsertOk();
        when(baseMapper.selectList(any())).thenReturn(List.of(stored("STD-0001", "v1.0", "draft"),
                stored("STD-0002", "v1.0", "published"), stored("CUSTOM-X", "v1", "draft")));

        Standard created = service.create(std(null, "v1.0"));

        assertEquals("STD-0003", created.getStandardCode(), "STD-%04d 续推最大可见序号（自定义编号不参与）");
    }

    @Test
    void 编号生成冲突_重试后成功() {
        stubAssociationsOk();
        when(baseMapper.selectList(any())).thenReturn(List.of(stored("STD-0001", "v1.0", "draft")));
        org.mockito.stubbing.Answer<Integer> failOnceThenOk = inv -> {
            Standard s = inv.getArgument(0);
            if ("STD-0002".equals(s.getStandardCode())) {
                throw new org.springframework.dao.DuplicateKeyException("uk");
            }
            s.setId(9001L);
            return 1;
        };
        when(baseMapper.insert(any(Standard.class))).thenAnswer(failOnceThenOk);

        Standard created = service.create(std(null, "v1.0"));

        assertEquals("STD-0003", created.getStandardCode(), "首次 STD-0002 冲突后重试 +1 生成 STD-0003");
    }

    @Test
    void 编号生成冲突_重试耗尽400() {
        stubAssociationsOk();
        when(baseMapper.selectList(any())).thenReturn(List.of(stored("STD-0001", "v1.0", "draft")));
        when(baseMapper.insert(any(Standard.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("uk"));

        BizException ex = assertThrows(BizException.class, () -> service.create(std(null, "v1.0")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("冲突"));
        verify(baseMapper, times(4)).insert(any(Standard.class));   // 初次 + 重试 ≤3
    }

    // ==================== 锚点 14：编辑限制与逻辑删（AC-F1.5） ====================

    @Test
    void draft全字段可编辑() {
        stubAssociationsOk();
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "draft"));
        when(baseMapper.updateById(any(Standard.class))).thenReturn(1);

        Standard patch = std("STD-0001", "v1.0");
        patch.setContent("# 新正文");
        Standard updated = service.edit(1932L, patch);

        ArgumentCaptor<Standard> captor = ArgumentCaptor.forClass(Standard.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("# 新正文", captor.getValue().getContent());
        assertNotNull(updated);
        verify(auditService).log(eq("standard_update"), eq("standard"), eq("1932"), anyString());
    }

    /**
     * S3（评审）：编辑清空关联原则/门禁——前端提交空数组，Controller toJson([]) 现返回
     * {@code "[]}（非 null）→ updateById 不忽略、清空可落库（原 null 会让清空静默失效回显旧值）。
     */
    @Test
    void 编辑清空关联原则与门禁_空数组落库() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "draft"));
        when(baseMapper.updateById(any(Standard.class))).thenReturn(1);

        Standard patch = std("STD-0001", "v1.1");
        // 模拟 Controller toEntity：PUT 提交 relatedPrincipleCodes=[] / relatedGateNames=[]
        // → toJson(空列表) 返回 "[]"（S3 修正，null 保留不更新语义）
        patch.setRelatedPrincipleCodes("[]");
        patch.setRelatedGateNames("[]");
        service.edit(1932L, patch);

        ArgumentCaptor<Standard> captor = ArgumentCaptor.forClass(Standard.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("[]", captor.getValue().getRelatedPrincipleCodes(),
                "清空关联原则必须落 \"[]\"（S3：null 会被 MP updateById 忽略导致清空失效）");
        assertEquals("[]", captor.getValue().getRelatedGateNames(),
                "清空关联门禁必须落 \"[]\"（S3）");
    }

    @Test
    void published编辑任意字段被拒_提示升版() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "published"));

        BizException ex = assertThrows(BizException.class,
                () -> service.edit(1932L, std("STD-0001", "v1.0")));
        assertEquals(400, ex.getCode());
        assertEquals("发布后不可编辑，请升版", ex.getMessage());
        verify(baseMapper, never()).updateById(any(Standard.class));
    }

    @Test
    void deprecated编辑被拒() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "deprecated"));

        BizException ex = assertThrows(BizException.class,
                () -> service.edit(1932L, std("STD-0001", "v1.0")));
        assertEquals(400, ex.getCode());
        verify(baseMapper, never()).updateById(any(Standard.class));
    }

    @Test
    void 逻辑删后列表不可见路径_删除写审计() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "published"));
        when(baseMapper.deleteById(1932L)).thenReturn(1);

        service.remove(1932L);

        // MP IService.removeById → baseMapper.deleteById(entity)（逻辑删路径，实测路由到实体重载）
        verify(baseMapper).deleteById(any(Standard.class));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("standard_delete"), eq("standard"), eq("1932"), detail.capture());
        assertTrue(detail.getValue().contains("STD-0001"));
    }

    @Test
    void 不存在或跨租户_404() {
        when(baseMapper.selectById(9999L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.loadOr404(9999L));
        assertEquals(404, ex.getCode());
    }

    // ==================== 锚点 15：关联存在性校验（AC-F1.6 + §4.5 翻译） ====================

    @Test
    void 关联原则不存在_400指名() {
        when(principleMapper.selectCount(any())).thenReturn(0L);
        Standard s = std("STD-0009", "v1.0");
        s.setRelatedPrincipleCodes("[\"P99\"]");

        BizException ex = assertThrows(BizException.class, () -> service.create(s));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("P99"), "指名不存在的原则 code，实际: " + ex.getMessage());
        verify(baseMapper, never()).insert(any(Standard.class));
    }

    @Test
    void 关联门禁不存在_400指名() {
        stubAssociationsOk();
        when(gateRuleMapper.selectOne(any())).thenReturn(null);
        Standard s = std("STD-0009", "v1.0");
        s.setRelatedGateNames("[\"不存在的规则\"]");

        BizException ex = assertThrows(BizException.class, () -> service.create(s));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
        assertTrue(ex.getMessage().contains("不存在的规则"));
    }

    @Test
    void 关联门禁已停用_400指名() {
        stubAssociationsOk();
        QualityGateRule disabled = new QualityGateRule();
        disabled.setName("已停用规则");
        disabled.setEnabled(0);
        when(gateRuleMapper.selectOne(any())).thenReturn(disabled);
        Standard s = std("STD-0009", "v1.0");
        s.setRelatedGateNames("[\"已停用规则\"]");

        BizException ex = assertThrows(BizException.class, () -> service.create(s));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已停用"), "停用规则不允许关联（AC-F1.3 翻译口径），实际: " + ex.getMessage());
    }

    @Test
    void 关联空数组合法_不触发校验() {
        stubInsertOk();
        Standard s = std("STD-0009", "v1.0");
        s.setRelatedPrincipleCodes("[]");
        s.setRelatedGateNames(null);

        assertDoesNotThrow(() -> service.create(s));
        verify(principleMapper, never()).selectCount(any());
        verify(gateRuleMapper, never()).selectOne(any());
    }

    // ==================== 列表缺省口径（AC-F1.2 数据侧） ====================

    @Test
    void 列表缺省双状态_显式deprecated可见() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Standard> p = inv.getArgument(0);
            p.setRecords(List.of(stored("STD-0001", "v1.0", "draft")));
            p.setTotal(1);
            return p;
        });

        // 缺省（status=null）：Service 兜底 draft+published（断言 wrapper 含 status IN 过滤）
        service.pageFilter(null, null, null, 1, 20);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Standard>> w1 = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper, times(1)).selectPage(any(), w1.capture());
        String sql1 = w1.getValue().getSqlSegment().toLowerCase().replace("_", "");
        assertTrue(sql1.contains("status"), "缺省走状态 IN 过滤（draft+published 双值），实际: " + sql1);

        // 显式 deprecated：透传（deprecated 需显式筛选可见，AC-F1.2）
        service.pageFilter("deprecated", null, null, 1, 20);
        verify(baseMapper, times(2)).selectPage(any(), any());
    }

    @Test
    void 原则筛选_内存过滤命中() {
        Standard hit = stored("STD-0001", "v1.0", "draft");
        hit.setRelatedPrincipleCodes("[\"P3\",\"P11\"]");
        Standard miss = stored("STD-0002", "v1.0", "draft");
        miss.setRelatedPrincipleCodes("[\"P7\"]");
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Standard> p = inv.getArgument(0);
            p.setRecords(List.of(hit, miss));
            p.setTotal(2);
            return p;
        });

        var page = service.pageFilter(null, "P3", null, 1, 20);

        assertEquals(1, page.getRecords().size());
        assertEquals("STD-0001", page.getRecords().get(0).getStandardCode());
        StandardVo vo = page.getRecords().get(0);
        assertEquals(List.of("P3", "P11"), vo.getRelatedPrincipleCodes(), "JSON 解析还原");
    }

    // ==================== 锚点 12：状态机全路径（批B T12，AC-F1.2） ====================

    private void stubUpdateOk() {
        when(baseMapper.updateById(any(Standard.class))).thenReturn(1);
    }

    @Test
    void 状态机_draft发布成功_写transit审计() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "draft"));
        when(baseMapper.selectOne(any())).thenReturn(null);   // 无现行 published → 首发不触发取代
        stubUpdateOk();

        StandardVo vo = service.transit(1932L, "published", null);

        ArgumentCaptor<Standard> captor = ArgumentCaptor.forClass(Standard.class);
        verify(baseMapper, times(1)).updateById(captor.capture());
        assertEquals("published", captor.getValue().getStatus());
        verify(auditService).log(eq("standard_transit"), eq("standard"), eq("1932"), anyString());
        verify(auditService, never()).log(eq("standard_auto_deprecate"), anyString(), anyString(), anyString());
        assertEquals("draft", vo.getStatus(), "Vo 为 stub 回显值（更新落库由 updateById 断言承载）");
    }

    @Test
    void 状态机_published废弃无原因_400() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "published"));

        BizException ex = assertThrows(BizException.class, () -> service.transit(1932L, "deprecated", null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("deprecateReason"), "废弃必填原因，实际: " + ex.getMessage());
        verify(baseMapper, never()).updateById(any(Standard.class));
    }

    @Test
    void 状态机_published废弃带原因_原因落列() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "published"));
        stubUpdateOk();

        service.transit(1932L, "deprecated", "内容过时");

        ArgumentCaptor<Standard> captor = ArgumentCaptor.forClass(Standard.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("deprecated", captor.getValue().getStatus());
        assertEquals("内容过时", captor.getValue().getDeprecateReason(), "废弃原因落列（V6 纠偏，详情列直读）");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("standard_transit"), eq("standard"), eq("1932"), detail.capture());
        assertTrue(detail.getValue().contains("内容过时"));
        assertTrue(detail.getValue().contains("published"));
        assertTrue(detail.getValue().contains("deprecated"));
    }

    @Test
    void 状态机_draft作废带原因成功() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "draft"));
        stubUpdateOk();

        service.transit(1932L, "deprecated", "不再适用");

        ArgumentCaptor<Standard> captor = ArgumentCaptor.forClass(Standard.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("deprecated", captor.getValue().getStatus());
        assertEquals("不再适用", captor.getValue().getDeprecateReason());
    }

    @Test
    void 状态机_deprecated终态不可流转_400() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "deprecated"));

        BizException ex = assertThrows(BizException.class, () -> service.transit(1932L, "published", null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("终态"), "deprecated 终态无出边，实际: " + ex.getMessage());
        verify(baseMapper, never()).updateById(any(Standard.class));
    }

    @Test
    void 状态机_published回draft非法_400() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "published"));

        BizException ex = assertThrows(BizException.class, () -> service.transit(1932L, "draft", null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("非法状态流转"), "published→draft 拒绝，实际: " + ex.getMessage());
        verify(baseMapper, never()).updateById(any(Standard.class));
    }

    @Test
    void 状态机_目标状态未知_400() {
        BizException ex = assertThrows(BizException.class, () -> service.transit(1932L, "archived", null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("未知状态"));
    }

    @Test
    void 状态机_同态流转幂等短路_不更新不审计() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "published"));

        StandardVo vo = service.transit(1932L, "published", null);

        assertNotNull(vo);
        verify(baseMapper, never()).updateById(any(Standard.class));
        verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    // ==================== 锚点 13：发布自动取代（批B T12，AC-F1.4，D-7） ====================

    /**
     * O3（评审）注：本用例为纯 Mockito 断言——锁行为（MySQL InnoDB 行级锁真实生效性、
     * 并发竞态下至多一个 published）以真库验证为准，此处断言 SQL 形态（FOR UPDATE 片段）
     * 与事务内取代顺序（先旧版 deprecated 后新版 published）、双审计链。
     */
    @Test
    void 发布新版自动取代旧版_双审计_至多一个published() {
        // 现行 published v1.0（id 1900）+ 待发布 draft v2.0（id 1932，同编号 STD-0001）
        Standard old = stored("STD-0001", "v1.0", "published");
        old.setId(1900L);
        when(baseMapper.selectOne(any())).thenReturn(old);
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v2.0", "draft"));
        stubUpdateOk();

        service.transit(1932L, "published", null);

        // FOR UPDATE 查询路径：wrapper 含 status=published 锁定 + LIMIT 1 FOR UPDATE（D-7，H2 MySQL 模式可跑）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Standard>> lockQuery = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectOne(lockQuery.capture());
        String lockSql = lockQuery.getValue().getSqlSegment().toLowerCase().replace("_", "");
        assertTrue(lockSql.contains("for update"), "发布取代走 FOR UPDATE 行级锁（D-7），实际: " + lockSql);
        assertTrue(lockSql.contains("status"), "锁定对象限定现行 published 版本，实际: " + lockSql);

        // 事务内先取代旧版（deprecated + 原因含"被 {code} {新版本} 取代"）再发布新版
        ArgumentCaptor<Standard> updates = ArgumentCaptor.forClass(Standard.class);
        verify(baseMapper, times(2)).updateById(updates.capture());
        List<Standard> patched = updates.getAllValues();
        assertEquals(1900L, patched.get(0).getId(), "先取代旧 published（顺序钉死，SE §3.2.1）");
        assertEquals("deprecated", patched.get(0).getStatus());
        assertEquals("被 STD-0001 v2.0 取代", patched.get(0).getDeprecateReason(), "自动废弃原因含「被 {code} {新版本} 取代」");
        assertEquals(1932L, patched.get(1).getId());
        assertEquals("published", patched.get(1).getStatus(), "后发布新版");
        long publishedCount = patched.stream().filter(p -> "published".equals(p.getStatus())).count();
        assertEquals(1, publishedCount, "同编号至多一个 published（AC-F1.4）");

        // 双审计：standard_transit（发布）+ standard_auto_deprecate（自动废弃，resource_id=旧版）
        ArgumentCaptor<String> autoDetail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("standard_auto_deprecate"), eq("standard"), eq("1900"), autoDetail.capture());
        assertTrue(autoDetail.getValue().contains("v1.0→v2.0"), "被取代链入审计，实际: " + autoDetail.getValue());
        ArgumentCaptor<String> transitDetail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("standard_transit"), eq("standard"), eq("1932"), transitDetail.capture());
        assertTrue(transitDetail.getValue().contains("v1.0→v2.0"), "发布审计含被取代链（§8.1），实际: " + transitDetail.getValue());
    }

    @Test
    void 发布首发无旧published_不触发自动取代() {
        when(baseMapper.selectById(1932L)).thenReturn(stored("STD-0001", "v1.0", "draft"));
        when(baseMapper.selectOne(any())).thenReturn(null);
        stubUpdateOk();

        service.transit(1932L, "published", null);

        verify(baseMapper, times(1)).updateById(any(Standard.class));
        verify(auditService, never()).log(eq("standard_auto_deprecate"), anyString(), anyString(), anyString());
        verify(auditService).log(eq("standard_transit"), eq("standard"), eq("1932"), anyString());
    }

    // ==================== 锚点 16：D-9 gateName 反查 + S3 被引用解析（批B T13，AC-F1.7） ====================

    @Test
    void gateName反查_逻辑删行以已删除占位返回() {
        Standard active = stored("STD-0002", "v1.0", "published");
        active.setRelatedGateNames("[\"R\"]");
        active.setDeleted(0);
        Standard deletedRow = stored("STD-0003", "v1.0", "published");
        deletedRow.setId(1933L);
        deletedRow.setRelatedGateNames("[\"R\"]");
        deletedRow.setDeleted(1);
        Standard miss = stored("STD-0004", "v1.0", "published");
        miss.setRelatedGateNames("[\"其他规则\"]");
        when(baseMapper.selectPublishedWithDeletedForGateRef())
                .thenReturn(List.of(active, deletedRow, miss));

        var page = service.pageFilter(null, null, "R", null, 1, 20);

        assertEquals(2, page.getTotal(), "JSON 内存过滤命中 R 的两条（未删+已删）");
        assertEquals(2, page.getRecords().size());
        StandardVo first = page.getRecords().stream()
                .filter(v -> "STD-0002".equals(v.getStandardCode())).findFirst().orElseThrow();
        StandardVo second = page.getRecords().stream()
                .filter(v -> "STD-0003".equals(v.getStandardCode())).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, first.getDeleted(), "未删行正常展示");
        assertEquals(Boolean.TRUE, second.getDeleted(), "逻辑删行以 deleted=true 占位（D-9，AC-F1.7）");
        assertEquals("published", second.getStatus(), "占位行仍按 published 口径返回（§4.5 翻译）");
        // M1（安全评审）：D-9 旁路逻辑删路径出参瘦身——占位 VO 不携带 content 正文与
        // deprecateReason，已删/未删行统一收窄（SE §4.5 "占位只含必要字段"落地）
        assertNull(second.getContent(), "已删占位行不返回 content 正文（M1）");
        assertNull(second.getDeprecateReason(), "已删占位行不返回 deprecateReason（M1）");
        assertNull(first.getContent(), "未删行走 gateName 反查同样瘦身（列表级占位口径，M1）");
        assertNull(first.getCreateBy(), "占位 VO 不返回 createBy（M1）");
        // 打通查询走 D-9 旁路 SQL，不进常规分页（status 钉死 published，不受理其他 status 值）
        verify(baseMapper, never()).selectPage(any(), any());
    }

    @Test
    void gateName反查_内存分页() {
        Standard a = stored("STD-0002", "v1.0", "published");
        a.setRelatedGateNames("[\"R\"]");
        Standard b = stored("STD-0003", "v1.0", "published");
        b.setRelatedGateNames("[\"R\"]");
        when(baseMapper.selectPublishedWithDeletedForGateRef()).thenReturn(List.of(a, b));

        var page1 = service.pageFilter("draft", null, "R", null, 1, 1);
        var page2 = service.pageFilter(null, null, "R", null, 2, 1);

        assertEquals(2, page1.getTotal());
        assertEquals(1, page1.getRecords().size());
        assertEquals("STD-0002", page1.getRecords().get(0).getStandardCode());
        assertEquals(1, page2.getRecords().size());
        assertEquals("STD-0003", page2.getRecords().get(0).getStandardCode());
    }

    @Test
    void gateName为空_走常规分页路径() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Standard> p = inv.getArgument(0);
            p.setRecords(List.of(stored("STD-0001", "v1.0", "draft")));
            p.setTotal(1);
            return p;
        });

        service.pageFilter(null, null, null, null, 1, 20);

        verify(baseMapper).selectPage(any(), any());
        verify(baseMapper, never()).selectPublishedWithDeletedForGateRef();
    }

    @Test
    void 详情被引用门禁解析_悬空name占位() {
        Standard s = stored("STD-0002", "v1.0", "published");
        s.setRelatedGateNames("[\"规则A\",\"已删规则\"]");
        when(baseMapper.selectById(1932L)).thenReturn(s);
        QualityGateRule alive = new QualityGateRule();
        alive.setName("规则A");
        alive.setGateType("llm_review");
        alive.setStage("post_dev");
        alive.setEnabled(1);
        when(gateRuleMapper.selectOne(any())).thenReturn(alive, null);

        StandardVo vo = service.detailVo(1932L);

        assertNotNull(vo.getReferencedByGates());
        assertEquals(2, vo.getReferencedByGates().size());
        StandardVo.ReferencedGate first = vo.getReferencedByGates().get(0);
        assertFalse(first.isDeleted());
        assertEquals("规则A", first.getName());
        assertEquals("llm_review", first.getGateType(), "解析规则当前信息");
        StandardVo.ReferencedGate dangling = vo.getReferencedByGates().get(1);
        assertTrue(dangling.isDeleted(), "悬空 name（规则已删/改名）以 deleted=true 占位（R6）");
        assertNull(dangling.getGateType());
    }
}
