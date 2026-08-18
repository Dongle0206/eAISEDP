package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 质量门禁规则 Mapper（空接口，标准 MP 形态；快照加载走 Service 层标准查询命中 idx_gate_tenant_enabled，SE §3.2）。 */
@Mapper
public interface QualityGateRuleMapper extends BaseMapper<QualityGateRule> {
}
