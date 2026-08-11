package com.kbrag.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 令牌桶:key = rate:{userId} 存当前令牌数,rate:{userId}:ts 存上次补充时间戳。
 * Lua 脚本单次原子执行(Redis 单线程保证)——面试点:为什么不用 get+set(竞态:并发请求同时读到旧令牌)。
 */
@Service
public class RateLimiter {
    private final StringRedisTemplate redis;
    private final double capacity;
    private final double refillPerSecond;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${app.rate-limit.capacity:10}") double capacity,
                       @Value("${app.rate-limit.refill-per-second:1}") double refillPerSecond) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local cap = tonumber(ARGV[2])
            local refill = tonumber(ARGV[3])
            local tokens = tonumber(redis.call('GET', key) or cap)
            local last = tonumber(redis.call('GET', key .. ':ts') or now)
            tokens = math.min(cap, tokens + (now - last) / 1000.0 * refill)
            if tokens < 1 then
                redis.call('SET', key .. ':ts', now)
                return 0
            end
            redis.call('SET', key, tokens - 1)
            redis.call('SET', key .. ':ts', now)
            return 1
            """, Long.class);

    /** userId 粒度限流;false = 超限。Redis 故障时 fail-open(不阻断服务),生产应加告警。 */
    public boolean tryAcquire(String userId) {
        try {
            Long r = redis.execute(SCRIPT,
                List.of("rate:" + userId),
                String.valueOf(System.currentTimeMillis()),   // ARGV[1] now
                String.valueOf(capacity),                     // ARGV[2] cap
                String.valueOf(refillPerSecond));             // ARGV[3] refill
            return r != null && r == 1L;
        } catch (Exception e) {
            // fail-open:限流是保护性组件,故障时降级为不限流(服务可用优先),代价是短暂无防护
            // 面试口径:限流组件故障 → 服务降级 + 告警,而不是把整个对话打挂
            System.err.println("[ratelimit] redis unavailable, fail-open: " + e.getMessage());
            return true;
        }
    }
}
