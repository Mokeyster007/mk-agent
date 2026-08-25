package com.example.mkagent.resilience;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内固定窗口限流器单元测试。
 *
 * 不启动 Spring 上下文，验证三件事：
 * 1. 窗口内超过阈值后被拒绝，且返回建议等待秒数；
 * 2. 高并发下计数原子，绝不超发（等价于 Redis 原子命令语义）；
 * 3. 窗口滚动后配额恢复。
 */
class InMemoryAgentRateLimiterUnitTest {

    @Test
    void rejectsAfterLimitWithWaitSeconds() {
        InMemoryAgentRateLimiter limiter =
                new InMemoryAgentRateLimiter(3, 60, true, Clock.systemUTC());

        String key = "user:1001";

        assertTrue(limiter.tryAcquire(key).allowed());
        assertTrue(limiter.tryAcquire(key).allowed());
        assertTrue(limiter.tryAcquire(key).allowed());

        RateLimitResult fourth = limiter.tryAcquire(key);
        assertFalse(fourth.allowed(), "第 4 次请求应被限流");
        assertTrue(fourth.waitSeconds() >= 1,
                "被拒绝时应给出建议等待秒数");
        assertEquals(3, fourth.limit());

        // 其他限流键不受影响。
        assertTrue(limiter.tryAcquire("user:1002").allowed());
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        int limit = 10;
        int threads = 50;

        InMemoryAgentRateLimiter limiter = new InMemoryAgentRateLimiter(
                limit, 60, true, Clock.systemUTC()
        );

        String key = "user:concurrent";
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicLong allowedCount = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (limiter.tryAcquire(key).allowed()) {
                        allowedCount.incrementAndGet();
                    }
                }));
            }

            // 所有线程同时发起请求，制造最大竞争。
            startGate.countDown();

            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(limit, allowedCount.get(),
                "并发竞争下放行数量必须恰好等于限额，不能超发");
    }

    @Test
    void quotaResetsAfterWindowRolls() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-25T10:00:00Z"));

        InMemoryAgentRateLimiter limiter =
                new InMemoryAgentRateLimiter(2, 60, true, clock);

        String key = "user:2001";

        assertTrue(limiter.tryAcquire(key).allowed());
        assertTrue(limiter.tryAcquire(key).allowed());
        assertFalse(limiter.tryAcquire(key).allowed());

        // 时间推进到下一个窗口，配额恢复。
        clock.advance(Duration.ofSeconds(61));

        assertTrue(limiter.tryAcquire(key).allowed(),
                "新窗口应重新获得配额");
    }

    @Test
    void disabledLimiterAlwaysAllows() {
        InMemoryAgentRateLimiter limiter =
                new InMemoryAgentRateLimiter(1, 60, false, Clock.systemUTC());

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("user:3001").allowed());
        }
    }

    /**
     * 可推进的时钟，用于验证窗口滚动。
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
