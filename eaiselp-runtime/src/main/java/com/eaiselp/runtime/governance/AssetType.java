package com.eaiselp.runtime.governance;

/**
 * 数据资产类型枚举（V6 F2.1 领域数据字典，case-20260820 T4；PRD §0 P6 裁决——随平台演进、
 * 不做租户配置化）。
 *
 * <p>非法值校验形态（AC-F2.2）：{@link #fromDbValue} 返回 null → Service 抛 400 并指名
 * 字段与合法值集合（"assetType 非法: view（应为 database/table/api/report/file）"）。</p>
 */
public enum AssetType {

    DATABASE("database"),
    TABLE("table"),
    API("api"),
    REPORT("report"),
    FILE("file");

    /** t_data_asset.asset_type 列存的小写字符串值。 */
    private final String dbValue;

    AssetType(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 返回与列存一致的小写字符串。 */
    public String dbValue() {
        return dbValue;
    }

    /** 合法值集合字符串（错误 message 拼接用，"database/table/api/report/file"）。 */
    public static String legalValues() {
        StringBuilder sb = new StringBuilder();
        for (AssetType t : values()) {
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
    public static AssetType fromDbValue(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (AssetType t : values()) {
            if (t.dbValue.equals(dbValue)) {
                return t;
            }
        }
        return null;
    }
}
