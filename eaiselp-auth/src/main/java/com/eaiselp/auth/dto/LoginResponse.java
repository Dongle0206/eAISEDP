package com.eaiselp.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String token;
    private long expiresIn;     // 有效期秒数（=86400）
    private UserInfo user;
}
