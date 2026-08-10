package com.kbrag.ratelimit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {
    @Test
    void burst_allowed_up_to_capacity() {
        TokenBucket b = new TokenBucket(3, 1);   // 容量 3,每秒补 1
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());              // 第 4 个被拒
    }

    @Test
    void refills_over_time() throws InterruptedException {
        TokenBucket b = new TokenBucket(1, 10);   // 容量 1,每秒补 10
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());
        Thread.sleep(200);                        // 200ms 补充 2 个(容量封顶 1)
        assertTrue(b.tryAcquire());
    }
}
