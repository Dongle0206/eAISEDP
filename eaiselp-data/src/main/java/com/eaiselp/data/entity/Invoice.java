package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度账单实体（case-20260823-商用化 V8，F2.1；t_invoice 租户级业务表，计费口径 B1~B6+Q6）。
 *
 * <p><b>字段名按 V8 落盘 DDL 定稿</b>（tasks.md 差异定稿表 D-2/D-3，冲突以 V8 为准，非 SE 方案
 * §3.2 的 plan_id/plan_snapshot/usage_detail/monthly_price/base_fee/overage_fee/amount_due）：
 * 套餐引用键 = {@code planCode}（字符串快照，D-3）；金额体系 = planPrice（原价快照）+
 * overageAmount + prorationAmount + totalAmount；明细细目统一入 detail JSON 单列（结构见
 * api-contracts.md 附 A）。</p>
 *
 * <p><b>恒等式（V8 形态，QA 落库断言基线）</b>：
 * {@code totalAmount = (prorationAmount > 0 ? prorationAmount : planPrice) + overageAmount}。</p>
 *
 * <p><b>幂等</b>：uk_invoice_tenant_period(tenant_id, period)（D-7/AC-F2.7）——draft 重算覆盖
 * UPDATE / issued、paid 不覆盖仅 WARN / DuplicateKeyException 兜底转已存在分支。</p>
 *
 * <p><b>信息隔离红线（§8.4）</b>：t_derivation.cost 禁止进入任何列/detail JSON（QA 序列化
 * grep "cost" 断言）。draft 对租户不可见（C2/C3 应用层第二道过滤，AC-F2.9）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invoice")
public class Invoice extends BaseEntity {

    /** 账期键 yyyy-MM（[当月1日00:00, 次月1日00:00) Asia/Shanghai；与 t_quota.period 同构） */
    private String period;

    /** 出账时生效套餐编码快照（B1 月末档；月中升降级本期按期末档计价） */
    private String planCode;

    /** 套餐月价快照（原价，不随折算变化，DECIMAL(14,2)；上限镜像校验 D-4） */
    private BigDecimal planPrice;

    /** 本期成功派生 token 合计 Σ(input+output)（B5 口径；失败/运行中不计） */
    private Long usageTokens;

    /** 超额 token = max(0, usageTokens − tokenLimit)（B5） */
    private Long overageTokens;

    /** 超量费 = ceil(overageTokens/1000) × 超量单价快照，HALF_UP 2 位落库（D-4 恒等式右元） */
    private BigDecimal overageAmount;

    /** 首月折算实收月费（Q6：仅首月折算账期非 0，其余 0.00=整月计费；上限镜像校验） */
    private BigDecimal prorationAmount;

    /** 应缴 = (prorationAmount>0 ? prorationAmount : planPrice) + overageAmount（服务端 Calculator 生成） */
    private BigDecimal totalAmount;

    /**
     * 明细 JSON 单列（附 A 结构）：套餐五字段快照 + tokenUsed/tokenLimit/tokenOverageTokens/
     * ktokenBilled/derivationCount/caseCount/storageUsedMb + firstMonthProration 四元组
     * （仅首月折算账期出现，D-11）。
     */
    private String detail;

    /** draft/issued/paid（单向状态机，跳变/回退 400，AC-F2.8；draft 对租户不可见 AC-F2.9） */
    private String status;

    /** 标记 issued 时间（流转审计落点） */
    private LocalDateTime issuedTime;

    /** 标记 paid 时间 */
    private LocalDateTime paidTime;
}
