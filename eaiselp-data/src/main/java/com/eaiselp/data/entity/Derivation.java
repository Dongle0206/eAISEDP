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

    /**
     * 门禁判定结果（V5 F1 DORA 埋点列，case-20260818 T1）：PASS / FAIL / FAIL_WARN。
     *
     * <p>NULL = 埋点上线（T21 接线）前的历史记录，或 LLM 未输出判定/断点续跑路径——
     * DORA RT 口径对 NULL 行走"≈"近似分支（PRD §4.1.2④，D-2 不回填历史）。
     * 唯一写入点 = OrchestrationService.runAsync 门禁判定三支汇合处 markGateResult；
     * 写入失败仅 ERROR 日志，不影响编排主流程（PRD §6.3）。</p>
     */
    private String gateResult;
}
