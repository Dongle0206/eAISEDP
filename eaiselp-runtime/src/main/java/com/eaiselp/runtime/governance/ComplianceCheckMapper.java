package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 合规检查 Mapper（case-20260821 T3，V7 F1.2）。
 *
 * <p>纯 BaseMapper 形态：CRUD/筛选走 LambdaWrapper（租户拦截器自动注入
 * t_compliance_check，G13）；无聚合/反查诉求（V7 仅 uk 单索引，t_data_asset 同先例）。</p>
 */
@Mapper
public interface ComplianceCheckMapper extends BaseMapper<ComplianceCheck> {
}
