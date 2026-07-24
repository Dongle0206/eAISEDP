package com.eaiselp.auth.service;

import com.eaiselp.auth.dto.LoginRequest;
import com.eaiselp.auth.dto.LoginResponse;
import com.eaiselp.auth.dto.UserInfo;

public interface AuthService {
    LoginResponse login(LoginRequest req);
    UserInfo currentUser(Long userId, Long tenantId);
    void logout(Long userId);
}
