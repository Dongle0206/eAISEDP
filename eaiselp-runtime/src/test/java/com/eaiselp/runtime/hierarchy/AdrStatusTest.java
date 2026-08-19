package com.eaiselp.runtime.hierarchy;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdrStatus 状态机纯逻辑单测（case-20260818 T7，AC-F4.2 验收基线）。
 *
 * <p>覆盖：合法三边 / 跳过 accepted 非法 / 终态锁死 / 幂等（含终态幂等）/ fromDbValue /
 * 必填项规则（deprecated→deprecateReason、superseded→supersededBy，C3 不落列但校验照旧）。</p>
 */
class AdrStatusTest {

    /** TC1 合法三边：评审通过 / 废弃 / 被取代（AC-F4.2 状态机全边）。 */
    @Test
    void TC1_合法三边() {
        assertTrue(AdrStatus.PROPOSED.canTransitionTo(AdrStatus.ACCEPTED), "proposed→accepted");
        assertTrue(AdrStatus.ACCEPTED.canTransitionTo(AdrStatus.DEPRECATED), "accepted→deprecated（必填废弃说明）");
        assertTrue(AdrStatus.ACCEPTED.canTransitionTo(AdrStatus.SUPERSEDED), "accepted→superseded（必填指向）");
    }

    /** TC2 跳过 accepted 非法（AC-F4.2 用例：proposed 直达两终态拒绝）。 */
    @Test
    void TC2_跳过accepted非法() {
        assertFalse(AdrStatus.PROPOSED.canTransitionTo(AdrStatus.SUPERSEDED), "proposed→superseded 跳过 accepted");
        assertFalse(AdrStatus.PROPOSED.canTransitionTo(AdrStatus.DEPRECATED), "proposed→deprecated 跳过 accepted");
    }

    /** TC3 终态锁死：deprecated/superseded 无出边（回 proposed 400，AC-F4.2）。 */
    @Test
    void TC3_终态锁死() {
        assertTrue(AdrStatus.DEPRECATED.isTerminal());
        assertTrue(AdrStatus.SUPERSEDED.isTerminal());
        assertFalse(AdrStatus.PROPOSED.isTerminal());
        assertFalse(AdrStatus.ACCEPTED.isTerminal());
        for (AdrStatus target : AdrStatus.values()) {
            if (target == AdrStatus.DEPRECATED) continue;   // 幂等另测
            assertFalse(AdrStatus.DEPRECATED.canTransitionTo(target),
                    "deprecated→" + target + " 终态无出边");
            if (target == AdrStatus.SUPERSEDED) continue;
            assertFalse(AdrStatus.SUPERSEDED.canTransitionTo(target),
                    "superseded→" + target + " 终态无出边");
        }
    }

    /** TC4 非法边全集矩阵（含 accepted→proposed 回退拒绝）。 */
    @Test
    void TC4_非法边矩阵() {
        Set<AdrStatus> proposedOk = EnumSet.of(AdrStatus.PROPOSED, AdrStatus.ACCEPTED);
        Set<AdrStatus> acceptedOk = EnumSet.of(AdrStatus.ACCEPTED, AdrStatus.DEPRECATED, AdrStatus.SUPERSEDED);
        for (AdrStatus target : AdrStatus.values()) {
            assertEquals(proposedOk.contains(target), AdrStatus.PROPOSED.canTransitionTo(target),
                    "proposed→" + target.dbValue());
            assertEquals(acceptedOk.contains(target), AdrStatus.ACCEPTED.canTransitionTo(target),
                    "accepted→" + target.dbValue());
        }
    }

    /** TC5 幂等：流转到自身合法（含终态→自身，并发重试语义，同 CaseStatus done→done 先例）。 */
    @Test
    void TC5_自身幂等含终态() {
        for (AdrStatus s : AdrStatus.values()) {
            assertTrue(s.canTransitionTo(s), s + "→自身 幂等应合法");
        }
    }

    /** TC6 null 参数返回 false。 */
    @Test
    void TC6_null参数返回false() {
        for (AdrStatus s : AdrStatus.values()) {
            assertFalse(s.canTransitionTo(null));
        }
    }

    /** TC7 fromDbValue：四值解析 / 未知值 null / dbValue 双向一致。 */
    @Test
    void TC7_fromDbValue() {
        assertEquals(AdrStatus.PROPOSED, AdrStatus.fromDbValue("proposed"));
        assertEquals(AdrStatus.ACCEPTED, AdrStatus.fromDbValue("accepted"));
        assertEquals(AdrStatus.DEPRECATED, AdrStatus.fromDbValue("deprecated"));
        assertEquals(AdrStatus.SUPERSEDED, AdrStatus.fromDbValue("superseded"));
        assertNull(AdrStatus.fromDbValue("unknown"));
        assertNull(AdrStatus.fromDbValue(null));
        assertNull(AdrStatus.fromDbValue(""));
        for (AdrStatus s : AdrStatus.values()) {
            assertSame(s, AdrStatus.fromDbValue(s.dbValue()));
        }
    }

    /** TC8 必填项规则内聚（C3：deprecateReason 不落列但"必填"规则仍在，PRD §4.4.2 行为权威）。 */
    @Test
    void TC8_requiredFieldsFor() {
        assertEquals(EnumSet.of(AdrStatus.RequiredField.DEPRECATE_REASON),
                AdrStatus.requiredFieldsFor(AdrStatus.DEPRECATED));
        assertEquals(EnumSet.of(AdrStatus.RequiredField.SUPERSEDED_BY),
                AdrStatus.requiredFieldsFor(AdrStatus.SUPERSEDED));
        assertTrue(AdrStatus.requiredFieldsFor(AdrStatus.PROPOSED).isEmpty());
        assertTrue(AdrStatus.requiredFieldsFor(AdrStatus.ACCEPTED).isEmpty());
        assertTrue(AdrStatus.requiredFieldsFor(null).isEmpty());
    }
}
