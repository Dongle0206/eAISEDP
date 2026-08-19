package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目详情 VO（PRJ-002 T25，SE §8.2 GET /api/v1/projects/{id} 响应契约）。
 *
 * <p>进度三列（progress/caseTotal/caseDone）为系统汇总字段，只读展示（AC-F3.2）；
 * principles 为项目已绑定的架构原则清单（绑定行 ∩ 原则未删，展示原则当前启停态）。</p>
 *
 * <p><b>case-20260818 T9 扩展（只加字段不删改，前端向后兼容）</b>：+achievementHint（达成提示，
 * AC-F2.4/F2.5）+ dependencies（依赖区块，AC-F3.5 展示入口）。两块由 T22 在 ProjectServiceImpl
 * 接线（批③），计算异常降级 null 不阻塞详情主渲染（PRD §6.3）——批A 阶段两字段恒 null。</p>
 */
@Data
public class ProjectDetailVo {

    private Long id;
    /** 所属项目群（可空 = 独立项目，AC-F3.1） */
    private Long programId;
    private String name;
    /** 项目描述/约束（非空时下行注入 Case 编排，F7） */
    private String description;
    /** planning / in_progress / delivered / closed */
    private String status;
    private Integer priority;
    /** 进度百分比 0-100（只读，ProjectProgressService 全量重算唯一写入） */
    private Integer progress;
    private Integer caseTotal;
    private Integer caseDone;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 已绑定原则清单（含项目级 enabled 覆盖位与原则租户级启停态） */
    private List<PrincipleItem> principles;

    /**
     * 达成提示（AC-F2.4/F2.5，T9 新增字段/T22 接线）：
     * eligible = caseTotal&gt;0 && caseDone==caseTotal && 存在 planned 里程碑（数据源=既有汇总字段，
     * 零新口径）；空项目（total=0）或未全 done → null/eligible=false（不出现提示条）。
     * 计算异常降级 null 不阻塞详情主渲染。
     */
    private AchievementHint achievementHint;

    /**
     * 依赖区块（AC-F3.5 展示入口，T9 新增字段/T22 接线）：等待/责任两组边，
     * 对方项目已删除的边不出现在内；计算异常降级 null。
     */
    private DependencySection dependencies;

    /** 项目已绑定原则条目 */
    @Data
    public static class PrincipleItem {
        private Long id;
        /** 原则编号（如 P11） */
        private String code;
        private String title;
        /** must / should / may */
        private String enforceLevel;
        /** 原则租户级启停态（false 时新编排注入不再包含，绑定关系保留，AC-F5.2） */
        private Boolean enabled;
        /** 项目级覆盖位（false = 本项目停用但保留绑定） */
        private Boolean projectEnabled;
    }

    /** 里程碑达成提示块（提示是唯一联动，确认永远人工）。 */
    @Data
    public static class AchievementHint {
        /** true=全部 Case 已完成且存在 planned 里程碑，可提示确认达成 */
        private Boolean eligible;
        /** 可达成的 planned 里程碑 id 列表 */
        private List<Long> milestoneIds;
        /** "项目全部 Case 已完成，可达成里程碑" */
        private String message;
    }

    /** 项目详情依赖区块（结构同 DependencyBoardVo.EdgeItem，对方已删边过滤）。 */
    @Data
    public static class DependencySection {
        /** 本项目等待谁（from=本项目 的边） */
        private List<DependencyBoardVo.EdgeItem> waitingFor;
        /** 谁依赖本项目（to=本项目 的边） */
        private List<DependencyBoardVo.EdgeItem> responsibleFor;
    }
}
