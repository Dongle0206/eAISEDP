package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 技术雷达 Mapper（V5 F5，标准 MP 形态空接口）。
 *
 * <p>CRUD/象限×环筛选走 BaseMapper + LambdaWrapper（命中 idx_radar_tenant_quadrant_ring）；
 * 环移动审计走 AuditService（detail 记 fromRing/toRing），本 Mapper 无自定义 SQL。</p>
 */
@Mapper
public interface TechRadarItemMapper extends BaseMapper<TechRadarItem> {
}
