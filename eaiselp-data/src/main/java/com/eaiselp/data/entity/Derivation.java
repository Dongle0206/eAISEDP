package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_derivation")
public class Derivation extends BaseEntity {
    private String caseId;
    private String role;
    private String stage;
    private String model;
    private String modelTier;
    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal cost;
    private String status;
    private String errorMsg;
    private String producedArtifacts;
    private String experience;
    private Integer retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
}
