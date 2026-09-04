package com.eaiselp.runtime.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工程标准视图 VO（case-20260820 T2，字段名=api-contracts §1 契约；批B T13 扩展占位字段）。
 *
 * <p>relatedPrincipleCodes/relatedGateNames 为 JSON 数组解析后的 List&lt;String&gt;；
 * deprecateReason 落列直读回显（V6 纠偏，区别于 ADR 的审计回显）。</p>
 *
 * <p>批B T13 增补：{@code deleted}（D-9 gateName 反查路径的"已删除"占位标记）与
 * {@code referencedByGates}（S3 详情被引用门禁列表，悬空 name 占位）。</p>
 */
@Data
public class StandardVo {

    private Long id;

    /** 标准编号（STD-0001） */
    private String standardCode;

    private String title;

    /** 版本号（v1.0） */
    private String version;

    /** draft / published / deprecated */
    private String status;

    /** 标准正文 markdown 全文 */
    private String content;

    /** 关联架构原则 code 列表（JSON 解析还原，可空） */
    private List<String> relatedPrincipleCodes;

    /** 关联门禁规则 name 列表（JSON 解析还原，可空；裁决 Q1 存储侧在标准） */
    private List<String> relatedGateNames;

    /** 废弃原因（deprecated 态直读列；其余为 null） */
    private String deprecateReason;

    private String createBy;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * D-9 占位标记（批B T13）：仅 gateName 反查路径（{@code GET /api/v1/standards?gateName=}
     * &amp;status=published）对逻辑删行置 true，前端渲染"已删除"占位（AC-F1.7）；
     * 常规列表/详情路径恒 null/false（MP @TableLogic 自动过滤，正常查询不含删行）。
     */
    private Boolean deleted;

    /**
     * S3 详情"被引用门禁列表"（批B T13，AC-F1.7 双向关联）：relatedGateNames 解析
     * name→规则当前信息；悬空 name（规则已删除/改名）以 {@code deleted=true} 占位。
     * 仅详情接口填充，列表为 null。
     */
    private List<ReferencedGate> referencedByGates;

    /** 被引用门禁条目（S3 详情；契约 §1 AC-S3 referencedByGates 记录结构）。 */
    @Data
    public static class ReferencedGate {

        /** 门禁规则 name（关联键，租户内唯一业务键 uk_gate_tenant_name） */
        private String name;

        /** 规则当前门禁类型（llm_review/auto_check/human_approval；悬空为 null） */
        private String gateType;

        /** 规则当前挂载阶段（悬空为 null） */
        private String stage;

        /** 规则当前启停（1=启用；悬空为 null） */
        private Integer enabled;

        /** true=悬空（规则已逻辑删/改名），前端展示"未找到/已删除"占位（SE §4.5/R6） */
        private boolean deleted;
    }
}
