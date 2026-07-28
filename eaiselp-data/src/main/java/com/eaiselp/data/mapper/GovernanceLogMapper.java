package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.GovernanceLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper（M3-2）。
 *
 * <p>t_governance_log 已加入 {@code EaiselpTenantHandler.IGNORE_TABLES}，
 * 本 Mapper 的 SQL 不被租户拦截器自动注入 tenant_id 条件——
 * 需要按租户隔离查询时由调用方显式 {@code eq(GovernanceLog::getTenantId, ...)}。
 */
@Mapper
public interface GovernanceLogMapper extends BaseMapper<GovernanceLog> {
}
