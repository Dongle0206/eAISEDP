package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 战略目标 Mapper（空接口，标准 MP 形态；查询走 Service 层 wrapper，不写自定义 SQL，SE §3.2）。 */
@Mapper
public interface StrategyMapper extends BaseMapper<Strategy> {
}
