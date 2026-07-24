package com.eaiselp.runtime.task;

import com.eaiselp.runtime.engine.DerivationEngine;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 派生任务内存态（M2-DFX，SE 技术方案 §5.1）。
 *
 * <p>存于 {@link DerivationTaskService} 的 {@link java.util.concurrent.ConcurrentHashMap}，
 * 作为运行态/近期完成态的快速查询层；最终态持久化到 t_derivation（内存 miss 回落 DB 查询）。
 *
 * <p>taskId = t_derivation.id（雪花），内存/DB/前端三方主键统一（SE §5.1 决策，省一层映射）。
 *
 * <p>状态机：{@code pending → running → success / failed}（SE §4.1.2）。
 */
@Data
@Builder
public class DerivationTaskState {

    /** 任务 id = t_derivation.id。 */
    private Long taskId;

    /** 状态：pending / running / success / failed / not_found（GET miss 时）。 */
    private String status;

    /** 角色（team-* 等）。 */
    private String role;

    /** 案例 id。 */
    private String caseId;

    /** 派生 stage（可选）。 */
    private String stage;

    /** 派生结果（仅 status=success 时非空，结构同 DerivationResult）。 */
    private DerivationEngine.DerivationResult result;

    /** 失败原因（仅 status=failed 时非空）。 */
    private String error;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** 最后访问毫秒时间戳（TTL 清理依据，volatile 防多线程可见性问题）。 */
    private volatile long lastAccessAt;
}
