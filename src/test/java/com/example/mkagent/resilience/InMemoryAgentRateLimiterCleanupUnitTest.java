package com.example.mkagent.resilience;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InMemoryAgentRateLimiter 惰性清理单元测试。
 *
 * 覆盖三件事（全部离线，使用可推进时钟）：
 * 1. 窗口滚动后，旧窗口的键会被清理，map 不会无限增长；
 * 2. 计数触发 + 时间触发都能驱动清理；
 * 3. 清理与 tryAcquire 交错执行时不会超发（重复 200 轮以放大偶发竞态）。
 */
class InMemoryAgentRateLimiterCleanupUnitTest {

    @Test
    void staleKeysAreRemovedAfterWindowRolls() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-25T10:00:00Z"));

        InMemoryAgentRateLimiter limiter =
                new InMemoryAgentRateLimiter(1000, 60, true, clock);

        // 第一个窗口内创建 100 个限流键。
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("user:" + i);
        }
        assertEquals(100, limiter.trackedKeyCount());

        // 推进到下一个窗口：下一次 tryAcquire 触发时间清理。
        clock.advance(Duration.ofSeconds(61));
        limiter.tryAcquire("user:fresh");

        assertEquals(
                1,
                limiter.trackedKeyCount(),
                "旧窗口的键应被清理，只保留当前窗口使用的键"
        );
    }

    @Test
    void countTriggerKeepsMapBoundedAcrossManyWindows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-25T10:00:00Z"));

        // 清理周期设为 1：每次 tryAcquire 都满足计数触发条件。
        InMemoryAgentRateLimiter limiter =
                new InMemoryAgentRateLimiter(1000, 60, true, clock, 1);

        for (int round = 0; round < 3; round++) {
            clock.advance(Duration.ofSeconds(61));

            for (int i = 0; i < 50; i++) {
                limiter.tryAcquire("round-" + round + ":user-" + i);
            }

            assertTrue(
                    limiter.trackedKeyCount() <= 51,
                    "每个窗口结束后只应保留当前窗口的键，实际："
                            + limiter.trackedKeyCount()
            );
        }
    }

    @Test
    void concurrentAcquireDuringCleanupNeverExceedsLimit() throws Exception {
        int maxRequests = 5;
        int keyCount = 20;
        int iterations = 200;

        MutableClock clock = new MutableClock(Instant.parse("2026-08-25T10:00:00Z"));

        /*
         * 清理周期设为 8：
         * 200 轮 × 20 键 = 4000 次 tryAcquire，
         * 期间会触发数百次清理，与计数过程充分交错。
         */
        InMemoryAgentRateLimiter limiter =
                new InMemoryAgentRateLimiter(maxRequests, 60, true, clock, 8);

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            keys.add("user:" + i);
            // 在旧窗口里先各占用一次，制造“待清理的旧窗口键”。
            limiter.tryAcquire("user:" + i);
        }

        // 进入新窗口后，这些键的窗口状态仍停留在旧窗口，等待被清理。
        clock.advance(Duration.ofSeconds(61));

        Map<String, AtomicLong> allowedPerKey = new ConcurrentHashMap<>();
        keys.forEach(key -> allowedPerKey.put(key, new AtomicLong()));

        ExecutorService pool = Executors.newFixedThreadPool(keyCount);
        try {
            for (int round = 0; round < iterations; round++) {
                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();

                for (String key : keys) {
                    futures.add(pool.submit(() -> {
                        try {
                            startGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (limiter.tryAcquire(key).allowed()) {
                            allowedPerKey.get(key).incrementAndGet();
                        }
                    }));
                }

                // 同一轮内所有键同时抢，最大化与清理的交错概率。
                startGate.countDown();

                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        for (String key : keys) {
            long allowed = allowedPerKey.get(key).get();
            assertTrue(
                    allowed <= maxRequests,
                    "清理期间不允许超发：key=" + key
                            + ", allowed=" + allowed
                            + ", limit=" + maxRequests
            );
        }

        // 反向校验：测试不是“永远不放行”的空跑。
        assertTrue(
                allowedPerKey.values().stream().anyMatch(v -> v.get() == maxRequests),
                "至少应有一个键用满配额，说明测试确实在放行"
        );
    }

    /**
     * 可推进的时钟，用于验证窗口滚动与清理。
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
