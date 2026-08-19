package com.eaiselp.runtime.hierarchy;

import java.util.EnumSet;
import java.util.Set;

/**
 * ADR 状态枚举（V5 F4 轻量级状态机，case-20260818 T7）。
 *
 * <p>复刻 {@link com.eaiselp.runtime.casestate.CaseStatus} 先例（F-29）：枚举纯逻辑不抛异常，
 * 非法流转返回 false 由上层 Service 抛 400；必填项规则内聚枚举，Service 只调用不重复定义。</p>
 *
 * <p>状态流转图：
 * <pre>
 *   proposed ──评审通过──→ accepted
 *   accepted ──废弃(必填 deprecateReason)──→ deprecated   ┐
 *   accepted ──取代(必填 supersededBy)────→ superseded   ┘ 目标须 accepted 且≠自身（Service 校验）
 *   deprecated / superseded = 终态，无出边（回退 400，AC-F4.2）
 * </pre>
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>幂等</b>：流转到自身视为合法（并发重试语义）——终态幂等亦合法（同 CaseStatus 的
 *       done→done 先例："已终态的 ADR 再收到同终态转换不报错"）。</li>
 *   <li><b>跳过 accepted 非法</b>：proposed→superseded/deprecated 返回 false（AC-F4.2 用例）。</li>
 *   <li><b>deprecateReason 不落列（C3 收敛）</b>：本枚举只声明"必填"规则；值由 Service 写入
 *       t_governance_log 审计 detail 并在响应/详情回显，实体无该字段。</li>
 * </ul>
 */
public enum AdrStatus {

    PROPOSED("proposed"),
    ACCEPTED("accepted"),
    DEPRECATED("deprecated"),
    SUPERSEDED("superseded");

    /** t_adr.status 列存的小写字符串值。 */
    private final String dbValue;

    AdrStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与 t_adr.status 列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 流转目标必填的业务字段（规则内聚枚举，Service 据此校验 400 文案）。 */
    public enum RequiredField {
        /** 废弃说明（target=deprecated 必填；PRD §4.4.2 行为权威，空=400） */
        DEPRECATE_REASON,
        /** 取代指向的新 ADR 编号（target=superseded 必填） */
        SUPERSEDED_BY
    }

    /**
     * 判断从当前状态到目标状态的流转是否合法。
     *
     * <p>幂等优先（终态→自身合法）；proposed 只能到 accepted；accepted 只能到
     * deprecated/superseded；deprecated/superseded 终态无出边。</p>
     *
     * @param target 目标状态
     * @return 合法返回 true；target=null、跳过 accepted、终态回退均返回 false（上层抛 400）
     */
    public boolean canTransitionTo(AdrStatus target) {
        if (target == null) {
            return false;
        }
        // 幂等：流转到自身合法（含终态→自身，并发重试语义，同 CaseStatus 先例）
        if (this == target) {
            return true;
        }
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /**
     * 目标状态要求的必填业务字段集合。
     *
     * @param target 流转目标（null/无必填项返回空集）
     */
    public static Set<RequiredField> requiredFieldsFor(AdrStatus target) {
        if (target == DEPRECATED) {
            return EnumSet.of(RequiredField.DEPRECATE_REASON);
        }
        if (target == SUPERSEDED) {
            return EnumSet.of(RequiredField.SUPERSEDED_BY);
        }
        return EnumSet.noneOf(RequiredField.class);
    }

    /** 是否终态（deprecated / superseded 无出边，不可回退）。 */
    public boolean isTerminal() {
        return this == DEPRECATED || this == SUPERSEDED;
    }

    /**
     * 按 t_adr.status 列存值解析为枚举。
     *
     * @return 对应枚举；未知值返回 null（上层判空处理，兼容历史脏数据）
     */
    public static AdrStatus fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (AdrStatus s : values()) {
            if (s.dbValue.equals(dbValue)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 合法流转映射表（不可变，类加载时初始化一次）。
     *
     * <p>proposed→accepted；accepted→deprecated（必填废弃说明）/superseded（必填指向）；
     * 两终态空集（回退非法）。</p>
     */
    private static final java.util.Map<AdrStatus, Set<AdrStatus>> ALLOWED_TRANSITIONS;

    static {
        java.util.Map<AdrStatus, Set<AdrStatus>> m = new java.util.EnumMap<>(AdrStatus.class);
        m.put(PROPOSED, EnumSet.of(ACCEPTED));                       // 唯一出边：评审通过
        m.put(ACCEPTED, EnumSet.of(DEPRECATED, SUPERSEDED));        // 废弃 / 被取代
        m.put(DEPRECATED, EnumSet.noneOf(AdrStatus.class));         // 终态
        m.put(SUPERSEDED, EnumSet.noneOf(AdrStatus.class));         // 终态
        java.util.Map<AdrStatus, Set<AdrStatus>> immutable = new java.util.EnumMap<>(AdrStatus.class);
        m.forEach((k, v) -> immutable.put(k, EnumSet.copyOf(v)));
        ALLOWED_TRANSITIONS = java.util.Collections.unmodifiableMap(immutable);
    }
}
