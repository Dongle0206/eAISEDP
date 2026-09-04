package com.eaiselp.runtime.governance.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 风险视图 VO（case-20260821 T2，字段名=api-contracts §1 契约）。
 *
 * <p><b>overdue 服务端统一判定</b>（D-10 防前端时钟偏差）：
 * {@code reviewDate < 今天 && status != closed}——DATE 精度次日红标当日不标（裁决 Q9）；
 * closed 后恒 false（AC-F1.7）。前端红标直接消费本字段。</p>
 *
 * <p>注：V7 r2 已补 description 列（DBA v1 漏建、评审 D1 修复贯通五处），
 * 风险名/描述/缓解措施三字段分立，对齐 PRD §4.1.1。</p>
 */
@Data
public class RiskVo {

    private Long id;

    /** 风险名 */
    private String riskName;

    /** strategy/compliance/operations/technical/security */
    private String category;

    /** 概率 1~5 */
    private Integer probability;

    /** 影响 1~5 */
    private Integer impact;

    /** 风险值 1~25（计算列回显=P×I，AC-F1.5 重算覆盖） */
    private Integer riskValue;

    /** low/medium/high/critical（计算列回显） */
    private String riskLevel;

    /** 风险描述（背景/影响范围） */
    private String description;
    private String mitigation;

    /** 应急预案 */
    private String contingencyPlan;

    /** 风险责任人 */
    private String owner;

    /** open/mitigating/closed */
    private String status;

    /** 处置说明（仅 closed 态非空） */
    private String resolutionNote;

    /** 复评日期（可空） */
    private LocalDate reviewDate;

    /** 逾期标识（服务端判定：reviewDate&lt;今天且未 closed；closed 恒 false，AC-F1.7） */
    private Boolean overdue;

    /** 关联对象列表（仅详情接口填充解析态；列表为 null） */
    private List<RelatedObjectVo> relatedObjects;

    private String createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
