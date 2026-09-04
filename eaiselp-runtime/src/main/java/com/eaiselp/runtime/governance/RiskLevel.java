package com.eaiselp.runtime.governance;

/**
 * 风险等级枚举（case-20260821 T2，V7 F1.1；服务端由风险值四段闭区间映射，客户端不可提交）。
 *
 * <p>等级映射唯一口径（PRD §4.1.2 / {@link RiskCalculator}，AC-F1.2 边界断言）：
 * v∈[1,6]→low；[7,12]→medium；[13,19]→high；[20,25]→critical——闭区间左端点归属上一档的
 * 下一档，边界 6=低、7=中、12=中、13=高、19=高、20=极高。</p>
 */
public enum RiskLevel {

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical");

    /** t_risk.risk_level 列存的小写字符串值。 */
    private final String dbValue;

    RiskLevel(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 合法值集合字符串（错误 message 拼接用，"low/medium/high/critical"）。 */
    public static String legalValues() {
        StringBuilder sb = new StringBuilder();
        for (RiskLevel l : values()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(l.dbValue);
        }
        return sb.toString();
    }

    /**
     * 风险值 → 等级四段闭区间映射（唯一实现，RiskCalculator.riskLevel 委托本方法）。
     *
     * @param riskValue 风险值 1~25
     * @return low / medium / high / critical
     */
    public static RiskLevel ofValue(int riskValue) {
        if (riskValue >= 1 && riskValue <= 6) {
            return LOW;
        }
        if (riskValue >= 7 && riskValue <= 12) {
            return MEDIUM;
        }
        if (riskValue >= 13 && riskValue <= 19) {
            return HIGH;
        }
        if (riskValue >= 20 && riskValue <= 25) {
            return CRITICAL;
        }
        // 值域外（理论不可达：P/I 校验 1~5 后乘积必在 1~25）按最低档兜底，不抛异常破坏读路径
        return LOW;
    }

    /**
     * 按列存值解析为枚举。
     *
     * @return 对应枚举；未知/null 返回 null（上层判空处理）
     */
    public static RiskLevel fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (RiskLevel l : values()) {
            if (l.dbValue.equals(dbValue)) {
                return l;
            }
        }
        return null;
    }
}
