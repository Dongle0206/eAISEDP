package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.mapper.CaseMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProjectProgressService 单测（AC-F8 上行汇总核心语义，PRD 验收基线）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>"两条标准 count + 一条 LambdaUpdateWrapper set" SQL 形态（SE §11 R4 拦截器友好，禁子查询）</li>
 *   <li>progress = ⌊done×100/total⌋ 向下取整；total=0 → 0（AC-F8.1 口径）</li>
 *   <li>全量重算幂等语义：重复/并发事件下每次按 DB 真值重算，最后写胜出=正确值（R6）</li>
 *   <li>异步入口异常全吞 + 租户上下文 finally 恢复（AC-F8.4 汇总失败不阻塞主流程）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProjectProgressServiceTest {

    @Mock CaseMapper caseMapper;
    @Mock ProjectMapper projectMapper;

    @InjectMocks
    ProjectProgressService service;

    @BeforeAll
    static void initLambdaCache() {
        // Case（count 条件）与 Project（update set 列）的 lambda 解析需 TableInfo（纯 Mockito 无 Spring）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Case.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
    }

    @AfterEach
    void cleanTenantContext() {
        TenantContext.clear();
    }

    // ===== AC-F8.1：3 total / 1 done → progress 33，两条 count + 一条 update =====

    @Test
    void recalculate_两条count一条update_进度33() {
        when(caseMapper.selectCount(any())).thenReturn(3L, 1L);   // total=3, done=1

        service.recalculate(10L);

        verify(caseMapper, times(2)).selectCount(any(LambdaQueryWrapper.class));
        ArgumentCaptor<LambdaUpdateWrapper<Project>> captor = wrapperCaptor();
        verify(projectMapper, times(1)).update(isNull(), captor.capture());

        LambdaUpdateWrapper<Project> uw = captor.getValue();
        String sqlSet = uw.getSqlSet();
        assertTrue(sqlSet.contains("case_total"), "一条 update 显式 set case_total");
        assertTrue(sqlSet.contains("case_done"), "一条 update 显式 set case_done");
        assertTrue(sqlSet.contains("progress"), "一条 update 显式 set progress");
        assertTrue(uw.getParamNameValuePairs().containsValue(3), "case_total=3");
        assertTrue(uw.getParamNameValuePairs().containsValue(1), "case_done=1");
        assertTrue(uw.getParamNameValuePairs().containsValue(33), "progress=⌊1×100/3⌋=33（AC-F8.1 断言数值）");
    }

    // ===== 幂等语义：重复触发按 DB 当前真值全量重算，最后写胜出=正确值 =====

    @Test
    void recalculate_重复调用幂等_每次按DB真值重算_第二个done后进度66() {
        // 第一次重算：3 total / 1 done；随后另一 Case 流转 done，第二次重算读到 3 total / 2 done
        when(caseMapper.selectCount(any())).thenReturn(3L, 1L, 3L, 2L);

        service.recalculate(10L);
        service.recalculate(10L);

        verify(caseMapper, times(4)).selectCount(any(LambdaQueryWrapper.class));
        ArgumentCaptor<LambdaUpdateWrapper<Project>> captor = wrapperCaptor();
        verify(projectMapper, times(2)).update(isNull(), captor.capture());

        LambdaUpdateWrapper<Project> second = captor.getAllValues().get(1);
        assertTrue(second.getParamNameValuePairs().containsValue(2), "第二次重算 case_done=2");
        assertTrue(second.getParamNameValuePairs().containsValue(66), "progress=⌊2×100/3⌋=66（AC-F8.1 流转后断言数值）");
    }

    // ===== AC-F8.1 口径边界：total=0 → progress=0 =====

    @Test
    void recalculate_total为0_progress为0() {
        when(caseMapper.selectCount(any())).thenReturn(0L, 0L);

        service.recalculate(10L);

        ArgumentCaptor<LambdaUpdateWrapper<Project>> captor = wrapperCaptor();
        verify(projectMapper).update(isNull(), captor.capture());
        // total=0 时三列全部归零（case_total=0, case_done=0, progress=0——不是 NaN/异常）
        assertTrue(captor.getValue().getParamNameValuePairs().values().stream()
                .allMatch(v -> ((Number) v).intValue() == 0), "total=0 → 0/0/0");
    }

    @Test
    void recalculate_projectId为空_不触发任何DB操作() {
        service.recalculate(null);

        verifyNoInteractions(caseMapper, projectMapper);
    }

    // ===== 异步入口：租户上下文切换/恢复 + 异常全吞（AC-F8.4） =====

    @Test
    void recalculateAsync_正常重算_租户上下文切换并在finally恢复() {
        TenantContext.set(999L);   // 异步线程池化复用前的"残留"上下文，模拟批4场景
        when(caseMapper.selectCount(any())).thenReturn(2L, 1L);

        service.recalculateAsync(10L, 1L);   // 目标租户 1

        verify(projectMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        assertEquals(999L, TenantContext.get(), "finally 必须恢复进入前上下文（防池化线程 ThreadLocal 泄漏）");
    }

    @Test
    void recalculateAsync_汇总失败吞异常不外抛_上下文仍恢复() {
        TenantContext.set(999L);
        when(caseMapper.selectCount(any())).thenThrow(new RuntimeException("mock: 汇总写库失败"));

        assertDoesNotThrow(() -> service.recalculateAsync(10L, 1L),
                "AC-F8.4：汇总失败绝不阻塞调用方主流程");

        verify(projectMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        assertEquals(999L, TenantContext.get(), "异常路径 finally 同样恢复上下文");
    }

    // ===== 辅助 =====

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<Project>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }
}
