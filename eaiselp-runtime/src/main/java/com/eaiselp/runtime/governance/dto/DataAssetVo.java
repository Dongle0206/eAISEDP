package com.eaiselp.runtime.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据资产视图 VO（case-20260820 T4，字段名=api-contracts §3 契约）。
 *
 * <p>tags 为 JSON 数组解析后的 List&lt;String&gt;；详情（A3）额外携带 rules 聚合区
 * （规则数 + 各规则最近结果，AC-F2.6 Then"资产详情展示该规则及 fail 结果"）。</p>
 */
@Data
public class DataAssetVo {

    private Long id;

    /** 资产名称（如 t_order） */
    private String assetName;

    /** 所属系统（如 ERP） */
    private String systemName;

    /** database / table / api / report / file */
    private String assetType;

    private String owner;

    /** public / internal / sensitive / confidential（四档色阶前端渲染） */
    private String sensitivity;

    private String description;

    /** 标签列表（JSON 解析还原，可空） */
    private List<String> tags;

    private String createBy;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 关联质量规则聚合（仅 A3 详情装配；列表为 null） */
    private RulesAggregation rules;

    /** 资产详情的质量规则聚合区（AC-F2.6 Then）。 */
    @Data
    public static class RulesAggregation {
        /** 关联规则数 */
        private long count;
        /** 各规则摘要（含最近结果四列） */
        private List<RuleBrief> items;
    }

    /** 规则摘要条目（契约 §3 A3 items 结构）。 */
    @Data
    public static class RuleBrief {
        private Long ruleId;
        private String ruleName;
        /** completeness / accuracy / consistency / timeliness */
        private String checkType;
        /** 百分比达标线 0~100（展示附 %） */
        private java.math.BigDecimal threshold;
        /** pass / fail / null=从未登记 */
        private String lastResult;
        private java.math.BigDecimal lastActualValue;
        private LocalDateTime lastCheckTime;
    }
}
