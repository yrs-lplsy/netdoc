package com.kbrag.ratelimit;

/**
 * 令牌桶纯算法:容量 capacity,每秒补充 refillPerSecond 个令牌。
 * 与存储解耦,单测可跑;Redis 层只做状态持久化与原子性。
 */
public class TokenBucket {
    private final double capacity;        // 令牌桶最大容量，不可修改
    private final double refillPerSecond; // 每秒填充令牌数目
    private double tokens;                 // 当前桶现存令牌
    private long lastRefillNanos;          // 上一次更新令牌的时间戳，单位纳秒

    public TokenBucket(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 尝试消耗 1 个令牌;不足返回 false。synchronized 保证并发安全。 */
    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        // 距离上一次计算令牌经过的秒数。
        double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsed * refillPerSecond);
        lastRefillNanos = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
