package com.eaiselp.runtime.controller;

import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.entity.Checkpoint;
import com.eaiselp.data.service.CheckpointService;
import com.eaiselp.runtime.casestate.CaseStateService;
import com.eaiselp.runtime.casestate.CaseStatus;
import com.eaiselp.runtime.casestate.IllegalStateTransitionException;
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
 * CaseStateController 单测（状态流转 + 检查点人工锁）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>transit 合法/非法/参数校验</li>
 *   <li>createCheckpoint 参数校验 + 正常创建</li>
 *   <li>confirm/reject 正常 + 已处理(409)</li>
 *   <li>listCheckpoints</li>
 *   <li>operator 从 JWT claims 取（防伪造）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CaseStateControllerTest {

    @Mock CaseStateService caseStateService;
    @Mock CheckpointService checkpointService;
    @Mock AuditService auditService;

    @InjectMocks CaseStateController controller;

    @AfterEach
    void clearThreadLocal() {
        LoginUser.set(null);
    }

    private void loginAs(String username) {
        JwtClaims claims = JwtClaims.builder().userId(1L).username(username).tenantId(1L).build();
        LoginUser.set(claims);
    }

    // ===== transit =====

    @Test
    void transit_合法流转_成功() {
        loginAs("admin");
        Case c = new Case();
        c.setStatus("deriving");
        when(caseStateService.transit(eq("case-1"), eq(CaseStatus.DERIVING), eq("admin")))
                .thenReturn(c);

        CaseStateController.TransitRequest req = new CaseStateController.TransitRequest();
        req.setTargetStatus("deriving");

        var result = controller.transit("case-1", req);

        assertEquals(0, result.getCode());
        assertEquals("deriving", result.getData());
        verify(auditService).log(eq("case_transit"), eq("case"), eq("case-1"), anyString());
    }

    @Test
    void transit_targetStatus为空_返回400() {
        CaseStateController.TransitRequest req = new CaseStateController.TransitRequest();
        req.setTargetStatus("");

        var result = controller.transit("case-1", req);

        assertEquals(400, result.getCode());
        verifyNoInteractions(caseStateService);
    }

    @Test
    void transit_req为null_返回400() {
        var result = controller.transit("case-1", null);

        assertEquals(400, result.getCode());
    }

    @Test
    void transit_未知状态值_返回400() {
        CaseStateController.TransitRequest req = new CaseStateController.TransitRequest();
        req.setTargetStatus("garbage");

        var result = controller.transit("case-1", req);

        assertEquals(400, result.getCode());
        verifyNoInteractions(caseStateService);
    }

    @Test
    void transit_非法流转_异常传播() {
        loginAs("admin");
        when(caseStateService.transit(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateTransitionException("非法流转"));

        CaseStateController.TransitRequest req = new CaseStateController.TransitRequest();
        req.setTargetStatus("done");

        // 异常由 GlobalExceptionHandler 统一处理，这里验证异常确实抛出
        assertThrows(IllegalStateTransitionException.class,
                () -> controller.transit("case-1", req));
    }

    @Test
    void transit_operator从JWT取_防伪造() {
        loginAs("zhangsan");
        when(caseStateService.transit(anyString(), any(), eq("zhangsan")))
                .thenReturn(new Case());

        CaseStateController.TransitRequest req = new CaseStateController.TransitRequest();
        req.setTargetStatus("deriving");

        controller.transit("case-1", req);

        // 验证传给 service 的 operator 是 JWT 里的 username，而非请求体里的
        verify(caseStateService).transit(eq("case-1"), any(), eq("zhangsan"));
    }

    // ===== createCheckpoint =====

    @Test
    void createCheckpoint_正常创建() {
        Checkpoint cp = new Checkpoint();
        cp.setId(100L);
        cp.setCaseId("case-1");
        cp.setOperation("deploy_production");
        when(checkpointService.create(eq("case-1"), eq("deploy_production"), isNull()))
                .thenReturn(cp);

        CaseStateController.CreateCheckpointRequest req = new CaseStateController.CreateCheckpointRequest();
        req.setOperation("deploy_production");

        var result = controller.createCheckpoint("case-1", req);

        assertEquals(0, result.getCode());
        assertEquals(100L, result.getData().getId());
    }

    @Test
    void createCheckpoint_operation为空_返回400() {
        CaseStateController.CreateCheckpointRequest req = new CaseStateController.CreateCheckpointRequest();
        req.setOperation("");

        var result = controller.createCheckpoint("case-1", req);

        assertEquals(400, result.getCode());
    }

    // ===== confirm / reject =====

    @Test
    void confirm_正常确认() {
        loginAs("admin");
        when(checkpointService.confirm(eq(100L), eq("admin"), anyString())).thenReturn(true);

        CaseStateController.CheckpointActionRequest req = new CaseStateController.CheckpointActionRequest();
        req.setComment("同意");

        var result = controller.confirm(100L, req);

        assertEquals(0, result.getCode());
        verify(auditService).log(eq("checkpoint_confirm"), eq("checkpoint"), eq("100"),
                anyString(), eq("success"), isNull());
    }

    @Test
    void confirm_已处理_返回409() {
        loginAs("admin");
        when(checkpointService.confirm(anyLong(), anyString(), any())).thenReturn(false);

        var result = controller.confirm(100L, new CaseStateController.CheckpointActionRequest());

        assertEquals(409, result.getCode());
        verify(auditService).log(eq("checkpoint_confirm"), eq("checkpoint"), eq("100"),
                anyString(), eq("failure"), anyString());
    }

    @Test
    void reject_正常拒绝() {
        loginAs("admin");
        when(checkpointService.reject(eq(100L), eq("admin"), anyString())).thenReturn(true);

        CaseStateController.CheckpointActionRequest req = new CaseStateController.CheckpointActionRequest();
        req.setComment("不合格，打回");

        var result = controller.reject(100L, req);

        assertEquals(0, result.getCode());
        verify(auditService).log(eq("checkpoint_reject"), eq("checkpoint"), eq("100"),
                anyString(), eq("success"), isNull());
    }

    @Test
    void reject_已处理_返回409() {
        loginAs("admin");
        when(checkpointService.reject(anyLong(), anyString(), any())).thenReturn(false);

        var result = controller.reject(100L, null);

        assertEquals(409, result.getCode());
    }

    @Test
    void confirm_req为null_comment兜底null() {
        loginAs("admin");
        when(checkpointService.confirm(eq(100L), eq("admin"), isNull())).thenReturn(true);

        var result = controller.confirm(100L, null);

        assertEquals(0, result.getCode());
    }

    // ===== listCheckpoints =====

    @Test
    void listCheckpoints_正常返回() {
        Checkpoint cp1 = new Checkpoint();
        cp1.setId(1L);
        cp1.setOperation("deploy_production");
        when(checkpointService.listByCaseId("case-1")).thenReturn(List.of(cp1));

        var result = controller.listCheckpoints("case-1");

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("deploy_production", result.getData().get(0).getOperation());
    }

    @Test
    void listCheckpoints_无记录_返回空列表() {
        when(checkpointService.listByCaseId("case-empty")).thenReturn(List.of());

        var result = controller.listCheckpoints("case-empty");

        assertEquals(0, result.getCode());
        assertTrue(result.getData().isEmpty());
    }
}
