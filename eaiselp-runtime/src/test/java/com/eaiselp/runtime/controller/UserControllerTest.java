package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.service.UserService;
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
 * UserController 单测。
 *
 * <p>覆盖用户管理 CRUD + 权限 + 审计 + 密码安全：</p>
 * <ul>
 *   <li>page 分页查询</li>
 *   <li>get 详情 + 密码清空（双保险）</li>
 *   <li>create 创建 + 参数校验</li>
 *   <li>update 更新</li>
 *   <li>disable 禁用 + 不存在 404</li>
 *   <li>assignRoles 角色分配</li>
 *   <li>tenantId 从 JWT claims 取（防伪造）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock UserService userService;
    @Mock AuditService auditService;

    @InjectMocks UserController controller;

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
    void page_正常分页() {
        loginAs(1L);
        Page<User> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of());
        mockPage.setTotal(0);
        when(userService.page(any(), eq(1L), isNull())).thenReturn(mockPage);

        var result = controller.page(1, 20, null);

        assertEquals(0, result.getCode());
    }

    // ===== get =====

    @Test
    void get_用户存在_密码清空() {
        loginAs(1L);
        User u = new User();
        u.setId(100L);
        u.setUsername("testuser");
        u.setPassword("$2a$12$something"); // 模拟 DB 中的 BCrypt hash
        when(userService.getById(1L, 100L)).thenReturn(u);

        var result = controller.get(100L);

        assertEquals(0, result.getCode());
        assertNull(result.getData().getPassword(), "响应中 password 必须清空");
    }

    @Test
    void get_用户不存在_返回404() {
        loginAs(1L);
        when(userService.getById(1L, 999L)).thenReturn(null);

        var result = controller.get(999L);

        assertEquals(404, result.getCode());
    }

    // ===== create =====

    @Test
    void create_正常创建() {
        loginAs(1L);
        User u = new User();
        u.setId(200L);
        u.setUsername("newuser");
        when(userService.create(eq(1L), eq("newuser"), anyString(), any(), any(), any(), any()))
                .thenReturn(u);

        UserController.CreateUserRequest req = new UserController.CreateUserRequest();
        req.setUsername("newuser");
        req.setPassword("pass123");
        req.setRoles(List.of("engineer"));

        var result = controller.create(req);

        assertEquals(0, result.getCode());
        verify(auditService).log(eq("user_create"), eq("user"), eq("200"), anyString());
    }

    @Test
    void create_username为空_返回400() {
        UserController.CreateUserRequest req = new UserController.CreateUserRequest();
        req.setUsername("");
        req.setPassword("pass123");

        var result = controller.create(req);

        assertEquals(400, result.getCode());
    }

    @Test
    void create_密码太短_返回400() {
        UserController.CreateUserRequest req = new UserController.CreateUserRequest();
        req.setUsername("user1");
        req.setPassword("123"); // < 6 位

        var result = controller.create(req);

        assertEquals(400, result.getCode());
    }

    // ===== update =====

    @Test
    void update_正常更新_密码清空() {
        loginAs(1L);
        User u = new User();
        u.setId(100L);
        u.setPassword("should-be-cleared");
        when(userService.update(eq(1L), eq(100L), any(), any(), any())).thenReturn(u);

        UserController.UpdateUserRequest req = new UserController.UpdateUserRequest();
        req.setDisplayName("新名字");

        var result = controller.update(100L, req);

        assertEquals(0, result.getCode());
        assertNull(result.getData().getPassword());
        verify(auditService).log(eq("user_update"), eq("user"), eq("100"), anyString());
    }

    // ===== disable =====

    @Test
    void disable_正常禁用() {
        loginAs(1L);
        when(userService.disable(1L, 100L)).thenReturn(true);

        var result = controller.disable(100L);

        assertEquals(0, result.getCode());
        verify(auditService).log(eq("user_disable"), eq("user"), eq("100"), isNull());
    }

    @Test
    void disable_用户不存在_返回404() {
        loginAs(1L);
        when(userService.disable(1L, 999L)).thenReturn(false);

        var result = controller.disable(999L);

        assertEquals(404, result.getCode());
    }

    // ===== assignRoles =====

    @Test
    void assignRoles_正常分配() {
        loginAs(1L);
        when(userService.assignRoles(1L, 100L, List.of("reviewer", "qa"))).thenReturn(true);

        UserController.AssignRolesRequest req = new UserController.AssignRolesRequest();
        req.setRoleCodes(List.of("reviewer", "qa"));

        var result = controller.assignRoles(100L, req);

        assertEquals(0, result.getCode());
        verify(auditService).log(eq("user_assign_roles"), eq("user"), eq("100"), anyString());
    }

    @Test
    void assignRoles_roleCodes为null_返回400() {
        UserController.AssignRolesRequest req = new UserController.AssignRolesRequest();
        req.setRoleCodes(null);

        var result = controller.assignRoles(100L, req);

        assertEquals(400, result.getCode());
    }

    @Test
    void assignRoles_用户不存在_返回404() {
        loginAs(1L);
        when(userService.assignRoles(1L, 999L, any())).thenReturn(false);

        UserController.AssignRolesRequest req = new UserController.AssignRolesRequest();
        req.setRoleCodes(List.of());

        var result = controller.assignRoles(999L, req);

        assertEquals(404, result.getCode());
    }

    // ===== tenantId 防伪造 =====

    @Test
    void tenantId从JWT取_不从请求参数() {
        loginAs(5L); // 租户 5
        when(userService.page(any(), eq(5L), any())).thenReturn(new Page<>());

        controller.page(1, 20, null);

        // 验证传给 service 的 tenantId 是 JWT 里的 5，不是 0（兜底默认）
        verify(userService).page(any(), eq(5L), any());
    }
}
