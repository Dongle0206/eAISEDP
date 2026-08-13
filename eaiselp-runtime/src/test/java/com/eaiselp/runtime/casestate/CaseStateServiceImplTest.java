package com.eaiselp.runtime.casestate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.service.CaseService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CaseStateServiceImpl 单测（状态机 + 不可逆操作防护）。
 *
 * <p>核心验证：合法流转、非法流转拦截、幂等流转、caseId 空值校验、
 * Case 不存在异常、终态保护（done 不可流转）。</p>
 *
 * <p><b>lambda cache 初始化</b>：CaseStateServiceImpl 内部用 LambdaQueryWrapper<Case>
 * 构造查询条件（Case::getCaseId），MyBatis-Plus 解析 lambda 方法引用需要 entity 的
 * TableInfo 缓存。纯 Mockito（无 Spring 上下文）下 cache 为空会抛 MybatisPlusException。
 * 这里用 @BeforeAll 手动初始化 Case 的 TableInfo。</p>
 */
@ExtendWith(MockitoExtension.class)
class CaseStateServiceImplTest {

    @Mock CaseService caseService;

    @InjectMocks
    CaseStateServiceImpl caseStateService;

    @BeforeAll
    static void initLambdaCache() {
        // 初始化 MyBatis-Plus lambda cache（纯 Mockito 下无 Spring 自动初始化）
        // LambdaQueryWrapper<Case> 构造时 Case::getCaseId 需要 entity 的 TableInfo 缓存
        org.apache.ibatis.session.Configuration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, Case.class);
    }

    // ===== 合法流转 =====

    @Test
    void transit_合法正向流转_成功() {
        Case c = mockCase("case-1", "drafting");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);
        when(caseService.update(any(LambdaUpdateWrapper.class))).thenReturn(true);

        Case result = caseStateService.transit("case-1", CaseStatus.DERIVING, "admin");

        assertEquals("deriving", result.getStatus());
        assertEquals("admin", result.getUpdateBy());
    }

    @Test
    void transit_返工流转_reviewing到deriving_成功() {
        Case c = mockCase("case-1", "reviewing");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);
        when(caseService.update(any(LambdaUpdateWrapper.class))).thenReturn(true);

        Case result = caseStateService.transit("case-1", CaseStatus.DERIVING, "admin");

        assertEquals("deriving", result.getStatus());
    }

    // ===== 幂等流转 =====

    @Test
    void transit_目标等于当前_幂等返回() {
        Case c = mockCase("case-1", "deriving");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        Case result = caseStateService.transit("case-1", CaseStatus.DERIVING, "admin");

        assertEquals("deriving", result.getStatus());
        // 幂等场景不调用 update
        verify(caseService, never()).update(any(LambdaUpdateWrapper.class));
    }

    // ===== 非法流转拦截 =====

    @Test
    void transit_跨阶段跳跃_抛异常() {
        Case c = mockCase("case-1", "drafting");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        // drafting 不能直接跳到 testing（跨阶段）
        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit("case-1", CaseStatus.TESTING, "admin"));
    }

    @Test
    void transit_终态done不可流转_抛异常() {
        Case c = mockCase("case-1", "done");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit("case-1", CaseStatus.DRAFTING, "admin"));
    }

    @Test
    void transit_终态done不可前进_抛异常() {
        Case c = mockCase("case-1", "done");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        // done 已经是终态，尝试流转到任何状态都应失败（但幂等 done→done 除外）
        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit("case-1", CaseStatus.DERIVING, "admin"));
    }

    // ===== 空值 / 参数校验 =====

    @Test
    void transit_caseId为空_抛异常() {
        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit(null, CaseStatus.DERIVING, "admin"));
    }

    @Test
    void transit_caseId纯空格_抛异常() {
        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit("   ", CaseStatus.DERIVING, "admin"));
    }

    @Test
    void transit_target为空_抛异常() {
        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit("case-1", null, "admin"));
    }

    // ===== Case 不存在 =====

    @Test
    void transit_Case不存在_抛异常() {
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit("case-not-exist", CaseStatus.DERIVING, "admin"));
    }

    // ===== Case status 非法值 =====

    @Test
    void transit_Case状态值非法_抛异常() {
        Case c = mockCase("case-1", "invalid_status");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit("case-1", CaseStatus.DERIVING, "admin"));
    }

    // ===== update 失败（并发删除 / 租户隔离）=====

    @Test
    void transit_update返回false_抛异常() {
        Case c = mockCase("case-1", "drafting");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);
        when(caseService.update(any(LambdaUpdateWrapper.class))).thenReturn(false);

        assertThrows(IllegalStateTransitionException.class,
                () -> caseStateService.transit("case-1", CaseStatus.DERIVING, "admin"));
    }

    // ===== getCurrentStatus =====

    @Test
    void getCurrentStatus_正常返回() {
        Case c = new Case();
        c.setStatus("reviewing");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        CaseStatus status = caseStateService.getCurrentStatus("case-1");

        assertEquals(CaseStatus.REVIEWING, status);
    }

    @Test
    void getCurrentStatus_Case不存在_返回null() {
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertNull(caseStateService.getCurrentStatus("case-not-exist"));
    }

    @Test
    void getCurrentStatus_caseId为空_返回null() {
        assertNull(caseStateService.getCurrentStatus(null));
    }

    @Test
    void getCurrentStatus_caseId纯空格_返回null() {
        assertNull(caseStateService.getCurrentStatus("   "));
    }

    @Test
    void getCurrentStatus_status值非法_返回null() {
        Case c = new Case();
        c.setStatus("garbage");
        when(caseService.getOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        assertNull(caseStateService.getCurrentStatus("case-1"));
    }

    // ===== 辅助 =====

    private Case mockCase(String caseId, String status) {
        Case c = new Case();
        c.setCaseId(caseId);
        c.setStatus(status);
        return c;
    }
}
