package com.eaiselp.data.service;

import com.eaiselp.data.mapper.PermissionMapper;
import com.eaiselp.data.mapper.UserRoleMapper;
import com.eaiselp.data.mapper.vo.UserRoleView;
import com.eaiselp.data.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * PermissionServiceImpl 单测（RBAC 安全敏感模块）。
 *
 * <p>验证权限查询链路：userId → roleIds → permissionCodes 的完整映射，
 * 以及空集合/重复值/不存在的防御性处理。</p>
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Mock UserRoleMapper userRoleMapper;
    @Mock PermissionMapper permissionMapper;

    @InjectMocks
    PermissionServiceImpl permissionService;

    // ===== getRoleCodesByUserId =====

    @Test
    void getRoleCodes_正常返回_去重() {
        UserRoleView r1 = roleView(10L, "tenant_admin");
        UserRoleView r2 = roleView(10L, "tenant_admin"); // 重复 roleId
        UserRoleView r3 = roleView(20L, "reviewer");
        when(userRoleMapper.selectRolesByUserId(1L)).thenReturn(Arrays.asList(r1, r2, r3));

        List<String> codes = permissionService.getRoleCodesByUserId(1L);

        assertEquals(2, codes.size(), "重复 roleCode 应去重");
        assertTrue(codes.contains("tenant_admin"));
        assertTrue(codes.contains("reviewer"));
    }

    @Test
    void getRoleCodes_用户无角色_返回空列表() {
        when(userRoleMapper.selectRolesByUserId(99L)).thenReturn(Collections.emptyList());

        List<String> codes = permissionService.getRoleCodesByUserId(99L);

        assertNotNull(codes);
        assertTrue(codes.isEmpty());
    }

    // ===== getRoleIdsByUserId =====

    @Test
    void getRoleIds_正常返回_去重() {
        when(userRoleMapper.selectRolesByUserId(1L))
                .thenReturn(Arrays.asList(roleView(10L, "a"), roleView(10L, "a"), roleView(20L, "b")));

        List<Long> ids = permissionService.getRoleIdsByUserId(1L);

        assertEquals(2, ids.size(), "重复 roleId 应去重");
        assertTrue(ids.contains(10L));
        assertTrue(ids.contains(20L));
    }

    // ===== getPermissionCodesByRoleIds =====

    @Test
    void getPermissionCodes_正常返回_去重() {
        when(permissionMapper.selectPermissionCodesByRoleIds(Arrays.asList(10L, 20L)))
                .thenReturn(Arrays.asList("case:view", "case:view", "case:create"));

        List<String> perms = permissionService.getPermissionCodesByRoleIds(Arrays.asList(10L, 20L));

        assertEquals(2, perms.size(), "重复权限码应去重");
        assertTrue(perms.contains("case:view"));
        assertTrue(perms.contains("case:create"));
    }

    @Test
    void getPermissionCodes_空roleIds_返回空列表() {
        List<String> perms = permissionService.getPermissionCodesByRoleIds(Collections.emptyList());
        assertNotNull(perms);
        assertTrue(perms.isEmpty(), "空 roleIds 不应触发 mapper 调用");
    }

    @Test
    void getPermissionCodes_nullRoleIds_返回空列表() {
        List<String> perms = permissionService.getPermissionCodesByRoleIds(null);
        assertNotNull(perms);
        assertTrue(perms.isEmpty());
    }

    // ===== hasAnyPermission =====

    @Test
    void hasAnyPermission_有权限_返回true() {
        when(permissionMapper.selectPermissionCodesByRoleIds(List.of(10L)))
                .thenReturn(Arrays.asList("case:view", "case:create"));

        assertTrue(permissionService.hasAnyPermission(List.of(10L), "case:view"));
    }

    @Test
    void hasAnyPermission_无权限_返回false() {
        when(permissionMapper.selectPermissionCodesByRoleIds(List.of(10L)))
                .thenReturn(List.of("case:view"));

        assertFalse(permissionService.hasAnyPermission(List.of(10L), "user:delete"));
    }

    // ===== hasPermission (userId → permissionCode) =====

    @Test
    void hasPermission_用户有权限_返回true() {
        when(userRoleMapper.selectRolesByUserId(1L))
                .thenReturn(List.of(roleView(10L, "admin")));
        when(permissionMapper.selectPermissionCodesByRoleIds(List.of(10L)))
                .thenReturn(Arrays.asList("case:view", "audit:read"));

        assertTrue(permissionService.hasPermission(1L, "audit:read"));
    }

    @Test
    void hasPermission_用户无权限_返回false() {
        when(userRoleMapper.selectRolesByUserId(1L))
                .thenReturn(List.of(roleView(10L, "viewer")));
        when(permissionMapper.selectPermissionCodesByRoleIds(List.of(10L)))
                .thenReturn(List.of("case:view"));

        assertFalse(permissionService.hasPermission(1L, "case:delete"));
    }

    @Test
    void hasPermission_用户无角色_返回false() {
        when(userRoleMapper.selectRolesByUserId(99L)).thenReturn(Collections.emptyList());

        // 无角色 → getPermissionCodesByRoleIds(空) 不调 mapper，直接返回空
        assertFalse(permissionService.hasPermission(99L, "case:view"));
    }

    // ===== 辅助 =====
    private UserRoleView roleView(Long roleId, String roleCode) {
        UserRoleView v = new UserRoleView();
        v.setRoleId(roleId);
        v.setRoleCode(roleCode);
        return v;
    }
}
