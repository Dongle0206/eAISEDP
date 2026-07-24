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
    private String programId;
    private String systemVersion;
    private String requirement;
}
