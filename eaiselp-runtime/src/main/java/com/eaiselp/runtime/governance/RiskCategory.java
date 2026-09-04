package com.eaiselp.runtime.governance;

/**
 * 风险类别枚举（case-20260821 T2，V7 F1.1；领域数据字典枚举——PRD §0 P6 裁决，
 * 随平台演进不做租户配置化，应用层校验非法 400）。
 *
 * <p>展示名（前端 governance-dict.js 集中一处映射）：strategy=战略 / compliance=合规 /
 * operations=运营 / technical=技术 / security=安全。</p>
 *
 * <p>非法值校验形态（AC-F1.1）：{@link #fromDbValue} 返回 null → Service 抛 400 并指名
 * 字段与合法值集合（{@link Sensitivity} 先例）。</p>
 */
public enum RiskCategory {

    STRATEGY("strategy"),
    COMPLIANCE("compliance"),
    OPERATIONS("operations"),
    TECHNICAL("technical"),
    SECURITY("security");

    /** t_risk.category 列存的小写字符串值。 */
    private final String dbValue;

    RiskCategory(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 合法值集合字符串（错误 message 拼接用，"strategy/compliance/operations/technical/security"）。 */
    public static String legalValues() {
        StringBuilder sb = new StringBuilder();
        for (RiskCategory c : values()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(c.dbValue);
        }
        return sb.toString();
    }

    /**
     * 按列存值解析为枚举。
     *
     * @return 对应枚举；未知/null 返回 null（上层判空抛 400 指名字段与合法值集）
     */
    public static RiskCategory fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (RiskCategory c : values()) {
            if (c.dbValue.equals(dbValue)) {
                return c;
            }
        }
        return null;
    }
}
