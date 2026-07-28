package com.eaiselp.common.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

/** 多租户 SQL 处理器：自动给业务表 SQL 加 tenant_id 条件。 */
@Component
public class EaiselpTenantHandler implements TenantLineHandler {

    private static final String[] IGNORE_TABLES = {
        "t_tenant", "t_user", "t_system_config", "t_system_version", "t_quota_template",
        // M2 Phase 1 新增：权限系统表为系统级共享，免 tenant 自动过滤
        "t_permission", "t_role", "t_role_permission", "t_user_role", "t_service_account",
        // M2 SP-6 新增：模型路由表为系统级全局配置（模型档位是平台级配置），免 tenant 自动过滤（ES-003 §2.5）
        "t_model_routing",
        // M3-2 新增：审计日志按 tenant_id 显式记录（AuditService 从 LoginUser 取 tenant_id 写入），
        // 不走拦截器自动注入（append-only 表，明细由 AuditService 显式控制更清晰）
        "t_governance_log"
    };

    @Override
    public Expression getTenantId() {
        return new LongValue(TenantContext.get());
    }

    @Override
    public String getTenantIdColumn() { return "tenant_id"; }

    @Override
    public boolean ignoreTable(String tableName) {
        if (TenantContext.isSystem()) return true;
        for (String t : IGNORE_TABLES) {
            if (t.equalsIgnoreCase(tableName)) return true;
        }
        return false;
    }
}
