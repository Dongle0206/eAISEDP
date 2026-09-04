package com.eaiselp.runtime.governance;

/**
 * 合规检查结果枚举（case-20260821 T3，V7 F1.2；领域数据字典枚举，应用层校验非法 400）。
 *
 * <p>结果为<b>覆盖式单值当前态</b>（不建历史表）：旧值唯一留痕 = t_governance_log 审计
 * detail（含 oldResult→newResult，AC-F1.10；同 V6 质量规则先例）。手动登记制——
 * 平台不做条款符合性自动判定（PRD §7-6），result 由登记人判定填写。</p>
 */
public enum ComplianceResult {

    PASS("pass"),
    FAIL("fail"),
    PARTIAL("partial"),
    NA("na");

    /** t_compliance_check.result 列存的小写字符串值。 */
    private final String dbValue;

    ComplianceResult(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 合法值集合字符串（错误 message 拼接用，"pass/fail/partial/na"）。 */
    public static String legalValues() {
        StringBuilder sb = new StringBuilder();
        for (ComplianceResult r : values()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(r.dbValue);
        }
        return sb.toString();
    }

    /**
     * 按列存值解析为枚举。
     *
     * @return 对应枚举；未知/null 返回 null（上层判空抛 400 指名字段与合法值集）
     */
    public static ComplianceResult fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (ComplianceResult r : values()) {
            if (r.dbValue.equals(dbValue)) {
                return r;
            }
        }
        return null;
    }
}
