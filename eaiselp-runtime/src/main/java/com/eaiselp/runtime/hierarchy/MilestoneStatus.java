package com.eaiselp.runtime.hierarchy;

import java.util.EnumSet;
import java.util.Set;

/**
 * 里程碑状态枚举（V5 F2 轻量级状态机，case-20260818 T6）。
 *
 * <p>复刻 {@link com.eaiselp.runtime.casestate.CaseStatus} 先例（F-29）：枚举纯逻辑不抛异常，
 * 非法流转返回 false 由上层 Service 抛 400；状态值与 t_milestone.status 列存字符串一一对应。</p>
 *
 * <p>状态流转图：
 * <pre>
 *   planned ──确认达成(achievedDate 必填,默认当天)──→ achieved
 *   planned ──标记延期──────────────────────────→ delayed
 *   delayed ──确认达成(achievedDate 必填)─────────→ achieved
 *   achieved ──撤销(清空 achieved_date,留审计)────→ planned
 * </pre>
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>幂等</b>：流转到自身视为合法（并发重试语义，同 CaseStatus）。</li>
 *   <li><b>非法边</b>：achieved→delayed（已达成的里程碑不存在"延期中"态）等一律 false → 上层 400。</li>
 *   <li><b>系统永不自动置 achieved/delayed</b>（PRD §4.2.3）：逾期仅展示层 Vo.overdue 黄角标，
 *       状态变更只走人工 transit 入口——本枚举只提供判定，不含任何自动触发。</li>
 *   <li><b>必填项规则内聚</b>：target=achieved 必填达成日期（{@link #requiredFieldsFor}），
 *       Service 只调用不重复定义。</li>
 * </ul>
 */
public enum MilestoneStatus {

    PLANNED("planned"),
    ACHIEVED("achieved"),
    DELAYED("delayed");

    /** t_milestone.status 列存的小写字符串值。 */
    private final String dbValue;

    MilestoneStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与 t_milestone.status 列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 流转目标必填的业务字段（规则内聚枚举，Service 据此校验 400 文案）。 */
    public enum RequiredField {
        /** 达成日期（target=achieved 时必填；Service 层默认当天兜底） */
        ACHIEVED_DATE
    }

    /**
     * 判断从当前状态到目标状态的流转是否合法（幂等优先；achieved→delayed 等非法边返回 false）。
     *
     * @param target 目标状态
     * @return 合法返回 true；target=null 或非法流转返回 false（上层抛 400，枚举不抛）
     */
    public boolean canTransitionTo(MilestoneStatus target) {
        if (target == null) {
            return false;
        }
        // 幂等：流转到自身合法（并发重试场景，同 CaseStatus 先例）
        if (this == target) {
            return true;
        }
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /**
     * 目标状态要求的必填业务字段集合。
     *
     * @param target 流转目标（null 或无需必填项返回空集）
     */
    public static Set<RequiredField> requiredFieldsFor(MilestoneStatus target) {
        if (target == ACHIEVED) {
            return EnumSet.of(RequiredField.ACHIEVED_DATE);
        }
        return EnumSet.noneOf(RequiredField.class);
    }

    /**
     * 按 t_milestone.status 列存值解析为枚举。
     *
     * @return 对应枚举；未知值/历史脏数据（如 V1 的 not_started）返回 null（上层判空处理）
     */
    public static MilestoneStatus fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (MilestoneStatus s : values()) {
            if (s.dbValue.equals(dbValue)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 合法流转映射表（不可变，类加载时初始化一次）。
     *
     * <p>planned→achieved（达成）/ planned→delayed（延期）/ delayed→achieved（补达成）/
     * achieved→planned（撤销清日期）。无终态——achieved 可撤销回 planned 是唯一出边。</p>
     */
    private static final java.util.Map<MilestoneStatus, Set<MilestoneStatus>> ALLOWED_TRANSITIONS;

    static {
        java.util.Map<MilestoneStatus, Set<MilestoneStatus>> m = new java.util.EnumMap<>(MilestoneStatus.class);
        m.put(PLANNED, EnumSet.of(ACHIEVED, DELAYED));   // 确认达成 / 标记延期
        m.put(DELAYED, EnumSet.of(ACHIEVED));            // 延期后补达成
        m.put(ACHIEVED, EnumSet.of(PLANNED));            // 撤销误确认（清空达成日期）
        java.util.Map<MilestoneStatus, Set<MilestoneStatus>> immutable = new java.util.EnumMap<>(MilestoneStatus.class);
        m.forEach((k, v) -> immutable.put(k, EnumSet.copyOf(v)));
        ALLOWED_TRANSITIONS = java.util.Collections.unmodifiableMap(immutable);
    }
}
