package com.eaiselp.runtime.casestate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CaseStatus 状态机纯逻辑单测（Wave2）。无 Spring 上下文，无外部依赖。
 *
 * <p>验证枚举内置的流转规则（正向流转 / 返工回退 / 终态锁死 / 幂等 / dbValue 解析）。
 * 这些规则是 M2 SP-3 轻量级状态机的核心不变量，被 RuntimeService 的状态变更路径依赖。</p>
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC1 合法正向流转：drafting→deriving→reviewing→testing→deploying→done 全链通</li>
 *   <li>TC2 合法返工：reviewing/testing/deploying 均可回退到 deriving</li>
 *   <li>TC3 非法跨阶段跳跃：drafting→reviewing / drafting→done / deriving→done 全部拒绝</li>
 *   <li>TC4 终态锁死：done 不能转到任何状态</li>
 *   <li>TC5 自身幂等：流转到当前状态合法</li>
 *   <li>TC6 fromDbValue：正确解析各 db 值，未知值返回 null</li>
 *   <li>TC7 canTransitionTo 对 null 参数的处理</li>
 * </ul>
 */
class CaseStatusTest {

    // ==================== TC1 正向流转 ====================

    /** TC1：每个状态只能前进到直接后继（不允许跨阶段跳跃到非相邻后继）。 */
    @Test
    void TC1_合法正向流转() {
        assertTrue(CaseStatus.DRAFTING.canTransitionTo(CaseStatus.DERIVING), "drafting→deriving 应合法");
        assertTrue(CaseStatus.DERIVING.canTransitionTo(CaseStatus.REVIEWING), "deriving→reviewing 应合法");
        assertTrue(CaseStatus.REVIEWING.canTransitionTo(CaseStatus.TESTING), "reviewing→testing 应合法");
        assertTrue(CaseStatus.TESTING.canTransitionTo(CaseStatus.DEPLOYING), "testing→deploying 应合法");
        assertTrue(CaseStatus.DEPLOYING.canTransitionTo(CaseStatus.DONE), "deploying→done 应合法");
    }

    // ==================== TC2 返工回退 ====================

    /** TC2：reviewing/testing/deploying 均可返工回退到 deriving。 */
    @Test
    void TC2_合法返工_回退到deriving() {
        assertTrue(CaseStatus.REVIEWING.canTransitionTo(CaseStatus.DERIVING), "reviewing→deriving 返工应合法");
        assertTrue(CaseStatus.TESTING.canTransitionTo(CaseStatus.DERIVING), "testing→deriving 返工应合法");
        assertTrue(CaseStatus.DEPLOYING.canTransitionTo(CaseStatus.DERIVING), "deploying→deriving 返工应合法");
    }

    /** 补充断言：drafting/deriving 不允许往回退（无回退目标），deriving 不能回到 drafting。 */
    @Test
    void TC2_补充_不可往回退到drafting() {
        assertFalse(CaseStatus.DERIVING.canTransitionTo(CaseStatus.DRAFTING),
                "deriving→drafting 不可往回退，应拒绝");
        assertFalse(CaseStatus.REVIEWING.canTransitionTo(CaseStatus.DRAFTING),
                "reviewing 只能回退到 deriving，不能到 drafting");
    }

    // ==================== TC3 非法跨阶段跳跃 ====================

    /** TC3：跨阶段跳跃必须被拒绝（状态机不允许跳过中间阶段）。 */
    @Test
    void TC3_非法跨阶段跳跃() {
        assertFalse(CaseStatus.DRAFTING.canTransitionTo(CaseStatus.REVIEWING),
                "drafting→reviewing 是跨阶段跳跃，应拒绝");
        assertFalse(CaseStatus.DRAFTING.canTransitionTo(CaseStatus.TESTING),
                "drafting→testing 是跨阶段跳跃，应拒绝");
        assertFalse(CaseStatus.DRAFTING.canTransitionTo(CaseStatus.DONE),
                "drafting→done 是跨阶段跳跃，应拒绝");
        assertFalse(CaseStatus.DERIVING.canTransitionTo(CaseStatus.DONE),
                "deriving→done 是跨阶段跳跃，应拒绝");
        assertFalse(CaseStatus.DERIVING.canTransitionTo(CaseStatus.DEPLOYING),
                "deriving→deploying 是跨阶段跳跃，应拒绝");
        assertFalse(CaseStatus.REVIEWING.canTransitionTo(CaseStatus.DONE),
                "reviewing→done 是跨阶段跳跃，应拒绝");
    }

    // ==================== TC4 终态锁死 ====================

