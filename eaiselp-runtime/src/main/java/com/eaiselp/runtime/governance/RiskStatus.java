package com.eaiselp.runtime.governance;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 风险状态枚举（case-20260821 T2，V7 F1.1 轻量级状态机；流转端点 R6 由 T10 接通）。
 *
 * <p>复刻 {@link StandardStatus} 先例：枚举纯逻辑不抛异常，非法流转返回 false 由上层
 * Service 抛 400；必填项规则内聚枚举。</p>
 *
 * <p>状态流转图（PRD §4.1.3 / 裁决 Q3）：
 * <pre>
 *   open ──开始缓解──→ mitigating ──关闭(必填 resolutionNote)──→ closed（终态）
 *   mitigating ──回退(缓解无效/风险复燃)──→ open
 * </pre>
 *
 * <p><b>§0.3-1 消解结论锚定</b>（防 Dev/QA 各执一词，SE 方案 §0.3-1 / tasks.md 给 QA 标注 1）：
 * 裁决 Q3"open→mitigating→closed 单向"与 PRD AC-F1.4"mitigating→open → 成功（回退合法）"
 * 字面冲突，消解结论 = <b>保留 mitigating→open 回退（合法）</b>；裁决"单向"语义收窄为
 * "<b>closed 终态不可 reopen、无任何终态回退</b>"。QA 照 AC-F1.4 原文执行（回退=200）。</p>
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>幂等</b>：流转到自身视为合法（并发重试语义，同 StandardStatus 先例；
 *       closed→closed 幂等合法但无业务效果，transit 据此短路不更新不审计）。</li>
 *   <li><b>resolutionNote 语义</b>（V7 列注释契约）：仅 closed 态可非空，非 closed 状态
 *       该列须置 NULL——由 Service 在编辑/流转时保证。</li>
 * </ul></p>
 */
public enum RiskStatus {

    OPEN("open"),
    MITIGATING("mitigating"),
    CLOSED("closed");

    /** t_risk.status 列存的小写字符串值。 */
    private final String dbValue;

    RiskStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与 t_risk.status 列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 流转目标必填的业务字段（规则内聚枚举，Service 据此校验 400 文案）。 */
    public enum RequiredField {
        /** 处置说明（target=closed 必填；空=400，AC-F1.4） */
        RESOLUTION_NOTE
    }

    /**
     * 判断从当前状态到目标状态的流转是否合法。
     *
     * <p>幂等优先（→自身合法）；open→mitigating；mitigating→closed / open（回退合法，
     * §0.3-1 消解）；closed 终态无出边；open→closed 跳级非法。</p>
     *
     * @param target 目标状态
     * @return 合法返回 true；target=null、跳级、终态出边均返回 false（上层抛 400）
     */
    public boolean canTransitionTo(RiskStatus target) {
        if (target == null) {
            return false;
        }
        // 幂等：流转到自身合法（并发重试语义，StandardStatus 先例）
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
    public static Set<RequiredField> requiredFieldsFor(RiskStatus target) {
        if (target == CLOSED) {
            return EnumSet.of(RequiredField.RESOLUTION_NOTE);
        }
        return EnumSet.noneOf(RequiredField.class);
    }

    /** 是否终态（closed 无出边，不可 reopen——复燃风险新建条目并互链描述，PRD Q2-②）。 */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    /**
     * 按 t_risk.status 列存值解析为枚举。
     *
     * @return 对应枚举；未知值返回 null（上层判空处理，兼容历史脏数据）
     */
    public static RiskStatus fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (RiskStatus s : values()) {
            if (s.dbValue.equals(dbValue)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 合法流转映射表（不可变，类加载时初始化一次）。
     *
     * <p>open→mitigating（开始缓解）；mitigating→closed（必填说明）/open（回退合法，§0.3-1）；
     * closed 终态空集。</p>
     */
    private static final Map<RiskStatus, Set<RiskStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<RiskStatus, Set<RiskStatus>> m = new EnumMap<>(RiskStatus.class);
        m.put(OPEN, EnumSet.of(MITIGATING));                       // 开始缓解
        m.put(MITIGATING, EnumSet.of(CLOSED, OPEN));               // 关闭（必填说明）/ 回退（§0.3-1 合法）
        m.put(CLOSED, EnumSet.noneOf(RiskStatus.class));           // 终态
        Map<RiskStatus, Set<RiskStatus>> immutable = new EnumMap<>(RiskStatus.class);
        m.forEach((k, v) -> immutable.put(k, EnumSet.copyOf(v)));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(immutable);
    }
}
