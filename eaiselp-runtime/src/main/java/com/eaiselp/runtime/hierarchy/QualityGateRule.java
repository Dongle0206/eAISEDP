package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 质量门禁规则实体（L2 治理→L1 执行，t_quality_gate_rule，PRJ-002 T04）。
 *
 * <p>字段与 V4 r2 列一一对应；name 租户内唯一（uk_gate_tenant_name）。规则数据驱动
 * 替换 OrchestrationService 硬编码 GATE_ROLES / team-ops 检查点（P6 零硬编码，AC-F6.3）。
 * 编排启动时一次性加载快照、运行期不缓存、启停仅对新编排生效（SE 决策 D-3 快照语义）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_quality_gate_rule")
public class QualityGateRule extends BaseEntity {

    /** 规则名称（如"代码必须过语法验证"；租户内唯一） */
    private String name;

    /** 门禁类型: auto_check / llm_review / human_approval */
    private String gateType;

    /** llm_review 时的门禁角色（team-reviewer 等）；其余类型为 null */
    private String gateRole;

    /** auto_check 时的检查项（code_validation / api_contract 等）；其余类型为 null */
    private String checkKey;

    /** 生效范围: all / code / design / doc */
    private String appliesTo;

    /** 挂载阶段: post_dev / post_test / pre_deploy */
    private String stage;

    /** 门禁 FAIL 打回重做上限（null = 走 yml eaiselp.orchestration.gate-max-retries 兜底，PRD F6.4） */
    private Integer maxRetries;

    /** 失败动作: block(阻断) / warn(告警放行) */
    private String failAction;

    /** 启停: 1=启用(默认) 0=停用（启停仅对新编排生效，在跑编排走快照，D-3） */
    private Integer enabled;

    /** 同阶段多规则执行顺序（小者先） */
    private Integer priority;
}
