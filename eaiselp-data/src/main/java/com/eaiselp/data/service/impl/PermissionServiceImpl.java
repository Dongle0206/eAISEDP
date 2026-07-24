package com.eaiselp.data.service.impl;

import com.eaiselp.data.mapper.PermissionMapper;
import com.eaiselp.data.mapper.UserRoleMapper;
import com.eaiselp.data.mapper.vo.UserRoleView;
import com.eaiselp.data.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;

    @Override
    public List<String> getRoleCodesByUserId(Long userId) {
        return userRoleMapper.selectRolesByUserId(userId).stream()
                .map(UserRoleView::getRoleCode).distinct().collect(Collectors.toList());
    }

    @Override
    public List<Long> getRoleIdsByUserId(Long userId) {
        return userRoleMapper.selectRolesByUserId(userId).stream()
                .map(UserRoleView::getRoleId).distinct().collect(Collectors.toList());
    }

    @Override
    public List<String> getPermissionCodesByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return Collections.emptyList();
        return permissionMapper.selectPermissionCodesByRoleIds(roleIds).stream()
                .distinct().collect(Collectors.toList());
    }

    @Override
    public boolean hasAnyPermission(Collection<Long> roleIds, String permissionCode) {
        return getPermissionCodesByRoleIds(roleIds).contains(permissionCode);
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        return hasAnyPermission(getRoleIdsByUserId(userId), permissionCode);
    }
}
