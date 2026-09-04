package com.eaiselp.runtime.governance;

import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 工程标准状态枚举（V6 F1.1 轻量级状态机，case-20260820 T2；流转端点 S6 由批B T12 交付）。
 *
 * <p>复刻 {@link com.eaiselp.runtime.hierarchy.AdrStatus} 先例：枚举纯逻辑不抛异常，
 * 非法流转返回 false 由上层 Service 抛 400；必填项规则内聚枚举，Service 只调用不重复定义。</p>
 *
 * <p>状态流转图（PRD §4.1.1 / SE 方案 D-8）：
 * <pre>
 *   draft ──发布──→ published
 *   draft ──作废(必填 deprecateReason)──→ deprecated
 *   published ──废弃(必填 deprecateReason)──→ deprecated
 *   deprecated = 终态，无出边（回退 400，AC-F1.2）
 * </pre>
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>幂等</b>：流转到自身视为合法（并发重试语义，同 AdrStatus/case-20260818 先例；
 *       deprecated→deprecated 幂等合法但无业务效果，T12 的 transit 据此短路）。</li>
 *   <li><b>draft→published 触发自动取代</b>：目标=published 时旧 published 版本在发布事务内
 *       自动置 deprecated（FOR UPDATE，D-7）——事务逻辑在 ServiceImpl.transit（批B T12），
 *       本枚举只声明图与必填项。</li>
 *   <li><b>deprecateReason 落列</b>（V6 纠偏）：必填规则在此声明，值由 Service 写
 *       t_standard.deprecate_reason 列 + 审计 detail。</li>
 * </ul>
 */
public enum StandardStatus {

    DRAFT("draft"),
    PUBLISHED("published"),
    DEPRECATED("deprecated");

    /** t_standard.status 列存的小写字符串值。 */
    private final String dbValue;

    StandardStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与 t_standard.status 列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 流转目标必填的业务字段（规则内聚枚举，Service 据此校验 400 文案）。 */
    public enum RequiredField {
        /** 废弃/作废原因（target=deprecated 必填；空=400，AC-F1.2） */
        DEPRECATE_REASON
    }

    /**
     * 判断从当前状态到目标状态的流转是否合法。
     *
     * <p>幂等优先（→自身合法）；draft→published/deprecated；published→deprecated；
     * deprecated 终态无出边；published→draft 非法（AC-F1.2）。</p>
     *
     * @param target 目标状态
     * @return 合法返回 true；target=null、published→draft、终态出边均返回 false（上层抛 400）
     */
    public boolean canTransitionTo(StandardStatus target) {
        if (target == null) {
            return false;
        }
        // 幂等：流转到自身合法（并发重试语义，AdrStatus 先例）
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
    public static Set<RequiredField> requiredFieldsFor(StandardStatus target) {
        if (target == DEPRECATED) {
            return EnumSet.of(RequiredField.DEPRECATE_REASON);
        }
        return EnumSet.noneOf(RequiredField.class);
    }

    /** 是否终态（deprecated 无出边，不可回退）。 */
    public boolean isTerminal() {
        return this == DEPRECATED;
    }

    /**
     * 按 t_standard.status 列存值解析为枚举。
     *
     * @return 对应枚举；未知值返回 null（上层判空处理，兼容历史脏数据）
     */
    public static StandardStatus fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (StandardStatus s : values()) {
            if (s.dbValue.equals(dbValue)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 合法流转映射表（不可变，类加载时初始化一次）。
     *
     * <p>draft→published（发布，触发自动取代）/deprecated（作废）；published→deprecated（废弃）；
     * deprecated 终态空集。</p>
     */
    private static final Map<StandardStatus, Set<StandardStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<StandardStatus, Set<StandardStatus>> m = new EnumMap<>(StandardStatus.class);
        m.put(DRAFT, EnumSet.of(PUBLISHED, DEPRECATED));            // 发布 / 作废
        m.put(PUBLISHED, EnumSet.of(DEPRECATED));                   // 废弃（必填原因）
        m.put(DEPRECATED, EnumSet.noneOf(StandardStatus.class));    // 终态
        Map<StandardStatus, Set<StandardStatus>> immutable = new EnumMap<>(StandardStatus.class);
        m.forEach((k, v) -> immutable.put(k, EnumSet.copyOf(v)));
        ALLOWED_TRANSITIONS = java.util.Collections.unmodifiableMap(immutable);
    }
}