    /**
     * TC4：done 是终态，不能转到任何"非自身"状态。
     *
     * <p>注意：done→done 因幂等规则（{@code this == target} 在终态判断之前）合法，故排除 target=DONE；
     * 该 done→done 幂等场景由 TC5 覆盖。本用例校验 done 对其余 5 个状态的流转全部拒绝。</p>
     */
    @Test
    void TC4_终态锁死_done不能转到任何其他状态() {
        for (CaseStatus target : CaseStatus.values()) {
            if (target == CaseStatus.DONE) continue; // 幂等另测（TC5）
            assertFalse(CaseStatus.DONE.canTransitionTo(target),
                    "done→" + target + " 应拒绝（终态锁死，不可前进/回退）");
        }
    }

    // ==================== TC5 自身幂等 ====================

    /**
     * TC5：流转到自身当前状态合法（并发重试场景，避免误判）。
     *
     * <p>幂等判断（{@code this == target}）在实现中位于终态判断之前，因此 done→done 也合法——
     * 语义为"已完成的 case 再收到一次 done 转换不报错"，符合幂等防并发重试的设计意图。</p>
     */
    @Test
    void TC5_自身幂等_流转到当前状态合法() {
        assertTrue(CaseStatus.DRAFTING.canTransitionTo(CaseStatus.DRAFTING), "drafting→drafting 幂等应合法");
        assertTrue(CaseStatus.DERIVING.canTransitionTo(CaseStatus.DERIVING), "deriving→deriving 幂等应合法");
        assertTrue(CaseStatus.REVIEWING.canTransitionTo(CaseStatus.REVIEWING), "reviewing→reviewing 幂等应合法");
        assertTrue(CaseStatus.TESTING.canTransitionTo(CaseStatus.TESTING), "testing→testing 幂等应合法");
        assertTrue(CaseStatus.DEPLOYING.canTransitionTo(CaseStatus.DEPLOYING), "deploying→deploying 幂等应合法");
        assertTrue(CaseStatus.DONE.canTransitionTo(CaseStatus.DONE), "done→done 幂等应合法（幂等优先于终态）");
    }

    // ==================== TC6 fromDbValue ====================

    /** TC6：fromDbValue 正确解析每个 db 值，未知值/空值返回 null。 */
    @Test
    void TC6_fromDbValue正确解析() {
        assertEquals(CaseStatus.DRAFTING, CaseStatus.fromDbValue("drafting"));
        assertEquals(CaseStatus.DERIVING, CaseStatus.fromDbValue("deriving"));
        assertEquals(CaseStatus.REVIEWING, CaseStatus.fromDbValue("reviewing"));
        assertEquals(CaseStatus.TESTING, CaseStatus.fromDbValue("testing"));
        assertEquals(CaseStatus.DEPLOYING, CaseStatus.fromDbValue("deploying"));
        assertEquals(CaseStatus.DONE, CaseStatus.fromDbValue("done"));
    }

    /** TC6 补充：dbValue 大小写敏感（列存小写，大写应返回 null，兼容历史脏数据策略）。 */
    @Test
    void TC6_fromDbValue大小写敏感_大写返回null() {
        assertNull(CaseStatus.fromDbValue("DRAFTING"), "dbValue 大小写敏感，大写应返回 null");
        assertNull(CaseStatus.fromDbValue("Drafting"));
    }

    /** TC6 补充：未知值返回 null（上层判空，不抛异常以兼容历史脏数据）。 */
    @Test
    void TC6_fromDbValue未知值返回null() {
        assertNull(CaseStatus.fromDbValue("unknown"), "未知值应返回 null");
        assertNull(CaseStatus.fromDbValue(""), "空字符串应返回 null");
        assertNull(CaseStatus.fromDbValue(null), "null 应返回 null");
        assertNull(CaseStatus.fromDbValue("archived"), "已废弃的状态值应返回 null");
    }

    /** TC6 补充：dbValue() 返回的字符串与列存一致（小写）。 */
    @Test
    void TC6_dbValue返回小写字符串() {
        assertEquals("drafting", CaseStatus.DRAFTING.dbValue());
        assertEquals("done", CaseStatus.DONE.dbValue());
        // 双向一致性：fromDbValue(dbValue()) 应返回原枚举
        for (CaseStatus s : CaseStatus.values()) {
            assertSame(s, CaseStatus.fromDbValue(s.dbValue()),
                    "fromDbValue(dbValue()) 应返回原枚举 " + s);
        }
    }

    // ==================== TC7 null 参数处理 ====================

    /** TC7：canTransitionTo 对 null 参数返回 false（不抛 NPE）。 */
    @Test
    void TC7_canTransitionTo_null参数返回false() {
        for (CaseStatus s : CaseStatus.values()) {
            assertFalse(s.canTransitionTo(null),
                    s + ".canTransitionTo(null) 应返回 false 而非抛 NPE");
        }
    }
}
