package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * ADR 架构决策记录 Mapper（V5 F4）。
 *
 * <p>标准 MP 形态（空接口）：CRUD/筛选走 BaseMapper + LambdaWrapper（租户拦截器自动注入）；
 * deprecateReason 审计回显走 GovernanceLogMapper（C3），本 Mapper 无自定义 SQL。
 * 按原则 code 筛选（related_principle_codes JSON 列）在 Service 内存过滤——不写
 * JSON_CONTAINS 的 wrapper（方言绑定+索引无效，SE §4.2 内存过滤口径）。</p>
 */
@Mapper
public interface AdrMapper extends BaseMapper<Adr> {
}
