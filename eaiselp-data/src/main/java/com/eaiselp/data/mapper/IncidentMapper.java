package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.Incident;
import org.apache.ibatis.annotations.Mapper;

/** 事故台账 Mapper（case-20260823-商用化 T11）。租户级表：租户上下文下自动过滤，平台通道放行。 */
@Mapper
public interface IncidentMapper extends BaseMapper<Incident> {
}
