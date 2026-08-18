package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_case")
public class Case extends BaseEntity {
    private String caseId;
    private String title;
    private String layer;
    private String tier;
    private String status;
    private String currentStage;
    private String orchestratorId;
    private String subproject;
    /** legacy 历史字段（VARCHAR，Q6 裁决）：保留只读展示、不写入、不迁移，M3 清理 */
    private String programId;
    /**
     * 所属项目 ID（PRJ-002 F4，V4 r2 列 project_id BIGINT，可空 = 不关联项目，全流程行为一致 AC-F4.3）。
     *
     * <p>本项目一切三层贯通新功能（下行注入/上行汇总/按项目过滤）一律使用本字段，
     * 与 legacy {@link #programId}(VARCHAR) 不迁移不复用。</p>
     */
    private Long projectId;
    private String systemVersion;
    private String requirement;
}
