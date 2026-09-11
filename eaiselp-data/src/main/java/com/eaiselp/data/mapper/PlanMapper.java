package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.Plan;
import org.apache.ibatis.annotations.Mapper;

/**
 * 套餐 Mapper（case-20260823-商用化 T3）。t_plan 在 EaiselpTenantHandler.IGNORE_TABLES（T2），
 * 本 Mapper SQL 不被租户拦截器改写——平台级商品资产按 code/id 直查（idx_plan_code 主查询路径）。
 */
@Mapper
public interface PlanMapper extends BaseMapper<Plan> {
}
