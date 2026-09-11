package com.eaiselp.data.service.subscription;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 商用化 H2 测试基建工具（case-20260823）：测试类内幂等补建 schema-h2 缺失的表/列。
 *
 * <p><b>为什么不改 schema-h2.sql</b>：DBA 定稿资产（边界红线禁碰）。t_governance_log/t_quota
 * 在 schema-h2 中从未建过（V8 前无测试诉求）；t_tenant 为最小列集（Tenant 实体全列 SELECT
 * 需补业务列）。全部 CREATE/ADD IF NOT EXISTS 幂等，生产结构以 V1/V8 为准。</p>
 */
public final class H2BillingTables {

    private H2BillingTables() {
    }

    /** 幂等补建/补列（多个测试类共享同一 H2 上下文时重复执行无害）。 */
    public static void ensure(JdbcTemplate jt) {
        jt.execute("CREATE TABLE IF NOT EXISTS t_governance_log ("
                + "id BIGINT NOT NULL PRIMARY KEY, tenant_id BIGINT DEFAULT 0, user_id BIGINT, "
                + "username VARCHAR(64), action VARCHAR(64), resource_type VARCHAR(32), "
                + "resource_id VARCHAR(128), detail CLOB, ip_address VARCHAR(64), "
                + "result VARCHAR(16), error_msg CLOB, create_time TIMESTAMP)");
        jt.execute("CREATE TABLE IF NOT EXISTS t_quota ("
                + "id BIGINT NOT NULL PRIMARY KEY, tenant_id BIGINT DEFAULT 0, period CHAR(7), "
                + "token_limit BIGINT, token_used BIGINT, case_limit INT, case_used INT, "
                + "derivation_limit INT, derivation_used INT, storage_limit_mb BIGINT, "
                + "storage_used_mb BIGINT, monthly_cost DECIMAL(14,2), alert_threshold INT, "
                + "create_time TIMESTAMP, update_time TIMESTAMP, create_by VARCHAR(64), "
                + "update_by VARCHAR(64), is_deleted INT DEFAULT 0)");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_quota_tenant_period ON t_quota (tenant_id, period)");
        for (String col : new String[]{
                "deploy_mode VARCHAR(32)", "contact_name VARCHAR(64)", "contact_email VARCHAR(128)",
                "contact_phone VARCHAR(64)", "system_repo_url VARCHAR(512)", "system_branch VARCHAR(64)",
                "llm_provider VARCHAR(32)", "llm_api_key VARCHAR(512)",
                "strategy_enabled INT DEFAULT 1", "program_project_enabled INT DEFAULT 1"}) {
            jt.execute("ALTER TABLE t_tenant ADD COLUMN IF NOT EXISTS " + col);
        }
    }
}
