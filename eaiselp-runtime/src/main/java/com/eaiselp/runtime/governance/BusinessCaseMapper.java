package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.runtime.governance.dto.PortfolioVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商业案例 Mapper（case-20260821 T4；T13 增补投资组合聚合手写 SQL）。
 *
 * <p>标准 MP 形态：CRUD/筛选走 BaseMapper + LambdaWrapper（租户拦截器自动注入，
 * G13）；strategyId 筛选 = related_strategy_ids JSON 列<b>内存过滤</b>（分页后过滤，
 * V6 principleCode 口径，不进 SQL——方言绑定+索引无效，ADR §4.2）。
 * 投资组合两聚合查询走手写 @Select（T13）。</p>
 */
@Mapper
public interface BusinessCaseMapper extends BaseMapper<BusinessCase> {

    /**
     * 投资口径汇总（T13，AC-F2.11）：Σ落库列，仅 status∈{approved,executing,done}
     * （draft 未决策、rejected 已否决不计钱，裁决 Q8）；空集 COALESCE 0。
     *
     * <p><b>口径钉死</b>（D-2）：直接对落库计算列/输入列 Σ，不逐行重算；
     * {@code is_deleted = 0} 手写显式过滤（@TableLogic 不作用于自定义 SQL）；
     * 租户拦截器自动注入 tenant_id（G13）。</p>
     */
    @Select("SELECT COALESCE(SUM(t.onetime_cost), 0) AS total_onetime_cost, "
            + "COALESCE(SUM(t.annual_op_cost), 0) AS total_annual_op_cost, "
            + "COALESCE(SUM(t.net_benefit), 0) AS total_annual_net_benefit "
            + "FROM t_business_case t "
            + "WHERE t.is_deleted = 0 AND t.status IN ('approved', 'executing', 'done')")
    PortfolioVo.Summary selectInvestmentSummary();

    /**
     * 状态分布（T13，AC-F2.12）：全量五态计数 GROUP BY（与汇总投资口径<b>有意不同</b>——
     * 分布看流程漏斗、汇总看钱）；is_deleted=0 显式过滤；租户拦截器自动注入（G13）。
     */
    @Select("SELECT t.status AS status, COUNT(*) AS cnt "
            + "FROM t_business_case t WHERE t.is_deleted = 0 GROUP BY t.status")
    List<PortfolioVo.StatusCount> selectStatusDistribution();
}
