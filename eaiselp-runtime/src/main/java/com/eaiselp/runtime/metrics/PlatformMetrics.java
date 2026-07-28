package com.eaiselp.runtime.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平台业务指标埋点（M3-1 监控告警）。
 *
 * <p>统一通过 Micrometer {@link MeterRegistry} 写指标，由 micrometer-registry-prometheus
 * 在 {@code /actuator/prometheus} 以 Prometheus exposition format 暴露。
 *
 * <p>所有业务指标统一加 {@code application=eaiselp-runtime} 全局标签（yml 内 management.metrics.tags 配置），
 * Grafana 跨实例聚合时无需额外按 instance 拆分即可按应用维度筛选。
 *
 * <h3>指标清单</h3>
 * <ul>
 *   <li>{@code eaiselp_derivation_total{role,status}} —— 派生计数器（成功/失败），用于成功率告警；</li>
 *   <li>{@code eaiselp_llm_duration_seconds{provider,model}} —— LLM 调用耗时直方图，定位慢调用/P95；</li>
 *   <li>{@code eaiselp_case_active} —— 活跃 Case 数量仪表，监控并发压力；</li>
 *   <li>{@code eaiselp_token_consumed_total{type=input|output}} —— token 消耗计数器，对接成本治理。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：MeterRegistry 内部对同名同 tag 的指标做了注册去重（线程安全），
 * 故此处 {@link #incrementActiveCase}/{@link #decrementActiveCase} 用 AtomicLong 做 gauge 状态持有，
 * {@code activeCaseGauge} 始终引用同一个 AtomicLong 实例，注册只发生一次。
 *
 * <p><b>容错</b>：所有埋点 try-catch，绝不因监控写入失败拖垮派生主流程（与 DerivationEngine 落库容错策略一致）。
 */
@Slf4j
@Component
public class PlatformMetrics {

    private static final String METRIC_DERIVATION = "eaiselp_derivation_total";
    private static final String METRIC_LLM_DURATION = "eaiselp_llm_duration_seconds";
    private static final String METRIC_CASE_ACTIVE = "eaiselp_case_active";
    private static final String METRIC_TOKEN = "eaiselp_token_consumed_total";

    private final MeterRegistry registry;

    /** 活跃 case 计数器：gauge 需要强引用持有，避免 GC 后 gauge 失效。 */
    private final AtomicLong activeCaseGauge = new AtomicLong(0L);

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
        // gauge 必须在构造时注册一次（Micrometer gauge 不可重复注册，且引用对象不能被回收）
        registry.gauge(METRIC_CASE_ACTIVE, activeCaseGauge);
    }

    /**
     * 记录一次派生结果 + LLM 调用耗时 + token 消耗（统一入口，供 DerivationAsyncRunner 调用）。
     *
     * <p>同时更新三项指标：派生计数器（含 role/status 维度）、LLM 耗时计时器（含 provider/model 维度）、
     * token 消耗计数器（input/output 拆分）。一次调用即覆盖派生全链路核心度量。
     *
     * @param role         角色（team-* 等）
     * @param status       派生结果状态：success / failed（与 t_derivation.status 对齐）
     * @param durationMs   LLM 调用耗时（毫秒）
     * @param provider     LLM 厂商（glm / deepseek / ...），失败无法解析时传 "unknown"
     * @param model        具体模型名（路由表解析后）
     * @param inputTokens  输入 token 数（可空，失败时可能为空）
     * @param outputTokens 输出 token 数（可空）
     */
    public void recordDerivation(String role, String status, long durationMs,
                                 String provider, String model,
                                 Integer inputTokens, Integer outputTokens) {
        // 容错：监控埋点不能拖垮主流程（与 DerivationEngine 落库容错策略一致）
        try {
            // 1. 派生计数器：role + status 双标签，Grafana 可按 role 维度看各角色成功率
            registry.counter(METRIC_DERIVATION,
                    Tags.of("role", nullSafe(role), "status", nullSafe(status))).increment();
            // 2. LLM 耗时计时器：直方图 + 百分位，定位慢调用 / P95
            Timer.builder(METRIC_LLM_DURATION)
                    .tags("provider", nullSafe(provider), "model", nullSafe(model))
                    .register(registry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
            // 3. token 消耗计数器：input/output 拆分，对接成本治理
            if (inputTokens != null && inputTokens > 0) {
                registry.counter(METRIC_TOKEN, Tags.of("type", "input")).increment(inputTokens);
            }
            if (outputTokens != null && outputTokens > 0) {
                registry.counter(METRIC_TOKEN, Tags.of("type", "output")).increment(outputTokens);
            }
        } catch (Throwable t) {
            // 仅 log 不抛：监控写入失败不能影响业务（指标丢了下次 scrape 周期会重统计，可接受）
            log.warn("[Metrics] recordDerivation 写入失败（已忽略，不影响业务）role={}, status={}: {}",
                    role, status, t.toString());
        }
    }

    /** 活跃 case 数 +1（Case 进入 running 派生时调）。 */
    public void incrementActiveCase() {
        try {
            activeCaseGauge.incrementAndGet();
        } catch (Throwable t) {
            log.warn("[Metrics] incrementActiveCase 失败: {}", t.toString());
        }
    }

    /** 活跃 case 数 -1（Case 派生完成/失败时调）。 */
    public void decrementActiveCase() {
        try {
            // 使用 max(0, x-1) 防止重复 decrement 导致负数（gauge 负值会误导告警）
            activeCaseGauge.updateAndGet(v -> Math.max(0L, v - 1L));
        } catch (Throwable t) {
            log.warn("[Metrics] decrementActiveCase 失败: {}", t.toString());
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "unknown" : s;
    }
}
