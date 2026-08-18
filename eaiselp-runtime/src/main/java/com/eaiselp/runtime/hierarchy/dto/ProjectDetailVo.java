package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目详情 VO（PRJ-002 T25，SE §8.2 GET /api/v1/projects/{id} 响应契约）。
 *
 * <p>进度三列（progress/caseTotal/caseDone）为系统汇总字段，只读展示（AC-F3.2）；
 * principles 为项目已绑定的架构原则清单（绑定行 ∩ 原则未删，展示原则当前启停态）。</p>
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
}
