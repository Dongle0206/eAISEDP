package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.GovernanceLog;

import java.util.List;

/**
 * 审计日志查询服务接口（M3-2 / Wave4 审计日志查询 API）。
 *
 * <p><b>注意：本接口仅用于审计日志「查询」</b>（分页 / 按用户查）。审计日志的「写入」
 * 由 {@code AuditService}（异步，从 LoginUser 取上下文）负责，二者职责分离：
 * 写入侧关心「不丢、不阻塞业务」，查询侧关心「按租户隔离 + 多维过滤」。
 *
 * <p><b>多租户隔离（关键）</b>：{@code t_governance_log} 已加入
 * {@code EaiselpTenantHandler.IGNORE_TABLES}（不走 MyBatis-Plus 租户拦截器自动注入），
 * 故本接口所有方法<b>必须由调用方显式传入 tenantId</b>作为查询条件——审计日志是跨租户敏感数据，
 * 拦截器不会自动加 tenant_id 过滤，漏传会导致跨租户越权读取（ES-003 §9.3 G13）。
 * Controller 层从 {@code LoginUser.getTenantId()} 取 tenantId 传入，杜绝客户端伪造。
 *
 * <p>继承 {@link IService} 以复用 MyBatis-Plus 通用能力（如需），实际查询走下方声明的方法。
 */
public interface GovernanceLogService extends IService<GovernanceLog> {

    /**
     * 按租户分页查询审计日志，可选按 action 过滤，按 id 倒序（最新优先）。
     *
     * <p><b>tenantId 必传</b>：t_governance_log 不走租户拦截器，调用方必须显式传入当前登录租户 id，
     * 防跨租户越权（ES-003 §9.3 G13）。action 为 null/空时不过滤（查该租户全部动作）。
     *
     * @param tenantId 租户 ID（必传，0=系统级，从 LoginUser 取）
     * @param action   操作动作过滤（如 case_create / login_success），null/空时不过滤
     * @param page     页码（1 起）
     * @param size     每页条数
     * @return 分页结果（按 id 倒序）
     */
    IPage<GovernanceLog> page(long tenantId, String action, int page, int size);

    /**
     * 按用户查审计日志（按 id 倒序，取最近 N 条）。
     *
     * <p>用于「某用户的操作历史」场景（如用户详情页操作时间线）。
     * <b>tenantId 不在此方法参数</b>：userId 在雪花/租户内基本唯一，且按用户查通常用于管理员审计
     * 单个用户行为；若需租户隔离，调用方应先校验该 userId 属于当前租户（上层 Controller 把关）。
     *
     * @param userId 用户 ID
     * @param limit  返回条数上限（建议 ≤ 100，防大结果集拖慢）
     * @return 审计日志列表（按 id 倒序，最近优先）
     */
    List<GovernanceLog> listByUserId(Long userId, int limit);
}
