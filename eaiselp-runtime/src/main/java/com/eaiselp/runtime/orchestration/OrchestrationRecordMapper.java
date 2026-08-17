package com.eaiselp.runtime.orchestration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 编排任务持久化 Mapper。 */
@Mapper
public interface OrchestrationRecordMapper extends BaseMapper<OrchestrationRecord> {
}
