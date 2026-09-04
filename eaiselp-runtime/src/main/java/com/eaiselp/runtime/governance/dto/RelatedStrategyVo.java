package com.eaiselp.runtime.governance.dto;

import lombok.Data;

/**
 * 案例关联战略条目 VO（case-20260821 T4，契约 B3 relatedStrategies 记录结构）。
 *
 * <p>战略逻辑删 → {@code deleted=true} 占位（title=null），计算与流转不受影响（AC-F2.8）。</p>
 */
@Data
public class RelatedStrategyVo {

    /** t_strategy.id（裁决 Q4：存 id——t_strategy 无 code 列） */
    private Long id;

    /** 战略目标标题（悬空/已逻辑删为 null，前端渲染"已删除"占位） */
    private String title;

    /** true=悬空（战略已逻辑删/不存在），前端展示"已删除"占位（AC-F2.8） */
    private boolean deleted;
}
