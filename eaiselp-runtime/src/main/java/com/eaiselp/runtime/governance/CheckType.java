package com.eaiselp.runtime.governance;

/**
 * 数据质量检查类型枚举（V6 F2.2 领域数据字典，case-20260820 T5；PRD §0 P6 裁决）。
 *
 * <p>非法值校验形态（AC-F2.5）：{@link #fromDbValue} 返回 null → Service 抛 400 并指名
 * 字段与合法值集合（"checkType 非法: timeliness_（应为 completeness/accuracy/consistency/
 * timeliness）"）。</p>
 */
public enum CheckType {

    COMPLETENESS("completeness"),
    ACCURACY("accuracy"),
    CONSISTENCY("consistency"),
    TIMELINESS("timeliness");

    /** t_data_quality_rule.check_type 列存的小写字符串值。 */
    private final String dbValue;

    CheckType(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 合法值集合字符串（错误 message 拼接用）。 */
    public static String legalValues() {
        StringBuilder sb = new StringBuilder();
        for (CheckType t : values()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(t.dbValue);
        }
        return sb.toString();
    }

    /**
     * 按列存值解析为枚举。
     *
     * @return 对应枚举；未知/null 返回 null（上层判空抛 400 指名字段与合法值集）
     */
    public static CheckType fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (CheckType t : values()) {
            if (t.dbValue.equals(dbValue)) {
                return t;
            }
        }
        return null;
    }
}
