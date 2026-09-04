package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 风险 Mapper（case-20260821 T2；T12 增补看板聚合手写 SQL）。
 *
 * <p>标准 MP 形态：CRUD/筛选走 BaseMapper + LambdaWrapper（租户拦截器自动注入 t_risk——
 * 三表不进 IGNORE_TABLES，G13）。看板热力图 GROUP BY 聚合走手写 @Select（T12）。</p>
 */
@Mapper
public interface RiskMapper extends BaseMapper<Risk> {

    /**
     * 看板热力图聚合（T12，AC-F1.12）：按 (probability, impact) 分组计数未 closed 风险。
     *
     * <p><b>口径钉死</b>：仅 {@code status <> 'closed'} 计入（closed 不入看板，AC-F1.12）；
     * {@code is_deleted = 0} 手写显式过滤（@TableLogic 不作用于自定义 SQL）；
     * 等级分布由 (P,I)→level 推导（单查双用，SE D-7 两查询实现之一）。</p>
     *
     * <p><b>租户隔离（G13）</b>：手写 @Select 仍被 MyBatis-Plus 租户拦截器改写
     * （tenant_id 自动注入），不违 G13。</p>
     *
     * @return 有数据的 (P,I) 组合行（25 格中的非零格；Service 补齐 0 格）
     */
    @Select("SELECT t.probability AS probability, t.impact AS impact, COUNT(*) AS count "
            + "FROM t_risk t WHERE t.is_deleted = 0 AND t.status <> 'closed' "
            + "GROUP BY t.probability, t.impact")
    List<HeatCell> selectHeatCells();

    /** 热力图聚合行（仅 Mapper→Service 内部承载；count 别名与字段名同名映射）。 */
    @Data
    class HeatCell {
        private Integer probability;
        private Integer impact;
        /** 该 (P,I) 组合未 closed 风险数 */
        private Long count;
    }
}
