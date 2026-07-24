package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_milestone")
public class Milestone extends BaseEntity {
    private String milestoneId;
    private String programId;
    private String title;
    private LocalDate targetDate;
    private String status;
    private String subprojects;
    private String integrationPoints;
    private String blocker;
}
