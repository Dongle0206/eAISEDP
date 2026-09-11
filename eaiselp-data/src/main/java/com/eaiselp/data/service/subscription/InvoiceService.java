package com.eaiselp.data.service.subscription;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.data.service.subscription.dto.GenerateSummaryVo;
import com.eaiselp.data.service.subscription.dto.InvoiceDetailVo;
import com.eaiselp.data.service.subscription.dto.InvoiceVo;

/**
 * 月度账单服务（case-20260823-商用化 T7/T9/T10；出账生成核心 + 状态机 + 查询 + draft 隔离）。
 *
 * <p><b>出账执行上下文 = SYSTEM（TenantContext=0，D-5）</b>：定时线程与手动补生成入口
 * （platform_admin tenant 0）同源——拦截器全放行，实体显式携带 tenant_id；
 * <b>本服务全程显式传 tenantId，禁止依赖 ThreadLocal</b>（SE D-5 惯例锚定）。
 * 定时任务（T8）与 I3 手动补生成走<b>同一入口</b> {@link #generatePeriod}——幂等口径单点。</p>
 *
 * <p><b>信息隔离（§8.4 红线）</b>：t_derivation.cost 禁止进入任何列/detail JSON/VO 字段。</p>
 */
public interface InvoiceService {

    /**
     * I3 手动补生成 / 定时任务同一入口（AC-F2.7 幂等）。
     *
     * <p>判定序（SE §4.2，先资格后折算）：① edition=trial → B2 跳过（无行，非 0 元账单）
     * ② plan_code 空 → D-13 跳过 + WARN ③ 期初（当月 1 日 00:00）为 trial（判定源 =
     * t_governance_log action=tenant_edition_change 账期内首个 trial→非trial 变更）→ B3
     * 赠送跳过；审计缺失 fallback：出账时 trial → 不出账，出账时付费 + create_time&lt;期初 →
     * 正常出账（保守少收不误收，§0.3-2）④ plan 按出账时刻 plan_code 查（B1 期末档；查不到/
     * 逻辑删 → 跳过 + WARN）⑤ 用量聚合（B5 两段 UNION，status='success'）⑥ Q6 折算判定
     * （期初租户不存在 create_time≥当月 1 日且出账付费 → 折算；B2/B3 优先于折算）
     * ⑦ Calculator 计算 + 快照/明细组装 + uk(tenant,period) 幂等落库（draft 覆盖 /
     * issued、paid 不覆盖 WARN / DuplicateKey 兜底转已存在分支）。</p>
     *
     * <p>单租户失败（如金额超 DECIMAL 域 400）进 failed 清单不中断全量（D-7）。</p>
     *
     * @param period   账期键 yyyy-MM（格式非法 40000）
     * @param tenantId 指定租户补生成；null = 全量该账期所有租户（t_tenant active）
     * @param operator 生成来源（定时固定 "billing-scheduler"；手动 = 操作者账号）——审计
     *                 action 按此区分 bill_generate_scheduled / bill_generate
     */
    GenerateSummaryVo generatePeriod(String period, Long tenantId, String operator);

    /**
     * I4 账单流转（draft→issued→paid 单向顺次，人工标记 AC-F2.8）：跳变（draft→paid）/
     * 回退（issued→draft）/paid 后再流转一律 400；落 issuedTime/paidTime；审计 bill_transit。
     *
     * @param target issued / paid
     */
    InvoiceVo transit(Long id, String target);

    /**
     * I1 平台账单分页（跨租户 D-5 通道；platform 侧可见 draft）：period/tenantId/status
     * 筛选，默认 period DESC, id DESC。
     */
    IPage<InvoiceVo> pagePlatform(String period, Long tenantId, String status, long page, long size);

    /** I2 平台账单详情（全字段 + detail JSON 解析回显；40400 不存在）。 */
    InvoiceDetailVo detail(Long id);

    /**
     * C2 租户历史账单（AC-F2.9 第二道语义过滤）：强制 status IN (issued, paid)——
     * draft 对租户不可见；period DESC。
     */
    IPage<InvoiceVo> pageForTenant(Long tenantId, long page, long size);

    /**
     * C3 租户账单详情 + 超量明细：draft 或他租户账单 → 40400（AC-F2.9/F2.10）。
     */
    InvoiceDetailVo detailForTenant(Long tenantId, Long id);
}
