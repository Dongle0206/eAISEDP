package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工程标准实体（t_standard，V6 F1.1 新表，租户级知识资产，不限层，case-20260820 T2）。
 *
 * <p><b>多版本行模型</b>（裁决 Q7，区别于 ADR 单记录/模板原地升版）：同一编号允许多版本行
 * 共存（v1.0 deprecated 与 v2.0 published 并存可追溯），uk(tenant_id, standard_code, version)；
 * "同编号至多一个 published"由发布事务保证（批B T12 交付，D-7 FOR UPDATE 取代），本实体不加约束。</p>
 *
 * <p><b>关联承载</b>：relatedPrincipleCodes/relatedGateNames 以 JSON 数组 String 承载
 * （同 V5 ADR 先例），Jackson 序列化/解析在 Service 层；relatedGateNames 存门禁规则
 * <b>name</b>（租户内唯一业务键，裁决 Q1——关联放标准侧，不改已发布的 t_quality_gate_rule），
 * 保存时逐 name 存在且 enabled 校验（AC-F1.3 翻译口径），查询侧解析与悬空占位由批B T13 交付。</p>
 *
 * <p><b>deprecateReason 落列</b>（V6 纠偏，区别于 ADR 的审计承载）：status=deprecated 时必填，
 * 详情列直读回显。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_standard")
public class Standard extends BaseEntity {

    /** 标准编号（标识一个标准族，租户内默认 STD-NNNN 服务端生成可自定义；同编号多版本行共存） */
    private String standardCode;

    /** 标题（必填 ≤200） */
    private String title;

    /** 版本号（如 v1.0；同编号内不可重复——uk 承载；编辑 published 禁止，升版建新行） */
    private String version;

    /** 状态: draft / published / deprecated（状态机在 {@link StandardStatus}，应用层校验） */
    private String status;

    /** 标准正文 markdown（建议 ≤20000 字符，MEDIUMTEXT 承载） */
    private String content;

    /** 关联架构原则 code 列表（JSON 数组 String，如 ["P3","P11"]；逐 code 存在性校验在 Service） */
    private String relatedPrincipleCodes;

    /** 关联门禁规则 name 列表（JSON 数组 String；逐 name 存在且 enabled 校验在 Service，裁决 Q1） */
    private String relatedGateNames;

    /** 废弃原因（status=deprecated 时必填；发布新版自动取代时由事务写入"被 {编号} {新版本} 取代"） */
    private String deprecateReason;
}
