package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据质量规则 Mapper（V6 F2.2，case-20260820 T5）。
 *
 * <p>标准 MP 形态（空接口）：CRUD/筛选/资产聚合（idx_dqr_tenant_asset 命中）走 BaseMapper
 * + LambdaWrapper（租户拦截器自动注入）。本 Mapper 亦被 DataAssetServiceImpl 注入用于
 * 资产删除联动定位与详情聚合（Mapper 级注入避免 Service 循环依赖）。</p>
 */
@Mapper
public interface DataQualityRuleMapper extends BaseMapper<DataQualityRule> {
}
