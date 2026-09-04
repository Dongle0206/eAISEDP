package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据质量规则实体（t_data_quality_rule，V6 F2.2 新表，租户级知识资产，不限层，
 * case-20260820 T5）。
 *
 * <p>uk(tenant_id, rule_name)：规则名租户内唯一（同 t_quality_gate_rule uk_gate_tenant_name
 * 先例）。assetId 单选关联（裁决 Q5）——存在且未逻辑删校验在 Service（无物理外键，V1 逻辑
 * 外键风格）；资产逻辑删时本规则由 DataAssetServiceImpl 联动逻辑删（AC-F2.7）。</p>
 *
 * <p><b>最近检查结果=单值当前态</b>（AC-F2.6）：登记覆盖式更新 last_* 四列，历史唯一留痕
 * = t_governance_log 审计 detail（不建历史表）；pass/fail 由登记人判定，平台不做阈值
 * 自动判定（PRD §7-5）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_data_quality_rule")
public class DataQualityRule extends BaseEntity {

    /** 规则名（租户内唯一，如"订单表完整性"） */
    private String ruleName;

    /** 关联资产 ID（单选 t_data_asset.id，裁决 Q5；存在/未删校验在 Service） */
    private Long assetId;

    /** 检查类型: completeness/accuracy/consistency/timeliness（{@link CheckType} 应用层校验） */
    private String checkType;

    /** 阈值=百分比达标线 0~100（如 99.50 表示 ≥99.5% 达标；区间应用层校验，边界 0/100 合法） */
    private BigDecimal threshold;

    /** 最近检查结果: pass/fail（登记人判定；NULL=从未登记） */
    private String lastResult;

    /** 最近检查实测值（登记时可选） */
    private BigDecimal lastActualValue;

    /** 最近检查时间（登记时可选，缺省当前时刻） */
    private LocalDateTime lastCheckTime;

    /** 最近检查备注（如"字段缺失"） */
    private String lastCheckRemark;
}
