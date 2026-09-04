package com.eaiselp.runtime.governance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据质量规则视图 VO（case-20260820 T5，字段名=api-contracts §4 契约）。
 *
 * <p>assetName 为关联资产摘要（列表批量装配避免 N+1；资产可能已被联动逻辑删，摘要保留
 * 登记时的最近快照——资产被删时规则同步逻辑删，列表不可见，无需悬空占位）。</p>
 */
@Data
public class DataQualityRuleVo {

    private Long id;

    /** 规则名（租户内唯一） */
    private String ruleName;

    /** 关联资产 ID */
    private Long assetId;

    /** 关联资产摘要（assetName/systemName，列表与详情装配） */
    private String assetName;
    private String assetSystemName;

    /** completeness / accuracy / consistency / timeliness */
    private String checkType;

    /** 百分比达标线 0~100（展示附 %） */
    private BigDecimal threshold;

    /** pass / fail / null=从未登记 */
    private String lastResult;

    private BigDecimal lastActualValue;
    private LocalDateTime lastCheckTime;
    private String lastCheckRemark;

    private String createBy;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
