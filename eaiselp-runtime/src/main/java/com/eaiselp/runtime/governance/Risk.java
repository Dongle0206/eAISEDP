package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 风险登记册实体（t_risk，V7 F1.1 新表，租户级知识资产，不限层，case-20260821 T2）。
 *
 * <p><b>计算列落库</b>（裁决 Q1）：riskValue/riskLevel 由 {@link RiskCalculator} 在
 * create/update 写库前重算覆盖——入参 DTO 无此二字段，客户端伪造值连绑定入口都没有
 * （AC-F1.5 防伪造链）。</p>
 *
 * <p><b>probability/impact 以 BigDecimal 承载</b>（SE 方案 D-4 磁盘事实纠偏：
 * GlobalExceptionHandler 无 HttpMessageNotReadableException 专项处理，Integer 承载时
 * 提交 1.5 会 Jackson 反序列化失败落入通用 50000——BigDecimal 让 1.5 正常到达 Service，
 * 由整数性校验 400 指名拒绝）。DB 列为 TINYINT/INT，Service 校验通过后归一为整数值
 * 写库（JDBC setBigDecimal 对整数值与 INT 列透明兼容）。</p>
 *
 * <p><b>关联承载</b>：relatedObjects 以 JSON 数组 String 承载（元素 {type, id}，
 * type∈program/project/case，存 id——裁决 Q4），Jackson 序列化/解析与存在性校验在
 * Service；被关联对象逻辑删后展示层"已删除"占位（AC-F1.6）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_risk")
public class Risk extends BaseEntity {

    /** 风险名（必填 ≤200；uk(tenant, risk_name)——同名再建被拒 AC-F1.1） */
    private String riskName;

    /** 类别: strategy/compliance/operations/technical/security（应用层枚举校验非法 400） */
    private String category;

    /** 概率 1~5（BigDecimal 承载整数性+区间校验在 Service/Calculator，D-4；DB TINYINT） */
    private BigDecimal probability;

    /** 影响 1~5（同上） */
    private BigDecimal impact;

    /** 风险值=probability×impact（1~25），计算列——Service 重算覆盖落库（裁决 Q1） */
    private Integer riskValue;

    /** 等级: low/medium/high/critical，计算列——四段闭区间映射（PRD §4.1.2 唯一口径） */
    private String riskLevel;

    /** 风险描述（背景/影响范围——V7 r2 补列贯通，评审 D1 修复） */
    private String description;

    /** 缓解措施（自由文本） */
    private String mitigation;

    /** 应急预案（自由文本） */
    private String contingencyPlan;

    /** 风险责任人（自由 VARCHAR，本期不联动 t_user——同 V6 t_data_asset.owner 先例；创建必填 AC-F1.1） */
    private String owner;

    /** 状态: open/mitigating/closed（状态机在 {@link RiskStatus}，应用层校验） */
    private String status;

    /** 处置说明（closed 必填；非 closed 状态须为 NULL——V7 列注释契约） */
    private String resolutionNote;

    /** 关联对象 JSON 数组 String（[{"type":"program","id":123}]，可空多选；校验在 Service） */
    private String relatedObjects;

    /** 复评日期（可空；逾期=review_date&lt;当天且 status≠closed，服务端 VO 判定 D-10） */
    private LocalDate reviewDate;
}
