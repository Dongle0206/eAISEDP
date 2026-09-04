package com.eaiselp.runtime.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模板库视图 VO（case-20260820 T3，字段名=api-contracts §2 契约）。
 *
 * <p>placeholders 为详情/列表实时从 content 提取的 {@code {{标识符}}} 清单（去重排序，
 * 不落库，AC-F1.11）；列表场景仅带 placeholderCount，详情带完整清单。</p>
 */
@Data
public class TemplateVo {

    private Long id;

    /** 模板类型（开放字典，含自定义值） */
    private String templateType;

    private String templateName;

    /** 版本号（原地升版：编辑时必须变更） */
    private String version;

    /** 模板正文 markdown（详情全文；列表不重复下发） */
    private String content;

    /** 启用状态: 1 / 0 */
    private Integer enabled;

    /** 占位符清单（详情实时提取，去重排序；无占位符 → 空数组合法） */
    private List<String> placeholders;

    /** 占位符数量（列表展示列，实时提取） */
    private Integer placeholderCount;

    private String createBy;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
