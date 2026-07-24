package com.eaiselp.runtime.context;

import org.springframework.stereotype.Component;

/** 上下文装配器：角色 prompt + 任务 + CLAUDE.md + 经验 + 上游产出 → 完整 prompt。 */
@Component
public class ContextAssembler {

    public String assemble(String agentPrompt, DerivationContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(agentPrompt).append("\n\n");
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
