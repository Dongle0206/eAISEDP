package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模板库 Mapper（V6 F1.2，case-20260820 T3）。
 *
 * <p>标准 MP 形态（空接口）：CRUD/筛选走 BaseMapper + LambdaWrapper（租户拦截器自动注入）。</p>
 */
@Mapper
public interface TemplateMapper extends BaseMapper<Template> {
}
