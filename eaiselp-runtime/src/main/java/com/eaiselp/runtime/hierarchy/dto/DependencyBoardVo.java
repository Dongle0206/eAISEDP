package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

import java.util.List;

/**
 * 跨项目依赖 blocked 看板 VO（case-20260818 T9，字段名=api-contracts §3 board 契约）。
 *
 * <p><b>展示层实时判定，不落库</b>（AC-F3.2）：项目 P blocked ⟺ 存在强依赖边
 * (P→Q, dependency_type='depends_on') 且 Q.status ∉ {delivered, closed}；
 * 被依赖项目置 delivered/closed 后刷新即自动解除；relates_to 边不参与判定（AC-F3.4）。</p>
 */
@Data
public class DependencyBoardVo {

    /** 统计条 */
    private Stats stats;

    /** 项目卡（依赖图中出现的全部涉项目，依赖方视角分组） */
    private List<ProjectCard> projects;

    /** 统计条：totalProjects 涉及项目数 / blockedCount 被阻塞数 / edgeCount 活跃边数 */
    @Data
    public static class Stats {
        private Integer totalProjects;
        private Integer blockedCount;
        private Integer edgeCount;
    }

    /** 项目卡：blocked 徽标 + 阻塞来源链 + 等待/责任两组边。 */
    @Data
    public static class ProjectCard {
        private Long projectId;
        private String projectName;
        /** planning / in_progress / delivered / closed（状态快照） */
        private String status;
        /** true=存在强依赖边指向未交付项目 */
        private Boolean blocked;
        /** 阻塞链文案（"被 项目A 阻塞：项目A 未交付"），非 blocked 为空列表 */
        private List<String> blockedSources;
        /** P 依赖谁（from=P 的边，依赖方视角） */
        private List<EdgeItem> waitingFor;
        /** 谁依赖 P（to=P 的边，责任项） */
        private List<EdgeItem> responsibleFor;
    }

    /** 边条目：归一化存储形态 + origType 解析还原（C1）。 */
    @Data
    public static class EdgeItem {
        private Long edgeId;
        /** 对端项目 ID（waitingFor 视角=被依赖方 to；responsibleFor 视角=依赖方 from） */
        private Long toProjectId;
        private String toProjectName;
        /** depends_on / relates_to（归一化存储值） */
        private String dependencyType;
        /** blocks / depends_on / relates_to（note 前缀 [orig:blocks] 解析还原；失败默认 depends_on 文案） */
        private String origType;
        /** 展示名（"受阻"/"依赖"/"关联"） */
        private String displayName;
        /** 备注（API 语义名，V5 列 note） */
        private String remark;
    }
}
