package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据资产 Mapper（V6 F2.1，case-20260820 T4）。
 *
 * <p>标准 MP 形态（空接口）：CRUD/筛选走 BaseMapper + LambdaWrapper（租户拦截器自动注入）；
 * tags 为 JSON 列，标签筛选在 Service 内存过滤（同 ADR relatedPrincipleCodes §4.2 口径，
 * 防 JSON_CONTAINS 方言绑定——H2 兼容）。</p>
 */
@Mapper
public interface DataAssetMapper extends BaseMapper<DataAsset> {
}
