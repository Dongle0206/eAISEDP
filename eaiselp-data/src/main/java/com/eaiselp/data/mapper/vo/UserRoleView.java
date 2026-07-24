package com.eaiselp.data.mapper.vo;

import lombok.Data;

/** 用户-角色投影 VO（MyBatis 自动驼峰映射 role_id→roleId 等）。 */
@Data
public class UserRoleView {
    private Long roleId;
    private String roleCode;
    private String roleName;
}
