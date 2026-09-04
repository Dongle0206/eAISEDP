package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模板库实体（t_template，V6 F1.2 新表，租户级知识资产，不限层，case-20260820 T3）。
 *
 * <p><b>单行当前版原地升版模型</b>（裁决 Q7，与标准的多版本行模型有意不同——模板=操作资产、
 * 标准=合规资产）：一个模板一行一个当前态，uk(tenant_id, template_type, template_name)
 * 不含 version；版本是描述字段，编辑时必须变更且 ≠ 当前值（应用层校验，不比较大小，
 * AC-F1.12），旧版本内容唯一留痕 = t_governance_log 审计 detail（不落历史行）。</p>
 *
 * <p><b>类型开放字典</b>（P6 裁决）：templateType 前端预置 5 常用值 + 允许自定义，
 * 应用层不枚举校验（防字典散落两处）。</p>
 *
 * <p><b>占位符清单不落库</b>：详情实时从 content 提取 {@code {{标识符}}} 列表
 * （正则 + 去重排序，AC-F1.11），派生存储与源头 content 保持一致。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_template")
public class Template extends BaseEntity {

    /** 模板类型（开放字典：PRD/技术方案/任务清单/测试用例/部署方案 + 自定义值；应用层不校验枚举） */
    private String templateType;

    /** 模板名称（必填；同类型内唯一——uk 承载） */
    private String templateName;

    /** 版本号（原地升版：编辑时必须 ≠ 当前值；旧版本仅存审计 detail） */
    private String version;

    /** 模板正文 markdown，支持 {{标识符}} 占位符（标识符限字母数字下划线；无占位符合法） */
    private String content;

    /** 启用状态: 1=启用(默认) 0=停用（列表默认隐藏、includeDisabled 筛选可见；本期启停无编排行为影响） */
    private Integer enabled;
}
