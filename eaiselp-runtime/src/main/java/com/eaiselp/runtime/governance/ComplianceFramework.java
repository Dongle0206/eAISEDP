package com.eaiselp.runtime.governance;

/**
 * 合规框架枚举（case-20260821 T3，V7 F1.2；领域数据字典枚举——PRD §0 P6 裁决，应用层校验）。
 *
 * <p>展示名（前端 governance-dict.js 集中一处映射）：djba2.0=等保 2.0 / iso27001=ISO27001 /
 * gdpr=GDPR / custom=自定义。</p>
 *
 * <p><b>custom↔framework_name 双向联动</b>（AC-F1.9 四例，Service 校验）：
 * custom 时 frameworkName 必填（空→400）；非 custom 时必须为空（填了→400 防脏数据）。</p>
 */
public enum ComplianceFramework {

    DJBA2_0("djba2.0"),
    ISO27001("iso27001"),
    GDPR("gdpr"),
    CUSTOM("custom");

    /** t_compliance_check.framework 列存的小写字符串值。 */
    private final String dbValue;

    ComplianceFramework(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 合法值集合字符串（错误 message 拼接用，"djba2.0/iso27001/gdpr/custom"）。 */
    public static String legalValues() {
        StringBuilder sb = new StringBuilder();
        for (ComplianceFramework f : values()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(f.dbValue);
        }
        return sb.toString();
    }

    /** 是否自定义框架（custom 时 frameworkName 必填、非 custom 必须为空，AC-F1.9）。 */
    public boolean isCustom() {
        return this == CUSTOM;
    }

    /**
     * 按列存值解析为枚举。
     *
     * @return 对应枚举；未知/null 返回 null（上层判空抛 400 指名字段与合法值集）
     */
    public static ComplianceFramework fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (ComplianceFramework f : values()) {
            if (f.dbValue.equals(dbValue)) {
                return f;
            }
        }
        return null;
    }
}
