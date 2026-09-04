package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 合规检查实体（t_compliance_check，V7 F1.2 新表，租户级知识资产，不限层，手动登记制，
 * case-20260821 T3）。
 *
 * <p><b>结果覆盖式单值当前态</b>（不建历史表）：旧值唯一留痕 = t_governance_log 审计
 * detail（oldResult→newResult + 证据，AC-F1.10；同 V6 质量规则 last_result 先例）。</p>
 *
 * <p><b>custom↔frameworkName 双向联动</b>（AC-F1.9，Service 校验）：framework=custom 时
 * frameworkName 必填；非 custom 时必须为空（防脏数据）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_check")
public class ComplianceCheck extends BaseEntity {

    /** 检查项名（必填 ≤200；uk(tenant, check_name)——同名再建被拒 AC-F1.8） */
    private String checkName;

    /** 框架: djba2.0/iso27001/gdpr/custom（应用层枚举校验非法 400） */
    private String framework;

    /** 自定义框架名（custom 时必填、非 custom 时必须为空——应用层校验 400，AC-F1.9） */
    private String frameworkName;

    /** 条款引用（如 "ISO27001 A.9.4.1"，自由填写 ≤128） */
    private String clauseRef;

    /** 检查描述（自由文本） */
    private String description;

    /** 检查结果: pass/fail/partial/na（覆盖式单值当前态；应用层枚举校验） */
    private String result;

    /** 证据说明（手动登记制 ≤1000） */
    private String evidenceNote;

    /** 检查日期（V7 列可空——登记时缺省由应用层取当天） */
    private LocalDate checkDate;

    /** 复检日期（可空；逾期=recheck_date&lt;当天展示层红标，na 不豁免——裁决 Q9） */
    private LocalDate recheckDate;

    /** 检查责任人（自由 VARCHAR） */
    private String owner;
}
