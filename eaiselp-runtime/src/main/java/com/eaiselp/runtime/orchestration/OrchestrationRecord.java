package com.eaiselp.runtime.orchestration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 编排任务持久化实体（t_orchestration，重启恢复用）。
 *
 * <p>编排状态主存内存（ConcurrentHashMap），本表做持久化快照：
 * 每次状态变更（步骤完成/审批等待/编排结束）异步写库。
 * 服务重启后 getState 内存 miss 时从本表恢复。</p>
 */
@Data
@TableName("t_orchestration")
public class OrchestrationRecord implements Serializable {

    @TableId(type = IdType.INPUT)   // ID 由 OrchestrationService 生成后显式传入
    private Long id;

    private Long tenantId;
    private String caseId;
    private String requirement;
    private String tier;

    /** pending / running / awaiting_approval / done / failed */
    private String status;

    private String currentRole;
    private Long pendingCheckpointId;
    private String approvalMessage;

    /** 步骤列表 JSON 快照（含状态/指令/错误） */
    private String stepsJson;

    /** 产出验证结果摘要 JSON */
    private String validationJson;

    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
