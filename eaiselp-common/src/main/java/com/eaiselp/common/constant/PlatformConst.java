package com.eaiselp.common.constant;

public interface PlatformConst {
    String HEADER_SYSTEM_VERSION = "X-System-Version";
    String CASE_ID_PREFIX = "case-";

    interface Layer { String L1 = "L1"; String L2 = "L2"; String L3 = "L3"; }
    interface Tier { String FAST = "fast"; String STANDARD = "standard"; }

    /** 不可逆操作清单（reliability-governance 防线1） */
    String[] IRREVERSIBLE_OPS = {
        "deploy_production", "ddl_change", "data_delete",
        "config_production", "main_branch_push", "service_offline", "model_deploy_prod"
    };
}
