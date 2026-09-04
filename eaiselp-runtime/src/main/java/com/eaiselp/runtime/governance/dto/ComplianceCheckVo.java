package com.eaiselp.runtime.governance.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合规检查视图 VO（case-20260821 T3，字段名=api-contracts §2 契约）。
 *
 * <p><b>overdue 服务端统一判定</b>（D-10）：{@code recheckDate < 今天}——
 * <b>na 不豁免</b>（统一口径，PRD §4.2/AC-F1.11）；recheckDate 为空恒 false。</p>
 */
@Data
public class ComplianceCheckVo {

    private Long id;

    /** 检查项名 */
    private String checkName;

    /** djba2.0/iso27001/gdpr/custom（djba2.0 展示名=等保 2.0，前端集中映射） */
    private String framework;

    /** 自定义框架名（仅 framework=custom 非空） */
    private String frameworkName;

    /** 条款引用（如 "ISO27001 A.9.4.1"） */
    private String clauseRef;

    /** 检查描述 */
    private String description;

    /** pass/fail/partial/na（覆盖式单值当前态） */
    private String result;

    /** 证据说明 */
    private String evidenceNote;

    /** 检查日期（缺省登记当天） */
    private LocalDate checkDate;

    /** 复检日期（可空） */
    private LocalDate recheckDate;

    /** 复检逾期标识（服务端判定：recheckDate&lt;今天，na 不豁免；空日期恒 false，AC-F1.11） */
    private Boolean overdue;

    /** 检查责任人 */
    private String owner;

    private String createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
