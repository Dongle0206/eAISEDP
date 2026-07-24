package com.eaiselp.runtime.task;

/**
 * 派生任务 id 传递 ThreadLocal（M2-DFX，SE §5.3 决策点 D-4 落地）。
 *
 * <p><b>解决的问题（D-4）</b>：现有 {@code DerivationPersistenceService.persist()} 用
 * {@code derivationService.save(d)}（INSERT，MP ASSIGN_ID 生成新雪花 id），而异步化要求
 * taskId = createPending 时预占的 id。若让 persist 仍走 INSERT，会产生新 id 行，
 * 与 createPending 的 pending 行冲突（DB 残留两条记录、taskId 对不上结果）。
 *
 * <p><b>解法（零改 DerivationEngine 同步核心逻辑）</b>：
 * <ol>
 *   <li>{@code DerivationAsyncRunner} 在调 {@code engine.derive()} 前 {@link #set(Long)} 注入 taskId；</li>
 *   <li>{@code DerivationPersistenceService.persist()} 顶部 {@link #get()} 读 taskId：
 *     <ul>
 *       <li>非空（异步路径）→ {@code d.setId(taskId)} 后走 {@code updateById}（UPDATE 预占行）；</li>
 *       <li>为空（同步测试/老路径）→ 保持原 {@code save()} INSERT 行为不变，零回归。</li>
 *     </ul></li>
 *   <li>{@code DerivationAsyncRunner} 在 finally 里 {@link #clear()} 必清（防线程池线程复用泄漏）。</li>
 * </ol>
 *
 * <p>ThreadLocal 的线程范围天然匹配：{@code engine.derive()} 与 {@code persist()}
 * 在同一线程栈上调用，holder 在异步线程内 set → engine → persist 全程可见，
 * 不跨线程、不跨请求。线程池线程复用后下一个任务前 holder 必须被 clear（否则会串到下一个任务）。
 */
public final class DerivationTaskIdHolder {

    private static final ThreadLocal<Long> TASK_ID = new ThreadLocal<>();

    private DerivationTaskIdHolder() {}

    public static void set(Long taskId) { TASK_ID.set(taskId); }

    /** 取当前线程绑定的 taskId，无则返回 null（同步路径 / 未设置）。 */
    public static Long get() { return TASK_ID.get(); }

    /** 必清：异步线程结束后清理，防线程池线程复用导致下一个任务读到上个任务的 id。 */
    public static void clear() { TASK_ID.remove(); }
}
