package com.eaiselp.runtime.task;

import com.eaiselp.common.exception.QuotaExceededException;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.service.DerivationService;
import com.eaiselp.runtime.engine.DerivationEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DerivationTaskService 单测（内存态状态机 + 配额校验 + TTL 清理）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>createPending：配额校验通过/超限/无配额记录放行 + DB INSERT + 内存 put</li>
 *   <li>markRunning / markSuccess / markFailed：内存 + DB 状态同步</li>
 *   <li>getTask：内存命中 / DB 回落 / not_found</li>
 *   <li>cleanupCompleted：TTL 清理已完成任务</li>
 *   <li>markFailed DB 写失败容错（不阻塞）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DerivationTaskServiceTest {

    @Mock DerivationService derivationService;
    @Mock QuotaMapper quotaMapper;

    @InjectMocks DerivationTaskService taskService;

    // ===== createPending =====

    @Test
    void createPending_配额校验通过_正常创建() {
        // 无配额记录 → 放行
        when(quotaMapper.selectOne(any())).thenReturn(null);
        // 模拟 ASSIGN_ID 回填
        doAnswer(inv -> {
            Derivation d = inv.getArgument(0);
            d.setId(1001L);
            return true;
        }).when(derivationService).save(any(Derivation.class));

        Long taskId = taskService.createPending("team-po", "case-1", "plan");

        assertEquals(1001L, taskId);
        // 验证内存态已写入
        DerivationTaskState state = taskService.getTask(1001L);
        assertEquals("pending", state.getStatus());
        assertEquals("team-po", state.getRole());
        assertEquals("case-1", state.getCaseId());
    }

    @Test
    void createPending_派生次数超限_抛QuotaExceededException() {
        Quota quota = new Quota();
        quota.setDerivationLimit(10);
        quota.setTokenLimit(100000L);
        when(quotaMapper.selectOne(any())).thenReturn(quota);
        when(derivationService.countSince(any())).thenReturn(10L); // 已用 10 = 上限

        assertThrows(QuotaExceededException.class,
                () -> taskService.createPending("team-po", "case-1", null));

        // 验证没有产生脏 pending 行（DB 没写）
        verify(derivationService, never()).save(any());
    }

    @Test
    void createPending_token超限_抛QuotaExceededException() {
        Quota quota = new Quota();
        quota.setDerivationLimit(100);
        quota.setTokenLimit(5000L);
        when(quotaMapper.selectOne(any())).thenReturn(quota);
        when(derivationService.countSince(any())).thenReturn(5L);
        when(derivationService.sumTokensSince(any())).thenReturn(5000L); // token 已达上限

        assertThrows(QuotaExceededException.class,
                () -> taskService.createPending("team-dev", "case-1", null));

        verify(derivationService, never()).save(any());
    }

    // ===== markRunning =====

    @Test
    void markRunning_内存和DB同步更新() {
        // 先创建一个 pending 任务
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(2001L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-dev", "case-2", null);

        taskService.markRunning(2001L);

        // 验证内存态
        assertEquals("running", taskService.getTask(2001L).getStatus());
        // 验证 DB UPDATE 被调用
        verify(derivationService).updateById(argThat(d -> "running".equals(((Derivation) d).getStatus())));
    }

    // ===== markSuccess =====

    @Test
    void markSuccess_内存更新_不重复写DB() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(3001L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-qa", "case-3", null);

        DerivationEngine.DerivationResult result = DerivationEngine.DerivationResult.builder()
                .status("success").role("team-qa").caseId("case-3")
                .finishedAt(LocalDateTime.now()).build();

        taskService.markSuccess(3001L, result);

        assertEquals("success", taskService.getTask(3001L).getStatus());
        assertNotNull(taskService.getTask(3001L).getFinishedAt());
    }

    @Test
    void markSuccess_result为null_finishedAt兜底当前时间() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(3002L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-qa", "case-3", null);

        taskService.markSuccess(3002L, null);

        assertNotNull(taskService.getTask(3002L).getFinishedAt(), "result null 时 finishedAt 应兜底当前时间");
    }

    // ===== markFailed =====

    @Test
    void markFailed_内存和DB同步标失败() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(4001L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-ops", "case-4", null);

        taskService.markFailed(4001L, new RuntimeException("LLM 超时"));

        DerivationTaskState state = taskService.getTask(4001L);
        assertEquals("failed", state.getStatus());
        assertEquals("LLM 超时", state.getError());
        verify(derivationService).updateById(argThat(d ->
                "failed".equals(((Derivation) d).getStatus()) &&
                ((Derivation) d).getErrorMsg() != null));
    }

    @Test
    void markFailed_DB写失败_不阻塞_内存态仍正确() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(4002L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-ops", "case-4", null);

        // DB 写失败
        doThrow(new RuntimeException("DB down")).when(derivationService).updateById(any());

        // 不应抛异常——内存态已标 failed，前端轮询仍能拿到结果
        assertDoesNotThrow(() -> taskService.markFailed(4002L, new RuntimeException("LLM error")));

        assertEquals("failed", taskService.getTask(4002L).getStatus());
    }

    @Test
    void markFailed_异常message为null_用类名兜底() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(4003L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-ops", "case-4", null);

        Throwable t = new RuntimeException(); // message = null
        taskService.markFailed(4003L, t);

        assertEquals("java.lang.RuntimeException", taskService.getTask(4003L).getError());
    }

    @Test
    void markFailed_throwable为null_兜底unknownError() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(4004L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-ops", "case-4", null);

        taskService.markFailed(4004L, null);

        assertEquals("unknown error", taskService.getTask(4004L).getError());
    }

    // ===== getTask =====

    @Test
    void getTask_taskId为null_返回notFound() {
        DerivationTaskState state = taskService.getTask(null);
        assertEquals("not_found", state.getStatus());
    }

    @Test
    void getTask_内存未命中_DB命中_从DB重构() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        Derivation dbRecord = new Derivation();
        dbRecord.setId(5001L);
        dbRecord.setStatus("success");
        dbRecord.setRole("team-po");
        dbRecord.setCaseId("case-5");
        when(derivationService.getById(5001L)).thenReturn(dbRecord);

        // 不经过 createPending（模拟内存已被 TTL 清理，DB 仍有记录）
        DerivationTaskState state = taskService.getTask(5001L);

        assertEquals("success", state.getStatus());
        assertEquals("team-po", state.getRole());
        assertEquals("case-5", state.getCaseId());
    }

    @Test
    void getTask_内存和DB都未命中_返回notFound() {
        when(derivationService.getById(9999L)).thenReturn(null);

        DerivationTaskState state = taskService.getTask(9999L);
        assertEquals("not_found", state.getStatus());
    }

    // ===== cleanupCompleted =====

    @Test
    void cleanupCompleted_清理已完成超TTL任务() {
        // 创建一个 success 任务
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(6001L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-po", "case-6", null);
        taskService.markSuccess(6001L, null);

        // 手动把 lastAccessAt 改到很久以前（模拟超过 TTL）
        DerivationTaskState s = taskService.getTask(6001L);
        s.setLastAccessAt(0); // epoch → 必然超 TTL

        // 执行清理
        taskService.cleanupCompleted();

        // 内存中应该已清掉（getTask 会回落 DB，但 DB 查不到因为没有 mock）
        when(derivationService.getById(6001L)).thenReturn(null);
        assertEquals("not_found", taskService.getTask(6001L).getStatus());
    }

    @Test
    void cleanupCompleted_未完成任务不清理() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { inv.<Derivation>getArgument(0).setId(7001L); return true; })
                .when(derivationService).save(any());
        taskService.createPending("team-po", "case-7", null);
        // 不 markSuccess —— 保持 pending

        // 即便 lastAccessAt 很旧，pending 状态不应被清理
        taskService.getTask(7001L).setLastAccessAt(0);
        taskService.cleanupCompleted();

        assertEquals("pending", taskService.getTask(7001L).getStatus());
    }
}
