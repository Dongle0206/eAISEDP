package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目 Mapper（空接口，标准 MP 形态；进度汇总重算固定为"两条标准 count + 一条 LambdaUpdateWrapper set"，不写子查询 UPDATE，SE §11 R4）。 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
