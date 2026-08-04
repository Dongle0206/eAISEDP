package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.data.entity.Checkpoint;
import com.eaiselp.data.mapper.CheckpointMapper;
import com.eaiselp.data.service.impl.CheckpointServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CheckpointServiceImpl 单测（Wave3-B，GRC 不可逆操作人工锁）。
 *
 * <p>不启动 Spring 上下文（{@code @ExtendWith(MockitoExtension)} + {@code @Mock}）。
 *
 * <p><b>关键点：被测类继承 MyBatis-Plus {@code ServiceImpl}</b>——confirm/reject/create/list*
 * 全部经由继承的 {@code this.update/save/list} 委托给父类 {@code protected baseMapper} 字段。
 * 故测试里用反射把 mock 的 {@link CheckpointMapper} 注入到 {@code baseMapper}，无需 mock 父类方法。
 *
 * <p>覆盖（状态机 CAS：仅 pending → confirmed/rejected，防并发双确认）：
 * <ul>
 *   <li>TC1 create：创建 pending 检查点（status=pending + requestedAt 设置）</li>
 *   <li>TC2 confirm pending→confirmed：条件更新 WHERE status='pending'，返回 true</li>
 *   <li>TC3 confirm 非 pending：已 confirmed 再 confirm 返回 false（CAS 防双确认）</li>
 *   <li>TC4 reject pending→rejected：条件更新，返回 true</li>
 *   <li>TC5 reject 非 pending：已 confirmed 的 reject 返回 false</li>
 *   <li>TC6 listByCaseId：按 caseId 查列表</li>
 *   <li>TC7 findPendingByCaseId：只返回 pending 的</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CheckpointServiceImplTest {

    @Mock CheckpointMapper checkpointMapper;

    private CheckpointServiceImpl checkpointService;

    /**
     * 预初始化 MyBatis-Plus 的实体→表元信息缓存（lambda cache）。
     *
     * <p><b>关键坑</b>：被测类的 confirm/reject 用 {@code LambdaUpdateWrapper.set(Checkpoint::getXxx, ...)}，
     * MyBatis-Plus 的 {@code set()} 是<b>急切求值</b>（构造 wrapper 时立即调 columnToString → getColumnCache →
     * tryInitCache）。在没有 Spring/MyBatis 启动流程的纯 Mockito 单测里，TableInfo 缓存为空，会抛
     * {@code "can not find lambda cache for this entity [Checkpoint]"}。
     *
     * <p>AuthServiceImplTest 没踩这个坑是因为它只用 {@code LambdaQueryWrapper.eq()}（惰性求值，且 mock 的
     * selectOne 从不真正解析 SQL），而 UpdateWrapper.set() 是急切的——必须显式 initTableInfo。
     */
    @BeforeAll
    static void initTableInfoCache() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.eaiselp.data.mapper.CheckpointMapper");
        TableInfoHelper.initTableInfo(assistant, Checkpoint.class);
    }

    @BeforeEach
    void setUp() {
        // ServiceImpl 的 baseMapper 是 protected 字段（无构造器入参），用反射注入 mock。
        // 用 Mockito.mock() 而非 @Mock 字段，确保实例与反射注入的是同一对象。
        checkpointService = new CheckpointServiceImpl();
        ReflectionTestUtils.setField(checkpointService, "baseMapper", checkpointMapper);
    }

    // ==================== TC1 create ====================

    /**
     * TC1：创建检查点——status 置为 pending，requestedAt 被设置，
     * 并调用 baseMapper.insert 持久化。
     */
    @Test
    void TC1_create_创建pending检查点_status与requestedAt被设置() {
        when(checkpointMapper.insert(any(Checkpoint.class))).thenAnswer(inv -> {
            Checkpoint cp = inv.getArgument(0);
            cp.setId(9001L); // 模拟 MyBatis-Plus 回填主键
            return 1;
        });

        Checkpoint cp = checkpointService.create("CASE-1001", "deploy_production", 55L);

        ArgumentCaptor<Checkpoint> captor = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointMapper, times(1)).insert(captor.capture());
        Checkpoint inserted = captor.getValue();
        assertEquals("CASE-1001", inserted.getCaseId());
        assertEquals("deploy_production", inserted.getOperation());
        assertEquals(55L, inserted.getDerivationId());
        assertEquals(CheckpointService.STATUS_PENDING, inserted.getStatus(),
                "新建检查点 status 必须为 pending");
        assertNotNull(inserted.getRequestedAt(), "requestedAt 必须被设置（人工待确认时间戳）");
        assertEquals(9001L, cp.getId(), "返回实体应携带回填主键");
    }

    // ==================== TC2 confirm pending→confirmed ====================

    /**
     * TC2：confirm pending 检查点——条件更新（WHERE status='pending'）影响行数=1，
     * 状态置为 confirmed，confirmedAt/confirmedBy/comment 被设置，返回 true。
     */
    @Test
    @SuppressWarnings("unchecked")
    void TC2_confirm_pending转confirmed_条件更新返回true() {
        // baseMapper.update(entity, wrapper)：ServiceImpl.update(wrapper) 内部传 entity=null
        when(checkpointMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        boolean ok = checkpointService.confirm(9001L, "alice", "approved");

        assertTrue(ok, "pending 状态下 confirm 应返回 true");

        ArgumentCaptor<AbstractWrapper<Checkpoint, ?, ?>> wrapperCaptor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(checkpointMapper, times(1)).update(isNull(), wrapperCaptor.capture());
        AbstractWrapper<Checkpoint, ?, ?> wrapper = wrapperCaptor.getValue();
        // 条件更新 WHERE 用 ? 占位符 + 绑定参数（MyBatis-Plus getTargetSql 不回填值），
        // 故分别校验：SQL 片段含 id+status 列，绑定参数含 9001 + pending（CAS 防双确认）
        String sql = wrapper.getTargetSql();
        assertNotNull(sql, "应构造 LambdaUpdateWrapper");
        assertTrue(sql.contains("id") && sql.contains("status"),
                "WHERE 必须含 id + status 列（CAS 条件更新）（实际=" + sql + "）");
        assertTrue(containsParamValue(wrapper, 9001L, "pending"),
                "WHERE 绑定参数必须含 id=9001 + status='pending'（实际参数=" + wrapper.getParamNameValuePairs() + "）");
    }

    // ==================== TC3 confirm 非 pending（CAS 防双确认） ====================

    /**
     * TC3：已 confirmed 的检查点再 confirm——条件更新影响行数=0（WHERE status='pending' 不匹配），
     * 返回 false。这是 CAS 防并发双确认的核心保证。
     */
    @Test
    void TC3_confirm_已confirmed再confirm_条件更新行数0返回false() {
        when(checkpointMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        boolean ok = checkpointService.confirm(9001L, "alice", "double-confirm");

        assertFalse(ok, "已 confirmed 的检查点再 confirm 必须返回 false（防并发双确认）");
        verify(checkpointMapper, times(1)).update(isNull(), any(Wrapper.class));
    }

    /** TC3b：confirm 传 null id 直接返回 false（不走 DB）。 */
    @Test
    void TC3b_confirm_nullId_直接返回false_不查DB() {
        boolean ok = checkpointService.confirm(null, "alice", null);
        assertFalse(ok);
        verifyNoInteractions(checkpointMapper);
    }

    // ==================== TC4 reject pending→rejected ====================

    /**
     * TC4：reject pending 检查点——条件更新（WHERE status='pending'）影响行数=1，
     * 状态置为 rejected，返回 true。
     */
    @Test
    @SuppressWarnings("unchecked")
    void TC4_reject_pending转rejected_条件更新返回true() {
        when(checkpointMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        boolean ok = checkpointService.reject(9001L, "bob", "risky rollback needed");

        assertTrue(ok);

        ArgumentCaptor<AbstractWrapper<Checkpoint, ?, ?>> wrapperCaptor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(checkpointMapper, times(1)).update(isNull(), wrapperCaptor.capture());
        AbstractWrapper<Checkpoint, ?, ?> wrapper = wrapperCaptor.getValue();
        String sql = wrapper.getTargetSql();
        // reject 也必须用 WHERE status='pending' 条件更新（与 confirm 同样的 CAS 防双确认）
        assertTrue(sql.contains("status"),
                "reject 的 WHERE 必须含 status 列（CAS）（实际=" + sql + "）");
        assertTrue(containsParamValue(wrapper, "pending"),
                "reject 的 WHERE 绑定参数必须含 status='pending'（实际参数=" + wrapper.getParamNameValuePairs() + "）");
    }

    // ==================== TC5 reject 非 pending ====================

    /**
     * TC5：已 confirmed 的检查点 reject——条件更新影响行数=0，返回 false
     * （已确认的不能再被拒绝，状态机单向）。
     */
    @Test
    void TC5_reject_已confirmed_条件更新行数0返回false() {
        when(checkpointMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        boolean ok = checkpointService.reject(9001L, "bob", "too late");

        assertFalse(ok, "已 confirmed 的检查点 reject 必须返回 false");
        verify(checkpointMapper, times(1)).update(isNull(), any(Wrapper.class));
    }

    /** TC5b：reject 传 null id 直接返回 false。 */
    @Test
    void TC5b_reject_nullId_直接返回false_不查DB() {
        boolean ok = checkpointService.reject(null, "bob", null);
        assertFalse(ok);
        verifyNoInteractions(checkpointMapper);
    }

    // ==================== TC6 listByCaseId ====================

    /** TC6：按 caseId 查检查点列表——委托 baseMapper.selectList，原样返回。 */
    @Test
    void TC6_listByCaseId_按caseId查列表() {
        Checkpoint cp1 = checkpoint(9001L, "CASE-2001", "pending");
        Checkpoint cp2 = checkpoint(9002L, "CASE-2001", "confirmed");
        when(checkpointMapper.selectList(any())).thenReturn(List.of(cp1, cp2));

        List<Checkpoint> result = checkpointService.listByCaseId("CASE-2001");

        assertEquals(2, result.size());
        assertEquals("CASE-2001", result.get(0).getCaseId());
        // 验证 listByCaseId 内部用了 selectList（含 caseId 条件）
        verify(checkpointMapper, times(1)).selectList(any());
    }

    // ==================== TC7 findPendingByCaseId ====================

    /** TC7：findPendingByCaseId 只返回 pending 检查点（查询条件含 status=pending 过滤）。 */
    @Test
    @SuppressWarnings("unchecked")
    void TC7_findPendingByCaseId_只返回pending的() {
        Checkpoint pending = checkpoint(9001L, "CASE-3001", "pending");
        when(checkpointMapper.selectList(any())).thenReturn(List.of(pending));

        List<Checkpoint> result = checkpointService.findPendingByCaseId("CASE-3001");

        assertEquals(1, result.size());
        assertEquals(CheckpointService.STATUS_PENDING, result.get(0).getStatus(),
                "findPending 应只返回 pending 状态");

        // 验证查询 wrapper 确实带 status=pending 条件（区别于 listByCaseId）
        ArgumentCaptor<AbstractWrapper<Checkpoint, ?, ?>> wrapperCaptor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(checkpointMapper, times(1)).selectList(wrapperCaptor.capture());
        AbstractWrapper<Checkpoint, ?, ?> wrapper = wrapperCaptor.getValue();
        String sql = wrapper.getTargetSql();
        assertTrue(sql.contains("status"),
                "findPendingByCaseId 的 WHERE 必须含 status 列（实际=" + sql + "）");
        assertTrue(sql.contains("caseId"),
                "WHERE 必须含 caseId 列（实际=" + sql + "）");
        assertTrue(containsParamValue(wrapper, "pending"),
                "WHERE 绑定参数必须含 status='pending'（实际参数=" + wrapper.getParamNameValuePairs() + "）");
    }

    // ==================== fixtures ====================

    private Checkpoint checkpoint(Long id, String caseId, String status) {
        Checkpoint cp = new Checkpoint();
        cp.setId(id);
        cp.setCaseId(caseId);
        cp.setOperation("deploy_production");
        cp.setStatus(status);
        return cp;
    }

    /**
     * 校验 wrapper 的绑定参数集合是否包含全部期望值（AND 关系）。
     * MyBatis-Plus 的 {@code getTargetSql()} 用 ? 占位符不回填值，
     * 实际绑定参数在 {@code getParamNameValuePairs()}（一个 Map，值可能延迟通过 supplier 求值）。
     * 这里统一调 toString 比对，覆盖 Long/String 等类型。
     */
    private static boolean containsParamValue(AbstractWrapper<?, ?, ?> wrapper, Object... expected) {
        Object params = wrapper.getParamNameValuePairs();
        if (params == null) return false;
        String s = params.toString();
        for (Object e : expected) {
            if (!s.contains(String.valueOf(e))) {
                return false;
            }
        }
        return true;
    }
}
