package com.eaiselp.runtime.context;

import org.springframework.stereotype.Component;

/**
 * 上下文装配器：角色 prompt + 治理上下文 + 任务 + CLAUDE.md + 经验 + 上游产出 → 完整 prompt。
 *
 * <p>本类保持无状态、零数据源依赖（只拼接不取数）：PRJ-002 批2 新增治理上下文章节由
 * GovernanceInjectionService 预渲染为最终字符串经 {@code DerivationContext.governanceContext}
 * 传入，本类仅做非空判断与拼接（SE 决策 D-1：防止引擎层反向依赖 hierarchy 数据源）。</p>
 *
 * <p>章节顺序（批2 排布）：角色 prompt → <b>架构原则与项目约束（治理约束优先于任务描述，
 * 角色第一时间读到约束）</b> → 本次任务 → 项目约定 → 角色经验 → 上游产出 → 编排者指令。</p>
 */
@Component
public class ContextAssembler {

    public String assemble(String agentPrompt, DerivationContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(agentPrompt).append("\n\n");
        // 治理上下文（PRJ-002 F7）：预渲染文本自带章节标题（AC-F7.1 断言锚点）；
        // null/blank 整体省略——无内容禁止出现空标题章节（AC-F7）
        if (ctx.getGovernanceContext() != null && !ctx.getGovernanceContext().isEmpty()) {
            sb.append("---\n").append(ctx.getGovernanceContext()).append("\n\n");
        }
        if (ctx.getTask() != null && !ctx.getTask().isEmpty()) {
            sb.append("---\n## 本次任务\n").append(ctx.getTask()).append("\n\n");
        }
        if (ctx.getProjectContext() != null && !ctx.getProjectContext().isEmpty()) {
            sb.append("---\n## 项目约定（CLAUDE.md）\n").append(ctx.getProjectContext()).append("\n\n");
        }
        if (ctx.getExperienceMemory() != null && !ctx.getExperienceMemory().isEmpty()) {
            sb.append("---\n## 角色经验（避免重复踩坑）\n").append(ctx.getExperienceMemory()).append("\n\n");
        }
        if (ctx.getUpstreamArtifacts() != null && !ctx.getUpstreamArtifacts().isEmpty()) {
            sb.append("---\n## 上游产出（必读）\n");
            ctx.getUpstreamArtifacts().forEach((k, v) -> {
                sb.append("### ").append(k).append("\n").append(truncate(v, 4000)).append("\n\n");
            });
        }
        if (ctx.getExtraInstructions() != null && !ctx.getExtraInstructions().isEmpty()) {
            sb.append("---\n## 编排者指令\n").append(ctx.getExtraInstructions()).append("\n\n");
        }
        return sb.toString();
    }

    private String truncate(String content, int maxChars) {
        if (content == null) return "";
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n...(已截断，原长度 " + content.length() + ")";
    }
}
