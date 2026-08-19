package com.eaiselp.runtime.hierarchy;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MilestoneStatus 状态机纯逻辑单测（case-20260818 T6，AC-F2.2/F2.3 验收基线）。
 *
 * <p>复刻 CaseStatusTest 先例：无 Spring 上下文，直接断言枚举流转不变量——
 * 合法四边 / 非法边（achieved→delayed 等）/ 幂等 / fromDbValue / 必填项规则内聚。</p>
 */
class MilestoneStatusTest {

    /** TC1 合法流转四边：达成/延期/补达成/撤销（AC-F2.2 状态机全边覆盖）。 */
    @Test
    void TC1_合法流转四边() {
        assertTrue(MilestoneStatus.PLANNED.canTransitionTo(MilestoneStatus.ACHIEVED), "planned→achieved 确认达成");
        assertTrue(MilestoneStatus.PLANNED.canTransitionTo(MilestoneStatus.DELAYED), "planned→delayed 标记延期");
        assertTrue(MilestoneStatus.DELAYED.canTransitionTo(MilestoneStatus.ACHIEVED), "delayed→achieved 补达成");
        assertTrue(MilestoneStatus.ACHIEVED.canTransitionTo(MilestoneStatus.PLANNED), "achieved→planned 撤销");
    }

    /** TC2 非法边：achieved→delayed（已达成不存在延期中态）与 delayed→planned 回退拒绝。 */
    @Test
    void TC2_非法边全部拒绝() {
        assertFalse(MilestoneStatus.ACHIEVED.canTransitionTo(MilestoneStatus.DELAYED),
                "achieved→delayed 非法（AC 契约示例边）");
        assertFalse(MilestoneStatus.DELAYED.canTransitionTo(MilestoneStatus.PLANNED), "delayed→planned 非法");
    }

    /** TC2 补充：非法边全集矩阵（除自身幂等与四条合法边外全 false）。 */
    @Test
    void TC2_补充_非法边矩阵() {
        // 期望的合法目标集合（含自身）
        Set<MilestoneStatus> plannedOk = EnumSet.of(MilestoneStatus.PLANNED, MilestoneStatus.ACHIEVED, MilestoneStatus.DELAYED);
        Set<MilestoneStatus> delayedOk = EnumSet.of(MilestoneStatus.DELAYED, MilestoneStatus.ACHIEVED);
        Set<MilestoneStatus> achievedOk = EnumSet.of(MilestoneStatus.ACHIEVED, MilestoneStatus.PLANNED);
        for (MilestoneStatus target : MilestoneStatus.values()) {
            assertEquals(plannedOk.contains(target), MilestoneStatus.PLANNED.canTransitionTo(target),
                    "planned→" + target.dbValue() + " 判定与矩阵不符");
            assertEquals(delayedOk.contains(target), MilestoneStatus.DELAYED.canTransitionTo(target),
                    "delayed→" + target.dbValue() + " 判定与矩阵不符");
            assertEquals(achievedOk.contains(target), MilestoneStatus.ACHIEVED.canTransitionTo(target),
                    "achieved→" + target.dbValue() + " 判定与矩阵不符");
        }
    }

    /** TC3 幂等：流转到自身合法（并发重试语义，同 CaseStatus 先例）。 */
    @Test
    void TC3_自身幂等() {
        for (MilestoneStatus s : MilestoneStatus.values()) {
            assertTrue(s.canTransitionTo(s), s + "→自身 幂等应合法");
        }
    }

    /** TC4 null 参数：返回 false 不抛 NPE。 */
    @Test
    void TC4_null参数返回false() {
        for (MilestoneStatus s : MilestoneStatus.values()) {
            assertFalse(s.canTransitionTo(null), s + ".canTransitionTo(null) 应 false");
        }
    }

    /** TC5 fromDbValue：正确解析三值；V1 历史 not_started 与未知值返回 null。 */
    @Test
    void TC5_fromDbValue() {
        assertEquals(MilestoneStatus.PLANNED, MilestoneStatus.fromDbValue("planned"));
        assertEquals(MilestoneStatus.ACHIEVED, MilestoneStatus.fromDbValue("achieved"));
        assertEquals(MilestoneStatus.DELAYED, MilestoneStatus.fromDbValue("delayed"));
        assertNull(MilestoneStatus.fromDbValue("not_started"), "V1 废弃词汇应返回 null（历史脏数据兼容）");
        assertNull(MilestoneStatus.fromDbValue("PLANNED"), "大小写敏感");
        assertNull(MilestoneStatus.fromDbValue(""));
        assertNull(MilestoneStatus.fromDbValue(null));
        for (MilestoneStatus s : MilestoneStatus.values()) {
            assertSame(s, MilestoneStatus.fromDbValue(s.dbValue()), "dbValue 双向一致");
        }
    }

    /** TC6 必填项规则内聚：target=achieved 必填达成日期；其余目标无必填（AC-F2.2）。 */
    @Test
    void TC6_requiredFieldsFor() {
        assertEquals(EnumSet.of(MilestoneStatus.RequiredField.ACHIEVED_DATE),
                MilestoneStatus.requiredFieldsFor(MilestoneStatus.ACHIEVED));
        assertTrue(MilestoneStatus.requiredFieldsFor(MilestoneStatus.PLANNED).isEmpty());
        assertTrue(MilestoneStatus.requiredFieldsFor(MilestoneStatus.DELAYED).isEmpty());
        assertTrue(MilestoneStatus.requiredFieldsFor(null).isEmpty());
    }
}
