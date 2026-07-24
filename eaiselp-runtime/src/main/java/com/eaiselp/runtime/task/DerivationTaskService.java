package com.eaiselp.runtime.task;

import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.service.DerivationService;
import com.eaiselp.runtime.engine.DerivationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 派生任务状态机服务（M2-DFX，SE 技术方案 §5.2 / §5.3 / §11）。
 *
 * <p>职责：
 * <ol>
 *   <li>{@link #createPending}：雪花生成 id → INSERT pending 占位行 → 内存 put → 返回 id；</li>
 *   <li>{@link #markRunning}：内存 + DB 标 running；</li>
 *   <li>{@link #markSuccess}：内存填 result（DB success 已由 engine.derive 内部 persist UPDATE 完成）；</li>
 *   <li>{@link #markFailed}：内存填 error + DB 显式 UPDATE status=failed/error_msg
 *       （SE §5.3 D-6：engine.derive 落库容错不重抛，失败时 DB 不会自动标 failed，须此处补写）；</li>
 *   <li>{@link #getTask}：内存 → DB miss 回落 → 仍 miss 返回 status=not_found。</li>
 * </ol>
 *
 * <p><b>内存 map 治理（SE §5.2 / §11 风险）</b>：
 * <ul>
 *   <li>{@link ConcurrentHashMap}，软上限 1 万条（防 OOM）；</li>
 *   <li>{@code @Scheduled} 定时清理已完成且超 {@code task-memory-ttl-minutes}（默认 60 分钟）的项；</li>
 *   <li>重启丢失：内存 miss 回落 DB 查（运行中被重启打断的任务 DB 仍 running，
 *       M2 dogfooding 可接受，M3 Redis + 启动恢复见 SE §12）。</li>
 * </ul>
 *
 * <p><b>多租户隔离</b>：t_derivation 带 tenant_id，MyBatis-Plus 拦截器自动注入过滤（ES-003 §9.3）。
 * createPending 走 {@code DerivationService}（已走拦截器），GET 回查亦走拦截器，符合 P11。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DerivationTaskService {

    private final DerivationService derivationService;

    /** 内存任务态表：taskId → state。软上限 1 万防 OOM（SE §5.2）。 */
    private final ConcurrentHashMap<Long, DerivationTaskState> memoryMap = new ConcurrentHashMap<>();

    private static final int SOFT_LIMIT = 10_000;

    @Value("${eaiselp.task.memory-ttl-minutes:60}")
    private int memoryTtlMinutes;

    /**
     * 创建 pending 任务（提交即调用）。
     *
     * <p>流程：雪花生成 id（由 DerivationService.save 的 ASSIGN_ID 回填）
     * → INSERT pending 占位行 → 内存 put → 返回 id。
     *
     * <p>注意：占位行 status=pending、started_at=now，但 model/tokens/cost 等留 NULL，
     * 由 engine.derive 后续 UPDATE 填充（SE §5.3 createPending 与 persist 字段分工）。
     *
     * @param role  角色（team-* 等）
     * @param caseId 案例 id（可空）
     * @param stage  派生 stage（可空）
     * @return taskId（= t_derivation.id）
     */
    public Long createPending(String role, String caseId, String stage) {
        LocalDateTime now = LocalDateTime.now();
        Derivation d = new Derivation();
        d.setRole(role);
        d.setCaseId(caseId);
        d.setStage(stage);
        d.setStatus("pending");
        d.setStartedAt(now);
        // INSERT → ASSIGN_ID 回填 d.id（雪花）
        derivationService.save(d);
        Long taskId = d.getId();
        if (taskId == null) {
            // 理论上不发生（ASSIGN_ID 一定回填），兜底防 NPE
            throw new IllegalStateException("createPending 后 id 未回填，MP ASSIGN_ID 配置异常");
        }
        memoryMap.put(taskId, DerivationTaskState.builder()
                .taskId(taskId).status("pending").role(role).caseId(caseId).stage(stage)
                .createdAt(now).startedAt(now)
                .lastAccessAt(System.currentTimeMillis())
                .build());
        return taskId;
    }

    /** pending → running（异步线程拿到任务后调）。内存 + DB 同步。 */
    public void markRunning(Long taskId) {
        updateMemory(taskId, s -> {
            s.setStatus("running");
            s.setStartedAt(LocalDateTime.now());
        });
        Derivation d = new Derivation();
        d.setId(taskId);
        d.setStatus("running");
        d.setStartedAt(LocalDateTime.now());
        derivationService.updateById(d);
    }

    /**
     * running → success（engine.derive 正常返回后调）。
     *
     * <p>注意（SE §5.3）：DB 的 status=success + model/tokens/cost/finished_at 等
     * 已由 {@code DerivationPersistenceService.persist()}（经 DerivationTaskIdHolder 走 UPDATE）写入，
     * 此处<b>不重复写 DB</b>，只同步内存态。
     */
    public void markSuccess(Long taskId, DerivationEngine.DerivationResult result) {
        updateMemory(taskId, s -> {
            s.setStatus("success");
            s.setResult(result);
            if (result != null && result.getFinishedAt() != null) {
                s.setFinishedAt(result.getFinishedAt());
            } else {
                s.setFinishedAt(LocalDateTime.now());
            }
        });
    }

    /**
     * → failed（engine.derive 抛 Throwable 后调，SE §5.3 D-6）。
     *
     * <p>DB 显式 UPDATE status=failed + error_msg：engine.derive 落库容错 try-catch 不重抛，
     * 失败时 persist 根本没执行，DB 仍是 pending/running，须此处补写 failed 标记。
     */
    public void markFailed(Long taskId, Throwable t) {
        String errMsg = t == null ? "unknown error" : (t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        updateMemory(taskId, s -> {
            s.setStatus("failed");
            s.setError(errMsg);
            s.setFinishedAt(LocalDateTime.now());
        });
        Derivation d = new Derivation();
        d.setId(taskId);
        d.setStatus("failed");
        // error_msg 长度兜底：异常 message 可能很长，DB error_msg 列 VARCHAR/CLOB，
        // 这里截断到 2000 字符防超长（MySQL TEXT 不限但其他列类型可能限长）
        d.setErrorMsg(truncate(errMsg, 2000));
        d.setFinishedAt(LocalDateTime.now());
        try {
            derivationService.updateById(d);
        } catch (Exception dbEx) {
            // DB 写失败不能阻塞：内存态已标 failed，前端轮询仍能拿到结果（SE §11 重启风险缓解）
            log.error("[AsyncTask] markFailed 写 DB 失败 taskId={}", taskId, dbEx);
        }
    }

    /**
     * 查任务态：内存 → DB miss 回落 → not_found（SE §4.1.2 / §5.2）。
     *
     * @return DerivationTaskState，status 含 not_found（GET miss 时不抛 404，便于前端判断）
     */
    public DerivationTaskState getTask(Long taskId) {
        if (taskId == null) {
            return notFound();
        }
        // 1. 内存查
        DerivationTaskState s = memoryMap.get(taskId);
        if (s != null) {
            s.setLastAccessAt(System.currentTimeMillis());
            return s;
        }
        // 2. DB miss 回落
        Derivation d = derivationService.getById(taskId);
        if (d == null) {
            return notFound();
        }
        // 从 DB 行重构内存态（不入 map：避免短期 DB-only 历史记录占内存；查询直接返回）
        return DerivationTaskState.builder()
                .taskId(taskId)
                .status(d.getStatus())
                .role(d.getRole())
                .caseId(d.getCaseId())
                .stage(d.getStage())
                .error(d.getErrorMsg())
                .createdAt(d.getCreateTime())
                .startedAt(d.getStartedAt())
                .finishedAt(d.getFinishedAt())
                .lastAccessAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 定时清理：已完成（success/failed）且超 TTL 的内存态（SE §11 / §12 风险缓解）。
     * fixedDelay 10 分钟。
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void cleanupCompleted() {
        long cutoff = System.currentTimeMillis() - Duration.ofMinutes(memoryTtlMinutes).toMillis();
        int removed = 0;
        Iterator<Map.Entry<Long, DerivationTaskState>> it = memoryMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, DerivationTaskState> e = it.next();
            DerivationTaskState s = e.getValue();
            String st = s.getStatus();
            boolean done = "success".equals(st) || "failed".equals(st) || "not_found".equals(st);
            if (done && s.getLastAccessAt() < cutoff) {
                it.remove();
                removed++;
            }
        }
        // 软上限保护：即便有未完成项，超 1 万也按最旧淘汰（防 OOM，SE §5.2）
        if (memoryMap.size() > SOFT_LIMIT) {
            log.warn("[AsyncTask] 内存任务态超软上限 {}，当前 {}，建议排查任务积压",
                    SOFT_LIMIT, memoryMap.size());
        }
        if (removed > 0) {
            log.info("[AsyncTask] 内存态清理：移除 {} 个已完成超 {}分钟项，剩余 {}",
                    removed, memoryTtlMinutes, memoryMap.size());
        }
    }

    // -------- helpers --------

    private void updateMemory(Long taskId, java.util.function.Consumer<DerivationTaskState> updater) {
        DerivationTaskState s = memoryMap.get(taskId);
        if (s == null) {
            // 异常场景：内存已被 TTL 清理但异步任务还在跑。重建一个最小态防 NPE。
            s = DerivationTaskState.builder().taskId(taskId).build();
            memoryMap.put(taskId, s);
        }
        updater.accept(s);
        s.setLastAccessAt(System.currentTimeMillis());
    }

    private DerivationTaskState notFound() {
        return DerivationTaskState.builder()
                .status("not_found")
                .lastAccessAt(System.currentTimeMillis())
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
