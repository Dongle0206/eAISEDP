package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import com.eaiselp.data.service.GovernanceLogService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计日志查询服务实现（M3-2 / Wave4）。
 *
 * <p><b>多租户隔离关键点</b>：t_governance_log 在 {@code EaiselpTenantHandler.IGNORE_TABLES} 中，
 * MyBatis-Plus 租户拦截器<b>不会</b>为本 Mapper 自动注入 tenant_id 条件。本实现所有查询
 * 都<b>显式</b> {@code eq(GovernanceLog::getTenantId, tenantId)}，确保审计日志按租户隔离，
 * 防跨租户越权读取（ES-003 §9.3 G13）。
 *
 * <p>查询性能：t_governance_log 是 append-only 高增长表，建议 DB 侧在
 * {@code (tenant_id, id)} / {@code (user_id, id)} 建复合索引支撑分页与按用户查。
 */
@Service
public class GovernanceLogServiceImpl
        extends ServiceImpl<GovernanceLogMapper, GovernanceLog>
        implements GovernanceLogService {

    /**
     * 按租户分页查询审计日志，可选按 action 过滤。
     *
     * <p>排序：按 id 倒序（id 为雪花，等价按时间倒序，避免额外 order by create_time 索引压力）。
     * tenantId 显式 eq（IGNORE_TABLES 不走拦截器，防跨租户越权）。
     */
    @Override
    public IPage<GovernanceLog> page(long tenantId, String action, int page, int size) {
        Page<GovernanceLog> p = new Page<>(page, size);
        LambdaQueryWrapper<GovernanceLog> wrapper = new LambdaQueryWrapper<GovernanceLog>()
                .eq(GovernanceLog::getTenantId, tenantId)
                .orderByDesc(GovernanceLog::getId);
        if (action != null && !action.isEmpty()) {
            wrapper.eq(GovernanceLog::getAction, action);
        }
        return this.page(p, wrapper);
    }

    /**
     * 按用户查审计日志（最近 N 条）。
     *
     * <p>排序按 id 倒序（最近优先）。limit 上限由调用方控制，这里用 last() 限制返回行数。
     */
    @Override
    public List<GovernanceLog> listByUserId(Long userId, int limit) {
        // limit 兜底：防上层传 0 或负数（MyBatis-Plus last 不做校验）
        int safeLimit = limit > 0 ? limit : 20;
        LambdaQueryWrapper<GovernanceLog> wrapper = new LambdaQueryWrapper<GovernanceLog>()
                .eq(GovernanceLog::getUserId, userId)
                .orderByDesc(GovernanceLog::getId)
                .last("LIMIT " + safeLimit);
        return this.list(wrapper);
    }
}
