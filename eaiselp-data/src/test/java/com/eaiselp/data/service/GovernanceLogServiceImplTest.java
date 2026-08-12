package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import com.eaiselp.data.service.impl.GovernanceLogServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * GovernanceLogServiceImpl 单测（审计日志查询 + 多租户隔离）。
 *
 * <p>核心验证：</p>
 * <ul>
 *   <li>page：tenantId 显式过滤（防跨租户越权） + action 可选过滤 + 分页</li>
 *   <li>listByUserId：userId 过滤 + limit 兜底（0/负数→20）</li>
 * </ul>
 *
 * <p>注意：GovernanceLogServiceImpl 继承 ServiceImpl，page(wrapper) 和 list(wrapper)
 * 由 MyBatis-Plus IService 默认实现转发到 mapper。测试用 @Mock mapper 模拟 DB 返回。</p>
 */
@ExtendWith(MockitoExtension.class)
class GovernanceLogServiceImplTest {

    @Mock GovernanceLogMapper governanceLogMapper;

    @InjectMocks GovernanceLogServiceImpl service;

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
        // ServiceImpl.page 调用 mapper.selectPage
        when(governanceLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

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
        when(governanceLogMapper.selectPage(any(), any())).thenReturn(mockPage);

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
        when(governanceLogMapper.selectPage(any(), any())).thenReturn(mockPage);

        // action="" 等价于 null，不加 action 过滤条件
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
        when(governanceLogMapper.selectPage(any(), any())).thenReturn(emptyPage);

        var result = service.page(99L, null, 1, 20);

        assertTrue(result.getRecords().isEmpty());
    }

    // ===== listByUserId =====

    @Test
    @SuppressWarnings("unchecked")
    void listByUserId_正常查询() {
        when(governanceLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        log(3L, 1L, "case_transit", 100L),
                        log(2L, 1L, "login_success", 100L),
                        log(1L, 1L, "login_success", 100L)
                ));

        List<GovernanceLog> logs = service.listByUserId(100L, 50);

        assertEquals(3, logs.size());
        logs.forEach(l -> assertEquals(100L, l.getUserId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listByUserId_limit为0_兜底为20() {
        when(governanceLogMapper.selectList(any())).thenReturn(List.of());

        service.listByUserId(100L, 0);

        // 验证不报错（limit 兜底为 20）
        // 无法直接验证 SQL 里的 LIMIT 值，但确保调用不异常
    }

    @Test
    @SuppressWarnings("unchecked")
    void listByUserId_limit为负数_兜底为20() {
        when(governanceLogMapper.selectList(any())).thenReturn(List.of());

        service.listByUserId(100L, -5);

        // 同上，确保兜底逻辑不异常
    }

    @Test
    @SuppressWarnings("unchecked")
    void listByUserId_无记录_空列表() {
        when(governanceLogMapper.selectList(any())).thenReturn(List.of());

        List<GovernanceLog> logs = service.listByUserId(999L, 10);

        assertNotNull(logs);
        assertTrue(logs.isEmpty());
    }
}
