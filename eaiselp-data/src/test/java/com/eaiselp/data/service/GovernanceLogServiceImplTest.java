package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.service.impl.GovernanceLogServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GovernanceLogServiceImpl 单测（审计日志查询 + 多租户隔离）。
 *
 * <p>核心验证：</p>
 * <ul>
 *   <li>page：tenantId 显式过滤（防跨租户越权） + action 可选过滤 + 分页</li>
 *   <li>listByUserId：userId 过滤 + limit 兜底（0/负数→20）</li>
 * </ul>
 *
 * <p><b>Mock 策略</b>：GovernanceLogServiceImpl 继承 ServiceImpl，内部调 this.page(p, wrapper) /
 * this.list(wrapper)，走 ServiceImpl 默认实现 → baseMapper。@InjectMocks 无法注入 ServiceImpl 私有
 * baseMapper 字段。改用 @Spy 包装真实实例，doReturn() 拦截父类方法，绕开 baseMapper。</p>
 */
@ExtendWith(MockitoExtension.class)
class GovernanceLogServiceImplTest {

    // @Spy 包装真实 ServiceImpl，拦截父类 this.page()/this.list()
    @Spy
    GovernanceLogServiceImpl service;

    private GovernanceLog log(Long id, Long tenantId, String action, Long userId) {
        GovernanceLog l = new GovernanceLog();
        l.setId(id);
        l.setTenantId(tenantId);
        l.setAction(action);
        l.setUserId(userId);
        return l;
    }

    // ===== page =====

    @Test
    @SuppressWarnings("unchecked")
    void page_按租户查询_正常分页() {
        Page<GovernanceLog> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of(log(1L, 1L, "login_success", 100L)));
        mockPage.setTotal(1);
        // 拦截 ServiceImpl.page(IPage, Wrapper) → 不走 baseMapper
        doReturn(mockPage).when(service).page(any(IPage.class), any(Wrapper.class));

        var result = service.page(1L, null, 1, 20);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("login_success", result.getRecords().get(0).getAction());
    }

    @Test
    @SuppressWarnings("unchecked")
    void page_带action过滤() {
        Page<GovernanceLog> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of(log(1L, 1L, "case_create", 100L)));
        mockPage.setTotal(1);
        doReturn(mockPage).when(service).page(any(IPage.class), any(Wrapper.class));

        var result = service.page(1L, "case_create", 1, 20);

        assertEquals(1, result.getRecords().size());
        assertEquals("case_create", result.getRecords().get(0).getAction());
    }

    @Test
    @SuppressWarnings("unchecked")
    void page_action为空字符串_不过滤() {
        Page<GovernanceLog> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of());
        mockPage.setTotal(0);
        doReturn(mockPage).when(service).page(any(IPage.class), any(Wrapper.class));

        var result = service.page(1L, "", 1, 20);

        assertNotNull(result);
        assertEquals(0, result.getTotal());
    }

    @Test
    @SuppressWarnings("unchecked")
    void page_无结果_空页() {
        Page<GovernanceLog> emptyPage = new Page<>(1, 20);
        emptyPage.setRecords(List.of());
        emptyPage.setTotal(0);
        doReturn(emptyPage).when(service).page(any(IPage.class), any(Wrapper.class));

        var result = service.page(99L, null, 1, 20);

        assertTrue(result.getRecords().isEmpty());
    }

    // ===== listByUserId =====

    @Test
    @SuppressWarnings("unchecked")
    void listByUserId_正常查询() {
        doReturn(List.of(
                log(3L, 1L, "case_transit", 100L),
                log(2L, 1L, "login_success", 100L),
                log(1L, 1L, "login_success", 100L)
        )).when(service).list(any(Wrapper.class));

        List<GovernanceLog> logs = service.listByUserId(100L, 50);

        assertEquals(3, logs.size());
        logs.forEach(l -> assertEquals(100L, l.getUserId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listByUserId_limit为0_兜底为20() {
        doReturn(List.of()).when(service).list(any(Wrapper.class));

        service.listByUserId(100L, 0);

        // 验证不报错（limit 兜底为 20），list 被调用了 1 次
        verify(service, times(1)).list(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listByUserId_limit为负数_兜底为20() {
        doReturn(List.of()).when(service).list(any(Wrapper.class));

        service.listByUserId(100L, -5);

        verify(service, times(1)).list(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listByUserId_无记录_空列表() {
        doReturn(List.of()).when(service).list(any(Wrapper.class));

        List<GovernanceLog> logs = service.listByUserId(999L, 10);

        assertNotNull(logs);
        assertTrue(logs.isEmpty());
    }
}
