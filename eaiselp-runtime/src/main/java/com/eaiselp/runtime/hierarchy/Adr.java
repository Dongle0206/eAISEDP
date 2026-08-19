package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * ADR 架构决策记录实体（t_adr，V5 F4 新表，租户级知识资产，不限层）。
 *
 * <p><b>五段式</b>：编号/上下文/决策/后果/关联原则——contextText/decisionText/consequenceText
 * 三段必填校验在 Service（空串 400，AC-F4.1）；relatedPrincipleCodes 以 JSON 数组 String 承载，
 * Jackson 序列化/解析在 Service 层（实体保持先例风格不加校验注解）。</p>
 *
 * <p><b>C3 收敛（tasks.md §0）</b>：V5 无 deprecate_reason 列——transit→deprecated 时
 * deprecateReason 仍为请求必填（空=400，PRD §4.4.2 行为权威），值写入 t_governance_log 审计
 * detail（adr_transit detail.deprecateReason）并在响应返回；详情接口从最近一次 transit 审计回显。
 * <b>本实体不加该字段</b>（V6 候选优化已提请 DBA）。</p>
 *
 * <p><b>C4 列名</b>：API 语义名 context/decision/consequences ↔ V5 列 context_text/decision_text/
 * consequence_text，实体按 V5 列名映射，Service 层换名并注释标注。</p>
 *
 * <p>状态机（proposed→accepted→deprecated/superseded，终态无出边）在 AdrStatus 枚举，应用层校验。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_adr")
public class Adr extends BaseEntity {

    /** ADR 编号（租户内唯一 uk_adr_tenant_code；缺省 ADR-NNN 服务端生成，可自定义） */
    private String adrCode;

    /** 标题（必填 ≤200） */
    private String title;

    /** 状态: proposed / accepted / deprecated / superseded（deprecated/superseded 终态不可回退） */
    private String status;

    /** 上下文（五段式必填，API 语义名 context） */
    private String contextText;

    /** 决策（五段式必填，API 语义名 decision） */
    private String decisionText;

    /** 后果（五段式必填，API 语义名 consequences） */
    private String consequenceText;

    /** 关联架构原则 code 列表（JSON 数组 String，如 ["P3","P11"]；逐 code 存在性校验在 Service） */
    private String relatedPrincipleCodes;

    /** 决策日期 */
    private LocalDate decisionDate;

    /** 作者 */
    private String author;

    /** 被取代指向的新 ADR 编号（status=superseded 时必填；目标须 accepted 且≠自身，Service 校验） */
    private String supersededBy;
}
