package com.eaiselp.auth.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class UserInfo {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private Long tenantId;
    private String tenantName;
    private List<String> roles;        // 角色码（=roleCodes，PRD 两者都返回，值相同）
    private List<String> roleCodes;    // 角色码
    private List<String> permissions;  // 权限码（Q-5：不放 JWT payload，/current 实时查）
    private String avatar;
}
