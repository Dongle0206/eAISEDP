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
 * TemplateServiceImpl 单测（case-20260820 T3，SE §9.1 锚点 17~21）。
 *
 * <p>纯 Mockito 方式（hierarchy 包先例）。覆盖：
 * <ul>
 *   <li>锚点 17：uk(tenant,type,name) 三例——同型同名拒（含同型同名异版本仍拒）/异型同名合法（AC-F1.9）；</li>
 *   <li>锚点 18：自定义类型"复盘报告"可存可筛（开放字典无枚举拦截，AC-F1.10）；</li>
 *   <li>锚点 19：占位符提取去重排序 / 无占位符空清单（AC-F1.11）；</li>
 *   <li>锚点 20：版本不变 400 / 变更成功且审计含 oldVersion（AC-F1.12）；</li>
 *   <li>锚点 21：停用默认不可见 + includeDisabled=1 可见 + 启停审计（AC-F1.12）。</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TemplateServiceImplTest {

    @Mock TemplateMapper baseMapper;
    @Mock AuditService auditService;

    @InjectMocks TemplateServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Template.class);
    }

    @BeforeEach
    void injectBaseMapper() throws Exception {
        java.lang.reflect.Field f = TemplateServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    // ==================== 构造工具 ====================

    private static Template tpl(String type, String name, String version) {
        Template t = new Template();
        t.setTemplateType(type);
        t.setTemplateName(name);
        t.setVersion(version);
        t.setContent("# 模板 {{project_name}}");
        return t;
    }

    private static Template stored(String type, String name, String version, Integer enabled) {
        Template t = tpl(type, name, version);
        t.setId(2201L);
        t.setEnabled(enabled);
        return t;
    }

    private void stubInsertOk() {
        when(baseMapper.insert(any(Template.class))).thenAnswer(inv -> {
            Template t = inv.getArgument(0);
            t.setId(9200L);
            return 1;
        });
    }

    // ==================== 锚点 17：uk 三例（AC-F1.9） ====================

    @Test
    void 同型同名被拒_uk冲突统一形态() {
        when(baseMapper.insert(any(Template.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_tpl_tenant_type_name"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(tpl("PRD", "需求文档模板", "v1")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"), "uk 冲突统一形态，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PRD"));
        assertTrue(ex.getMessage().contains("需求文档模板"));
    }

    @Test
    void 同型同名异版本仍被拒_单行当前版不含version() {
        when(baseMapper.insert(any(Template.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_tpl_tenant_type_name"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(tpl("PRD", "需求文档模板", "v2")));
        assertEquals(400, ex.getCode(), "单行当前版模型：version 不进 uk，同型同名换版本仍拒");
    }

    @Test
    void 异型同名合法() {
        stubInsertOk();

        Template created = service.create(tpl("技术方案", "需求文档模板", "v1"));

        assertNotNull(created.getId());
        assertEquals(1, created.getEnabled(), "创建默认启用");
        verify(auditService).log(eq("template_create"), eq("template"), anyString(), anyString());
    }

    // ==================== 锚点 18：类型开放字典（AC-F1.10） ====================

    @Test
    void 自定义类型可存_应用层无枚举拦截() {
        stubInsertOk();

        Template created = assertDoesNotThrow(() -> service.create(tpl("复盘报告", "复盘模板", "v1")));
        assertEquals("复盘报告", created.getTemplateType());
    }

    @Test
    void 自定义类型可筛_精确匹配透传() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Template> p = inv.getArgument(0);
            p.setRecords(List.of(stored("复盘报告", "复盘模板", "v1", 1)));
            p.setTotal(1);
            return p;
        });

        var page = service.pageFilter("复盘报告", null, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Template>> wc = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(), wc.capture());
        String sql = wc.getValue().getSqlSegment().toLowerCase().replace("_", "");
        assertTrue(sql.contains("templatetype"), "类型精确匹配进 SQL（含自定义值），实际: " + sql);
        assertEquals(1, page.getRecords().size());
        assertEquals("复盘报告", page.getRecords().get(0).getTemplateType());
    }

    // ==================== 锚点 19：占位符提取（AC-F1.11） ====================

    @Test
    void 占位符提取_去重排序() {
        List<String> ph = service.extractPlaceholders("本项目 {{project_name}} 的 {{case_id}} 交付物，重申 {{project_name}}");
        assertEquals(List.of("case_id", "project_name"), ph, "去重 + 字典序排序");
    }

    @Test
    void 占位符非法形态不提取_空正文空清单() {
        assertTrue(service.extractPlaceholders(null).isEmpty());
        assertTrue(service.extractPlaceholders("正文无占位符").isEmpty(), "无占位符 → 空清单合法（AC-F1.11）");
        assertTrue(service.extractPlaceholders("{{illegal-name}} {{}} 非法标识符不提取").isEmpty(),
                "标识符限字母数字下划线");
    }

    @Test
    void 详情Vo_实时提取占位符清单与数量() {
        Template t = stored("PRD", "需求文档模板", "v2", 1);
        t.setContent("本项目 {{project_name}} 的 {{case_id}} 交付物 {{project_name}}");
        when(baseMapper.selectById(2201L)).thenReturn(t);

        var vo = service.detailVo(2201L);

        assertEquals(List.of("case_id", "project_name"), vo.getPlaceholders());
        assertEquals(2, vo.getPlaceholderCount());
        assertTrue(vo.getContent().contains("{{project_name}}"), "详情携带 content 全文");
    }

    // ==================== 锚点 20：原地升版（AC-F1.12） ====================

    @Test
    void 编辑版本未变更_400() {
        when(baseMapper.selectById(2201L)).thenReturn(stored("PRD", "需求文档模板", "v1", 1));

        BizException ex = assertThrows(BizException.class,
                () -> service.edit(2201L, tpl("PRD", "需求文档模板", "v1")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("版本必须变更"), "不比较大小，相同即拒，实际: " + ex.getMessage());
        verify(baseMapper, never()).updateById(any(Template.class));
    }

    @Test
    void 编辑版本变更成功_审计含旧版本() {
        when(baseMapper.selectById(2201L)).thenReturn(stored("PRD", "需求文档模板", "v1", 1));
        when(baseMapper.updateById(any(Template.class))).thenReturn(1);

        service.edit(2201L, tpl("PRD", "需求文档模板", "v2"));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("template_update"), eq("template"), eq("2201"), detail.capture());
        assertTrue(detail.getValue().contains("\"oldVersion\":\"v1\""), "旧版本唯一留痕=审计 detail，实际: " + detail.getValue());
        assertTrue(detail.getValue().contains("\"newVersion\":\"v2\""));
    }

    // ==================== 锚点 21：启停与默认隐藏（AC-F1.12） ====================

    @Test
    void 启停_写enabled并审计() {
        when(baseMapper.selectById(2201L)).thenReturn(stored("PRD", "需求文档模板", "v1", 1));
        when(baseMapper.updateById(any(Template.class))).thenReturn(1);

        service.toggleEnabled(2201L, 0);

        ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getEnabled());
        verify(auditService).log(eq("template_status"), eq("template"), eq("2201"), anyString());
    }

    @Test
    void 启停非法值_400() {
        when(baseMapper.selectById(2201L)).thenReturn(stored("PRD", "需求文档模板", "v1", 1));

        BizException ex = assertThrows(BizException.class, () -> service.toggleEnabled(2201L, 2));
        assertEquals(400, ex.getCode());
        verify(baseMapper, never()).updateById(any(Template.class));
    }

    @Test
    void 列表默认隐藏停用_includeDisabled可见() {
        when(baseMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Template> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });

        service.pageFilter(null, null, null, 1, 20);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Template>> w1 = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper, times(1)).selectPage(any(), w1.capture());
        assertTrue(w1.getValue().getSqlSegment().toLowerCase().replace("_", "").contains("enabled"),
                "缺省 includeDisabled=0 → enabled=1 过滤（停用默认隐藏）");

        service.pageFilter(null, null, 1, 1, 20);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Template>> w2 = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper, times(2)).selectPage(any(), w2.capture());
        String sql2 = w2.getValue().getSqlSegment().toLowerCase().replace("_", "");
        assertTrue(sql2.isEmpty() || !sql2.contains("enabled"), "includeDisabled=1 → 无 enabled 过滤（筛选可见），实际: " + sql2);
    }
}
