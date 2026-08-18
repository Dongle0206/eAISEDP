package com.eaiselp.runtime.context;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

/**
 * 单角色派生上下文（编排/单派生入口构建，DerivationEngine 消费）。
 *
 * <p>PRJ-002 批2 新增 {@link #governanceContext}：预渲染的"架构原则与项目约束"章节文本
 * （含标题，GovernanceInjectionService 一次解析、整条编排复用同一份，SE 决策 D-1）。
 * 本类保持纯数据 POJO——L1 引擎只消费字符串、不反向依赖 hierarchy 数据源（P12/P3 单向依赖）。</p>
 */
@Data
@Builder
public class DerivationContext {
    private String task;
    private String stage;
    private String projectContext;
    private String experienceMemory;
    private Map<String, String> upstreamArtifacts;
    private String extraInstructions;

    /**
     * 治理上下文（PRJ-002 F7 下行注入载体）：预渲染的最终章节文本
     * （"## 架构原则与项目约束（必须遵循）" + 项目约束 + 逐条原则）。
     * null/blank = 整体省略章节（AC-F7：无内容禁止出现空标题）。
     */
    private String governanceContext;
}
