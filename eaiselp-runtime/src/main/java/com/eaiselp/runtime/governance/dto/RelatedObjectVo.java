package com.eaiselp.runtime.governance.dto;

import lombok.Data;

/**
 * 风险关联对象条目 VO（case-20260821 T2，契约 R3 relatedObjects 记录结构）。
 *
 * <p><b>id 统一 String 承载</b>（编排者契约对齐裁决，2026-09-04）：program/project 为
 * 数字主键的字符串形式（"88"）；case 为平台对外业务键 caseId（t_case.case_id VARCHAR，
 * 如 "case-xxx"）——与既有 API 惯例一致（Case 对外标识是字符串 caseId 而非 BIGINT 主键）。
 * 入参提交数字 id 时 Jackson 自动强转为字符串，两种写法等价。</p>
 *
 * <p>悬空/已逻辑删的关联对象以 {@code deleted=true} 占位（name=null），不 400 不静默丢
 * （AC-F1.6，展示级打通边界——被关联对象逻辑删后不影响风险任何行为）。</p>
 */
@Data
public class RelatedObjectVo {

    /** 对象类型: program / project / case（裁决 Q4 存 id+type） */
    private String type;

    /**
     * 对象标识（String）：program/project=数字 id 字符串形式；case=caseId 业务键字符串。
     * 悬空/已逻辑删时保留原值（前端据 deleted 渲染占位）。
     */
    private String id;

    /** 对象当前名称（悬空/已逻辑删为 null，前端渲染"已删除"占位） */
    private String name;

    /** true=悬空（对象已逻辑删/不存在），前端展示"已删除"占位（AC-F1.6） */
    private boolean deleted;
}
