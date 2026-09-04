package com.eaiselp.runtime.governance;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 商业案例状态枚举（case-20260821 T4，V7 F2.1 状态机；流转端点 B7 由 T11 接通）。
 *
 * <p>复刻 {@link StandardStatus} 先例：纯逻辑不抛异常、流转图内聚、必填项内聚、
 * 自流转幂等、终态无出边。</p>
 *
 * <p>状态流转图（PRD §4.4.2 / 裁决 Q3；executing 唯一合法出边=done 以 PRD 图为准，
 * V7 注释同锚定）：
 * <pre>
 *   draft ──批准──→ approved ──启动执行──→ executing ──完成──→ done（终态）
 *   draft ──拒绝(必填 rejectedReason)──→ rejected（终态）
 * </pre>
 * 其余流转一律非法：draft→executing 跳级、approved→rejected（批准后不可撤销）、
 * rejected/done 终态出边（AC-F2.6）。</p>
 *
 * <p>编辑/删除限制（AC-F2.7，Service 承载）：draft 全字段可编辑；approved/executing
 * 输入只读（仅 decision_note 可经 B6 更新）；rejected/done 全只读；仅 draft 可逻辑删。</p>
 */
public enum BizCaseStatus {

    DRAFT("draft"),
    APPROVED("approved"),
    REJECTED("rejected"),
    EXECUTING("executing"),
    DONE("done");

    /** t_business_case.status 列存的小写字符串值。 */
    private final String dbValue;

    BizCaseStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与 t_business_case.status 列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 流转目标必填的业务字段（规则内聚枚举，Service 据此校验 400 文案）。 */
    public enum RequiredField {
        /** 拒绝原因（target=rejected 必填；空=400，AC-F2.6） */
        REJECTED_REASON
    }

    /**
     * 判断从当前状态到目标状态的流转是否合法。
     *
     * <p>幂等优先（→自身合法）；draft→approved/rejected；approved→executing；executing→done；
     * rejected/done 终态无出边；跳级与撤销均非法。</p>
     *
     * @param target 目标状态
     * @return 合法返回 true；target=null、跳级、撤销、终态出边均返回 false（上层抛 400）
     */
    public boolean canTransitionTo(BizCaseStatus target) {
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
    public static Set<RequiredField> requiredFieldsFor(BizCaseStatus target) {
        if (target == REJECTED) {
            return EnumSet.of(RequiredField.REJECTED_REASON);
        }
        return EnumSet.noneOf(RequiredField.class);
    }

    /** 是否终态（rejected/done 无出边；裁决 Q3：不设 reopen/撤销，审计留痕承担追溯）。 */
    public boolean isTerminal() {
        return this == REJECTED || this == DONE;
    }

    /**
     * 按 t_business_case.status 列存值解析为枚举。
     *
     * @return 对应枚举；未知值返回 null（上层判空处理，兼容历史脏数据）
     */
    public static BizCaseStatus fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (BizCaseStatus s : values()) {
            if (s.dbValue.equals(dbValue)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 合法流转映射表（不可变，类加载时初始化一次）。
     *
     * <p>draft→approved（批准）/rejected（必填原因）；approved→executing（启动执行）；
     * executing→done（完成）；rejected/done 终态空集。</p>
     */
    private static final Map<BizCaseStatus, Set<BizCaseStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<BizCaseStatus, Set<BizCaseStatus>> m = new EnumMap<>(BizCaseStatus.class);
        m.put(DRAFT, EnumSet.of(APPROVED, REJECTED));        // 批准 / 拒绝（必填原因）
        m.put(APPROVED, EnumSet.of(EXECUTING));              // 启动执行（不可撤销拒绝）
        m.put(EXECUTING, EnumSet.of(DONE));                  // 完成
        m.put(REJECTED, EnumSet.noneOf(BizCaseStatus.class)); // 终态
        m.put(DONE, EnumSet.noneOf(BizCaseStatus.class));    // 终态
        Map<BizCaseStatus, Set<BizCaseStatus>> immutable = new EnumMap<>(BizCaseStatus.class);
        m.forEach((k, v) -> immutable.put(k, EnumSet.copyOf(v)));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(immutable);
    }
}
