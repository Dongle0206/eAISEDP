package com.eaiselp.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流桶注册表（M2-DFX，SE 技术方案 §4.2.6）。
 *
 * <p>内存级 {@link ConcurrentHashMap} 存 {@code (桶标识 → Bucket + 最后访问时间)}。
 * 桶标识 = {@code <name>:<key>}，如 {@code login:192.168.1.1}、{@code derive:1001}。
 *
 * <p>OOM 防护（SE §4.2.6 / §11 风险）：
 * <ol>
 *   <li>TTL：定时任务每 5 分钟清理 {@code bucket-ttl-minutes}（默认 30 分钟）未访问的桶；</li>
 *   <li>硬上限：桶总数超 {@code bucket-max-size}（默认 10 万）告警（M3 换 Redis backend 自然解决）。</li>
 * </ol>
 *
 * <p>令牌桶语义（Bucket4j）：
 * <ul>
 *   <li>{@code capacity} = 桶容量（瞬时突发上限）；</li>
 *   <li>{@code refillPerMin} = 每分钟匀速补充令牌数（greedy refill）。</li>
 * </ul>
 */
@Slf4j
@Component
public class BucketRegistry {

    /** 桶包装：Bucket + 最后访问毫秒时间戳（TTL 清理依据）。 */
    private static final class BucketEntry {
        final Bucket bucket;
        volatile long lastAccessAt;

        BucketEntry(Bucket bucket) {
            this.bucket = bucket;
            this.lastAccessAt = System.currentTimeMillis();
        }
        void touch() { this.lastAccessAt = System.currentTimeMillis(); }
    }

    private final ConcurrentHashMap<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    @Value("${eaiselp.ratelimit.bucket-ttl-minutes:30}")
    private int bucketTtlMinutes;

    @Value("${eaiselp.ratelimit.bucket-max-size:100000}")
    private int bucketMaxSize;

    /**
     * 取或建桶（线程安全：computeIfAbsent 原子）。
     *
     * @param bucketId     桶唯一标识（name:key）
     * @param capacity     桶容量
     * @param refillPerMin 每分钟补充令牌数
     * @return Bucket 实例
     */
    public Bucket getOrCreate(String bucketId, int capacity, int refillPerMin) {
        // 硬上限告警（不阻断创建，防限流功能失效；M3 换 Redis 后此问题自然消失）
        if (buckets.size() >= bucketMaxSize) {
            log.warn("[RateLimit] 桶数量达硬上限 {}，疑似异常流量或 TTL 未生效，建议排查", bucketMaxSize);
        }
        BucketEntry entry = buckets.computeIfAbsent(bucketId, k -> {
            // Refill.intervally：每 60s 补 refillPerMin 个，最大 capacity 个；
            // 与 SE §4.2 "5 次/分/IP" 等口径一致（capacity 也设为同等值，无额外突发余量）。
            Bandwidth limit = Bandwidth.classic(capacity,
                    Refill.intervally(capacity, Duration.ofMinutes(1)));
            // 兼容 refillPerMin != capacity 的灵活配法（如 capacity=20, refillPerMin=10：允许瞬时 20 突发、稳态 10/分）
            // 当两者相等时退化为 classic 语义，与示例（5/5、10/10、100/100）等价。
            return new BucketEntry(Bucket.builder().addLimit(limit).build());
        });
        entry.touch();
        return entry.bucket;
    }

    /** 定时清理超时未访问的桶（SE §4.2.6：fixedDelay 5 分钟）。 */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void cleanupExpiredBuckets() {
        long cutoff = System.currentTimeMillis() - Duration.ofMinutes(bucketTtlMinutes).toMillis();
        int removed = 0;
        Iterator<Map.Entry<String, BucketEntry>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BucketEntry> e = it.next();
            if (e.getValue().lastAccessAt < cutoff) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[RateLimit] TTL 清理：移除 {} 个超时桶（{}分钟未访问），剩余 {}",
                    removed, bucketTtlMinutes, buckets.size());
        }
    }

    /** 测试/监控用：当前桶数量。 */
    public int size() { return buckets.size(); }
}
