package com.eaiselp.runtime.governance;

/**
 * 数据资产敏感等级枚举（V6 F2.1，裁决 Q2——四档固定分级，不做租户自定义，防筛选/色阶
 * 逻辑碎片化；case-20260820 T4）。
 *
 * <p>非法值校验形态（AC-F2.2）：{@link #fromDbValue} 返回 null → Service 抛 400 并指名
 * 字段与合法值集合。敏感等级为元数据标注，行级可见性/脱敏为范围外（PRD §7-9）。</p>
 */
public enum Sensitivity {

    PUBLIC("public"),
    INTERNAL("internal"),
    SENSITIVE("sensitive"),
    CONFIDENTIAL("confidential");

    /** t_data_asset.sensitivity 列存的小写字符串值。 */
    private final String dbValue;

    Sensitivity(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 合法值集合字符串（错误 message 拼接用，"public/internal/sensitive/confidential"）。 */
    public static String legalValues() {
        StringBuilder sb = new StringBuilder();
        for (Sensitivity s : values()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(s.dbValue);
        }
        return sb.toString();
    }

    /**
     * 按列存值解析为枚举。
     *
     * @return 对应枚举；未知/null 返回 null（上层判空抛 400 指名字段与合法值集）
     */
    public static Sensitivity fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (Sensitivity s : values()) {
            if (s.dbValue.equals(dbValue)) {
                return s;
            }
        }
        return null;
    }
}
