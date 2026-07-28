package com.eaiselp.runtime.casestate;

import java.util.EnumSet;
import java.util.Set;

/**
 * Case 状态枚举（M2 SP-3 轻量级状态机，替代 Spring Statemachine）。
 *
 * <p>状态流转图（与 t_case.status 列存的小写字符串一一对应）：
 * <pre>
 *   drafting → deriving → reviewing → testing → deploying → done
 *                 ↑          |          |          |
 *                 └──────────┴──────────┴──────────┘
 *                       可回退到 deriving（返工）
 * </pre>
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>正向流转</b>：每个状态只能前进到它的直接后继（不允许跨阶段跳跃，如 drafting 不能直接到 done）。</li>
 *   <li><b>返工回退</b>：reviewing / testing / deploying 均可回退到 deriving（返工重派生）。</li>
 *   <li><b>终态</b>：done 为终态，不可再流转。</li>
 *   <li><b>幂等</b>：流转到自身当前状态视为合法（幂等，不报错），避免并发重试误判。</li>
 * </ul>
 *
 * <p>注意：本枚举是「业务状态」，非体系角色/流程阶段名，不违反 ES-003 §9.1（G11 零角色硬编码）。
 * 状态值（drafting/deriving/...）与 t_case.status 列存字符串严格一致，序列化/反序列化走 {@link #dbValue()}。
 */
public enum CaseStatus {

    DRAFTING("drafting"),
    DERIVING("deriving"),
    REVIEWING("reviewing"),
    TESTING("testing"),
    DEPLOYING("deploying"),
    DONE("done");

    /** t_case.status 列存的小写字符串值。 */
    private final String dbValue;

    CaseStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与 t_case.status 列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /**
     * 判断从当前状态到目标状态的流转是否合法。
     *
     * <p>规则：直接后继（含自身幂等）或允许的返工回退路径。非法流转返回 false，
     * 由上层 Service 抛业务异常（不在枚举内抛，保持枚举纯逻辑、易测试）。
     *
     * @param target 目标状态
     * @return 合法返回 true，否则 false
     */
    public boolean canTransitionTo(CaseStatus target) {
        if (target == null) {
            return false;
        }
        // 幂等：流转到自身合法（并发重试场景）
        if (this == target) {
            return true;
        }
        // 终态不可再流转
        if (this == DONE) {
            return false;
        }
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /**
     * 按 t_case.status 列存值解析为枚举。
     *
     * @param dbValue 列存字符串（如 "drafting"）
     * @return 对应枚举；未知值返回 null（上层判空处理，不在此抛异常以兼容历史脏数据）
     */
    public static CaseStatus fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (CaseStatus s : values()) {
            if (s.dbValue.equals(dbValue)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 合法流转映射表（不可变，类加载时初始化一次）。
     *
     * <p>正向：drafting→deriving，deriving→reviewing，reviewing→testing，
     *         testing→deploying，deploying→done。
     * <p>返工：reviewing→deriving，testing→deriving，deploying→deriving。
     */
    private static final java.util.Map<CaseStatus, Set<CaseStatus>> ALLOWED_TRANSITIONS;

    static {
        java.util.Map<CaseStatus, Set<CaseStatus>> m = new java.util.EnumMap<>(CaseStatus.class);
        // 正向流转
        m.put(DRAFTING, EnumSet.of(DERIVING));
        m.put(DERIVING, EnumSet.of(REVIEWING));
        m.put(REVIEWING, EnumSet.of(TESTING, DERIVING));       // 前进 / 返工
        m.put(TESTING, EnumSet.of(DEPLOYING, DERIVING));       // 前进 / 返工
        m.put(DEPLOYING, EnumSet.of(DONE, DERIVING));          // 前进 / 返工
        m.put(DONE, EnumSet.noneOf(CaseStatus.class));         // 终态
        // 冻结为不可变视图，防止运行时被篡改
        java.util.Map<CaseStatus, Set<CaseStatus>> immutable = new java.util.EnumMap<>(CaseStatus.class);
        m.forEach((k, v) -> immutable.put(k, EnumSet.copyOf(v)));
        ALLOWED_TRANSITIONS = java.util.Collections.unmodifiableMap(immutable);
    }
}
