package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.Invoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 账单 Mapper（case-20260823-商用化 T7）。t_invoice 为租户级表（不进 IGNORE_TABLES）：
 * 租户上下文自动过滤（C2/C3），SYSTEM 上下文（tenant 0 = 定时任务/平台补生成，D-5）全放行
 * ——本类 SQL 均显式携带 tenant_id 条件，禁依赖 ThreadLocal（SE D-5 惯例）。
 */
@Mapper
public interface InvoiceMapper extends BaseMapper<Invoice> {

    /**
     * B5 用量聚合：本期成功派生 token 合计 Σ(input_tokens+output_tokens)，两段 UNION
     * （SE D-15——started_at 可空，NULL 行 fallback create_time 归属账期）：
     * ① started_at ∈ [期初, 次月期初)；② started_at IS NULL AND create_time ∈ 同区间。
     * 各段带 status='success'（成功终态字面值，V1 schema + DerivationEngine 磁盘事实；
     * running/failed 一律不计）。t_derivation.cost 列禁止进入任何投影（§8.4 信息隔离）。
     *
     * <p>注：租户上下文下拦截器会自动追加 tenant_id 条件（与显式条件同值叠加，无害）。</p>
     */
    @Select("SELECT COALESCE(SUM(t.tokens), 0) FROM ("
            + "SELECT (d.input_tokens + d.output_tokens) AS tokens FROM t_derivation d"
            + " WHERE d.tenant_id = #{tenantId} AND d.status = 'success'"
            + " AND d.started_at >= #{periodStart} AND d.started_at < #{periodEnd}"
            + " UNION ALL "
            + "SELECT (d2.input_tokens + d2.output_tokens) FROM t_derivation d2"
            + " WHERE d2.tenant_id = #{tenantId} AND d2.status = 'success'"
            + " AND d2.started_at IS NULL AND d2.create_time >= #{periodStart} AND d2.create_time < #{periodEnd}"
            + ") t")
    Long sumSuccessTokens(@Param("tenantId") Long tenantId,
                          @Param("periodStart") LocalDateTime periodStart,
                          @Param("periodEnd") LocalDateTime periodEnd);
}
