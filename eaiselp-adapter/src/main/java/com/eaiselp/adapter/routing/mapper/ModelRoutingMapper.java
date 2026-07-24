package com.eaiselp.adapter.routing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.adapter.routing.entity.ModelRouting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型路由 Mapper（P8 解耦层）。
 *
 * <p>放 adapter 模块内部（非 data），遵循 ES-003 §9.2 / P3：避免 adapter→data 反向依赖。
 * runtime 装配时通过 {@code EaiselpRuntimeApplication} 的 @MapperScan 扫到本包。
 *
 * <p>查询全部走 MyBatis-Plus LambdaQueryWrapper；t_model_routing 为系统级表（已加 IGNORE_TABLES），
 * 多租户拦截器不会自动注入 tenant_id 条件。
 */
@Mapper
public interface ModelRoutingMapper extends BaseMapper<ModelRouting> {
}
