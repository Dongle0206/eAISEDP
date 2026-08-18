package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目-原则关联 Mapper（空接口，标准 MP 形态；绑定全量替换/反向清理走 Service 层标准写，SE §3.2）。 */
@Mapper
public interface ProjectPrincipleMapper extends BaseMapper<ProjectPrinciple> {
}
