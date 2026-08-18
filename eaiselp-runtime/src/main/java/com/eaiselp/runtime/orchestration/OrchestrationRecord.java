package com.eaiselp.runtime.orchestration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * 下行注入清单快照（PRJ-002 F7，AC-F7.1 三处留痕之一）：注入的原则 code 列表
     * （List&lt;String&gt; 的 JSON）。批2 仅落字段（OrchestrationService 不改），
     * 写入由批3 persistState 接线。
     *
     * <p>注意列名为 <b>injected_json</b>（DBA V4 r2 落盘命名，与 tasks.md 对账结论一致），
     * 与字段名 injectedPrinciplesJson（批2 任务约定）不一致，用 @TableField 显式映射。</p>
     */
    @TableField("injected_json")
    private String injectedPrinciplesJson;

    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
