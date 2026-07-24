package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_checkpoint")
public class Checkpoint extends BaseEntity {
    private String caseId;
    private String operation;
    private String operationDetail;
    private String status;
    private LocalDateTime requestedAt;
    private LocalDateTime confirmedAt;
    private String confirmedBy;
    private String comment;
    private Long derivationId;
}
