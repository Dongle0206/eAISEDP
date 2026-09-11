package com.eaiselp.data.service.subscription;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Incident;
import com.eaiselp.data.mapper.IncidentMapper;
import com.eaiselp.data.service.subscription.dto.IncidentVo;
import com.eaiselp.data.service.subscription.impl.IncidentServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * IncidentService 单测（case-20260823-商用化 T11；AC-F3.2/F3.3 代码侧 + 倒挂校验 +
 * slaBreach 缺省 + 审计）。纯 Mockito（data 模块先例）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentServiceImplTest {

    @Mock IncidentMapper incidentMapper;
    @Mock AuditService auditService;

    @InjectMocks IncidentServiceImpl service;

    private static final LocalDateTime AUG_01 = LocalDateTime.of(2026, 8, 1, 9, 0);
    private static final LocalDateTime AUG_10 = LocalDateTime.of(2026, 8, 10, 10, 0);
    private static final LocalDateTime AUG_20 = LocalDateTime.of(2026, 8, 20, 14, 30);

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Incident.class);
    }

    private static Incident incident(String title, LocalDateTime occurred, LocalDateTime recovery) {
        Incident i = new Incident();
        i.setId(7001L);
        i.setTitle(title);
        i.setOccurredTime(occurred);
        i.setRecoveryTime(recovery);
        i.setImpactDesc("三租户派生排队延迟");
        return i;
    }

    // ==================== N2 登记（AC-F3.2 前半） ====================

    @Test
    void 登记_必填齐全_落库成功_recovered为false() {
        Incident created = incident("派生服务超时", AUG_20, null); // 恢复时间留空
        IncidentVo vo = service.create(created);

        assertFalse(vo.isRecovered(), "补记前列表展示'未恢复'（AC-F3.2）");
        assertEquals(0, vo.getSlaBreach(), "slaBreach 缺省 0（D-4）");
        verify(incidentMapper).insert(any(Incident.class));
        verify(auditService).log(eq("incident_create"), eq("incident"), eq("7001"), any(String.class));
    }

    @Test
    void 登记_必填缺失_40000() {
        Incident noTitle = incident(null, AUG_20, null);
        BizException ex1 = assertThrows(BizException.class, () -> service.create(noTitle));
        assertEquals(ResultCode.BAD_REQUEST, ex1.getCode());

        Incident noTime = incident("标题", null, null);
        ex1 = assertThrows(BizException.class, () -> service.create(noTime));
        assertEquals(ResultCode.BAD_REQUEST, ex1.getCode());

        Incident noImpact = incident("标题", AUG_20, null);
        noImpact.setImpactDesc(" ");
        assertThrows(BizException.class, () -> service.create(noImpact));
    }

    @Test
    void 登记_恢复时间倒挂_400() {
        Incident bad = incident("倒挂事故", AUG_20, AUG_10); // recovery < occurred
        BizException ex = assertThrows(BizException.class, () -> service.create(bad));
        assertEquals(400, ex.getCode(), "倒挂 400 防脏数据");
        assertTrue(ex.getMessage().contains("倒挂") || ex.getMessage().contains("早于"));
        verify(incidentMapper, never()).insert(any());
    }

    // ==================== N4 编辑补记（AC-F3.2 后半） ====================

    @Test
    void 编辑_补记恢复时间与根因_recovered翻true() {
        Incident exist = incident("派生服务超时", AUG_20, null);
        exist.setRootCause(null);
        when(incidentMapper.selectById(7001L)).thenReturn(exist);
        when(incidentMapper.updateById(any(Incident.class))).thenReturn(1);

        Incident patched = incident("派生服务超时", AUG_20, LocalDateTime.of(2026, 8, 20, 16, 0));
        patched.setRootCause("上游模型服务限流");
        // update 内部 detail(id) 再查一次——返回已补记行
        Incident recovered = incident("派生服务超时", AUG_20, LocalDateTime.of(2026, 8, 20, 16, 0));
        recovered.setRootCause("上游模型服务限流");
        when(incidentMapper.selectById(7001L)).thenReturn(exist).thenReturn(recovered);

        IncidentVo vo = service.update(7001L, patched);

        assertTrue(vo.isRecovered(), "补记后展示'已恢复'（AC-F3.2）");
        assertEquals("上游模型服务限流", vo.getRootCause());
        // 审计 incident_update detail 含 old→new
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("incident_update"), eq("incident"), eq("7001"), detail.capture());
        assertTrue(detail.getValue().contains("\"old\""));
    }

    @Test
    void 编辑_不存在_40400() {
        when(incidentMapper.selectById(404L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.update(404L, incident("标题", AUG_20, null)));
        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    // ==================== N1 列表倒序与筛选（AC-F3.3——Service 层 wrapper 语义） ====================

    @Test
    void 列表_默认发生时间倒序_未恢复筛选判空() {
        // wrapper 语义由 H2 集成（T19 收口）断言行序；此处断言 Service 正常组装分页与 VO 映射
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Incident> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(java.util.List.of(
                incident("事故A", AUG_20, null),
                incident("事故B", AUG_10, AUG_10.plusHours(2)),
                incident("事故C", AUG_01, null)));
        page.setTotal(3);
        when(incidentMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.pageFilter(null, 1, 20);

        assertEquals(3, result.getTotal());
        assertEquals("事故A", result.getRecords().get(0).getTitle(), "构造序即倒序回显");
        assertFalse(result.getRecords().get(0).isRecovered());
        assertTrue(result.getRecords().get(1).isRecovered());
    }
}
