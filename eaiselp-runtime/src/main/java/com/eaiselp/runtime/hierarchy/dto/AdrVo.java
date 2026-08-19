package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ADR 视图 VO（case-20260818 T9，字段名=api-contracts §4 契约）。
 *
 * <p>API 语义名 context/decision/consequences ↔ V5 列 context_text/decision_text/consequence_text
 * （C4，Service 层换名）；relatedPrincipleCodes 为 JSON 数组解析后的 List&lt;String&gt;。</p>
 *
 * <p><b>C3 承载</b>：deprecateReason 不落列——详情/流转响应从最近一次 adr_transit 审计 detail
 * 回显（无则为 null）；principleSyncHints 仅在"流转离开 accepted 且关联原则非空"时非空
 * （提示而非自动，系统不写 t_architecture_principle，AC-F4.3）。</p>
 */
@Data
public class AdrVo {

    private Long id;

    /** ADR 编号（ADR-001） */
    private String adrCode;

    private String title;

    /** proposed / accepted / deprecated / superseded */
    private String status;

    /** 上下文（五段式） */
    private String context;

    /** 决策（五段式） */
    private String decision;

    /** 后果（五段式） */
    private String consequences;

    /** 关联架构原则 code 列表（JSON 解析还原，可空） */
    private List<String> relatedPrincipleCodes;

    private LocalDate decisionDate;
    private String author;

    /** 被取代指向的新 ADR 编号（superseded 态必有值） */
    private String supersededBy;

    /** 废弃说明（C3：不落列，从最近一次 adr_transit 审计回显；未废弃过为 null） */
    private String deprecateReason;

    /** 原则联动提示（流转离开 accepted 且关联非空时组装：[{code,title}]；其余场景 null） */
    private List<PrincipleHint> principleSyncHints;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 原则联动提示条目（非阻断提示，AC-F4.3）。 */
    @Data
    public static class PrincipleHint {
        private String code;
        private String title;
    }
}
