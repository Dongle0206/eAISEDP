package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 架构原则实体（L3→L1 下行约束，t_architecture_principle，PRJ-002 T04）。
 *
 * <p>字段与 V4 r2 列一一对应；code 租户内唯一（uk_principle_code(tenant_id, code)，
 * 冲突时 Service 层转 409，AC-F5.1）。租户级表走拦截器，不进 IGNORE_TABLES。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_architecture_principle")
public class ArchitecturePrinciple extends BaseEntity {

    /** 原则编号（如 P3/P6/P11），租户内唯一 */
    private String code;

    /** 原则标题 */
    private String title;

    /** 原则内容（注入 L1 编排上下文，单条建议 ≤2000 字符，AC-F7.4 截断上限之一） */
    private String content;

    /** 原则类型: tech / data / security / governance */
    private String principleType;

    /** 执行级别: must / should / may（must 违反时 Reviewer 门禁拦截；8000 截断按 must&gt;should&gt;may 排序） */
    private String enforceLevel;

    /** 租户级启停: 1=启用(默认) 0=停用（停用即时退出注入，绑定关系保留，AC-F5.2） */
    private Integer enabled;
}
