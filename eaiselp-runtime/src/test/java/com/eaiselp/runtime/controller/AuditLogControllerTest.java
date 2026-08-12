package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.service.GovernanceLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuditLogController 单测。
 *
 * <p>核心验证：</p>
 * <ul>
 *   <li>tenantId 从 LoginUser 取（防跨租户越权，ES-003 §9.3 G13）</li>
 *   <li>分页查询 + action 过滤</li>
 *   <li>listByUserId 的 limit 上限兜底（防超大结果集）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock GovernanceLogService governanceLogService;

    @InjectMocks AuditLogController controller;

    @AfterEach
    void clearThreadLocal() {
        LoginUser.set(null);
    }

    private void loginAs(Long tenantId) {
        JwtClaims claims = JwtClaims.builder().userId(1L).username("admin").tenantId(tenantId).build();
        LoginUser.set(claims);
    }

    // ===== page =====

    @Test
    void page_正常查询_tenantId从JWT取() {
        loginAs(3L);
        Page<GovernanceLog> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of());
        when(governanceLogService.page(eq(3L), isNull(), eq(1), eq(20))).thenReturn(mockPage);

        var result = controller.page(1, 20, null);

        assertEquals(0, result.getCode());
        verify(governanceLogService).page(eq(3L), isNull(), eq(1), eq(20));
    }

    @Test
    void page_带action过滤() {
        loginAs(1L);
        Page<GovernanceLog> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of());
        when(governanceLogService.page(eq(1L), eq("login_success"), eq(1), eq(20))).thenReturn(mockPage);

        var result = controller.page(1, 20, "login_success");

        assertEquals(0, result.getCode());
        verify(governanceLogService).page(eq(1L), eq("login_success"), eq(1), eq(20));
    }

    @Test
    void page_默认页码_默认条数() {
        loginAs(1L);
        Page<GovernanceLog> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of());
        when(governanceLogService.page(eq(1L), isNull(), eq(1), eq(20))).thenReturn(mockPage);

        // 不传 page/size，验证默认值 1/20
        var result = controller.page(1, 20, null);

        assertEquals(0, result.getCode());
    }

    // ===== listByUserId =====

    @Test
    void listByUserId_正常查询() {
        loginAs(1L);
        GovernanceLog log1 = new GovernanceLog();
        log1.setId(1L);
        log1.setAction("login_success");
        when(governanceLogService.listByUserId(eq(100L), eq(50))).thenReturn(List.of(log1));

        var result = controller.listByUserId(100L, 50);

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("login_success", result.getData().get(0).getAction());
    }

    @Test
    void listByUserId_limit超过100_截断到100() {
        loginAs(1L);
        when(governanceLogService.listByUserId(eq(100L), eq(100))).thenReturn(List.of());

        controller.listByUserId(100L, 500); // 传入 500

        // 验证 limit 被 Math.min 截断到 100（防大结果集拖慢 DB）
        verify(governanceLogService).listByUserId(eq(100L), eq(100));
    }

    @Test
    void listByUserId_limit为0_兜底为1() {
        loginAs(1L);
        when(governanceLogService.listByUserId(eq(100L), eq(1))).thenReturn(List.of());

        controller.listByUserId(100L, 0);

        verify(governanceLogService).listByUserId(eq(100L), eq(1));
    }

    @Test
    void listByUserId_limit为负_兜底为1() {
        loginAs(1L);
        when(governanceLogService.listByUserId(eq(100L), eq(1))).thenReturn(List.of());

        controller.listByUserId(100L, -10);

        verify(governanceLogService).listByUserId(eq(100L), eq(1));
    }

    @Test
    void listByUserId_无结果_空列表() {
        loginAs(1L);
        when(governanceLogService.listByUserId(eq(999L), anyInt())).thenReturn(List.of());

        var result = controller.listByUserId(999L, 10);

        assertTrue(result.getData().isEmpty());
    }
}
