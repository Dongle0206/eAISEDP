package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目群 Mapper（空接口，标准 MP 形态；查询走 Service 层 wrapper，SE §3.2）。 */
@Mapper
public interface ProgramMapper extends BaseMapper<Program> {
}
