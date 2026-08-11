package com.eaiselp.data.audit;

import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.entity.GovernanceLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuditServiceImpl 单测（审计合规敏感模块）。
 *
 * <p>核心验证：</p>
 * <ul>
 *   <li>已登录场景：从 LoginUser（JWT claims）正确提取 userId/username/tenantId</li>
 *   <li>未登录场景（如登录失败）：tenantId 兜底为 0（系统级），不报错</li>
 *   <li>result 默认值：未传 result 时填 "success"</li>
 *   <li>result 显式传 failure 时正确记录</li>
 *   <li>异步委派：正确调用 auditLogger.write(entry)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock AuditLogger auditLogger;

    @InjectMocks
    AuditServiceImpl auditService;

    @AfterEach
    void clearThreadLocal() {
        // 清理 LoginUser ThreadLocal，防止测试间污染
        LoginUser.set(null);
    }

    @Test
    void log_已登录_正确提取claims() {
        // 模拟 JwtAuthInterceptor 注入 LoginUser
        JwtClaims claims = JwtClaims.builder()
                .userId(1001L)
                .username("admin")
                .tenantId(1L)
                .build();
        LoginUser.set(claims);

        auditService.log("case_create", "case", "case-abc",
                "{\"title\":\"测试\"}", null, null);

        // 验证传给 auditLogger 的 GovernanceLog 字段
        ArgumentCaptor<GovernanceLog> captor = ArgumentCaptor.forClass(GovernanceLog.class);
        org.mockito.Mockito.verify(auditLogger).write(captor.capture());
        GovernanceLog entry = captor.getValue();

        assertEquals(1001L, entry.getUserId());
        assertEquals("admin", entry.getUsername());
        assertEquals(1L, entry.getTenantId());
        assertEquals("case_create", entry.getAction());
        assertEquals("case", entry.getResourceType());
        assertEquals("case-abc", entry.getResourceId());
        assertEquals("success", entry.getResult(), "未传 result 应默认 success");
        assertNull(entry.getErrorMsg());
    }

    @Test
    void log_未登录_tenantId兜底为0() {
        // LoginUser 未注入（如登录接口是白名单，无 token）
        LoginUser.set(null);

        auditService.log("login_failure", "user", null,
                "{\"username\":\"hacker\"}", "failure", "用户名或密码错误");

        ArgumentCaptor<GovernanceLog> captor = ArgumentCaptor.forClass(GovernanceLog.class);
        org.mockito.Mockito.verify(auditLogger).write(captor.capture());
        GovernanceLog entry = captor.getValue();

        assertEquals(0L, entry.getTenantId(), "未登录场景 tenantId 应兜底为 0=系统级");
        assertNull(entry.getUserId());
        assertNull(entry.getUsername());
        assertEquals("failure", entry.getResult());
        assertEquals("用户名或密码错误", entry.getErrorMsg());
    }

    @Test
    void log_result显式failure_正确记录() {
        JwtClaims claims = JwtClaims.builder().userId(1L).username("u").tenantId(1L).build();
        LoginUser.set(claims);

        auditService.log("derivation", "derivation", "d-123",
                null, "failure", "LLM 超时");

        ArgumentCaptor<GovernanceLog> captor = ArgumentCaptor.forClass(GovernanceLog.class);
        org.mockito.Mockito.verify(auditLogger).write(captor.capture());
        GovernanceLog entry = captor.getValue();

        assertEquals("failure", entry.getResult());
        assertEquals("LLM 超时", entry.getErrorMsg());
        assertNull(entry.getDetail(), "detail 为 null 应原样传入");
    }

    @Test
    void log_tenantId为null时兜底为0() {
        // 极端边界：claims 存在但 tenantId 为 null
        JwtClaims claims = JwtClaims.builder()
                .userId(1L).username("u").tenantId(null).build();
        LoginUser.set(claims);

        auditService.log("checkpoint", "checkpoint", "cp-1", null, null, null);

        ArgumentCaptor<GovernanceLog> captor = ArgumentCaptor.forClass(GovernanceLog.class);
        org.mockito.Mockito.verify(auditLogger).write(captor.capture());
        assertEquals(0L, captor.getValue().getTenantId(), "tenantId null 应兜底为 0");
    }

    @Test
    void log_auditLogger抛异常_不影响主流程() {
        JwtClaims claims = JwtClaims.builder().userId(1L).username("u").tenantId(1L).build();
        LoginUser.set(claims);

        // auditLogger.write 抛异常，auditService.log 应捕获不传播
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(auditLogger).write(org.mockito.ArgumentMatchers.any());

        // 不应抛异常——审计失败不能影响业务主流程
        assertDoesNotThrow(() -> auditService.log("case_create", "case", "c1", null, null, null));
    }
}
