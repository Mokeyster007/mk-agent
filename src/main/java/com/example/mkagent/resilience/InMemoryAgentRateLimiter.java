package com.example.mkagent.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内固定窗口限流器（无 Redis 时的默认实现）。
 *
 * 原子性设计：
 * 每个限流键对应一个 Window，内部用
 * AtomicReference&lt;long[]&gt; 保存 [窗口编号, 已用次数]，
 * 通过 CAS 循环完成 "窗口滚动 + 计数 + 超限判断" 三步，
 * 并发调用不会出现竞态超发（等价于 Redis 单条命令的原子语义）。
 *
 * 局限（文档见 05-agent-resilience-and-observability.md）：
 * 1. 仅对单实例生效，多实例部署需切换 Redis 实现；
 * 2. 固定窗口在窗口切换瞬间可能短时通过 2 倍流量，
 *    对 Agent 这种低频高成本场景可接受。
 */
@Component
public class InMemoryAgentRateLimiter implements AgentRequestRateLimiter {

    private static final Logger log =
            LoggerFactory.getLogger(InMemoryAgentRateLimiter.class);

    /**
     * 每个窗口内允许的最大请求数。
     */
    private final int maxRequests;

    /**
     * 窗口长度（毫秒）。
     */
    private final long windowMillis;

    /**
     * 限流总开关；关闭后所有请求直接放行（便于排查问题）。
     */
    private final boolean enabled;

    private final Clock clock;

    /**
     * key = 限流键，value = 该键的窗口计数状态。
     */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryAgentRateLimiter(
            @Value("${mkagent.rate-limit.max-requests:10}") int maxRequests,
            @Value("${mkagent.rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${mkagent.rate-limit.enabled:true}") boolean enabled
    ) {
        this(maxRequests, windowSeconds, enabled, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器：单测可以推进时间验证窗口滚动。
     */
    public InMemoryAgentRateLimiter(
            int maxRequests,
            long windowSeconds,
            boolean enabled,
            Clock clock
    ) {
        if (maxRequests <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "限流配置必须为正数：maxRequests=" + maxRequests
                            + ", windowSeconds=" + windowSeconds
            );
        }
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000;
        this.enabled = enabled;
        this.clock = clock;
        log.info(
                "Agent 限流器初始化：enabled={}, maxRequests={}, windowSeconds={}",
                enabled, maxRequests, windowSeconds
        );
    }

    @Override
    public RateLimitResult tryAcquire(String rateLimitKey) {
        if (!enabled) {
            return RateLimitResult.allow(maxRequests);
        }

        Window window = windows.computeIfAbsent(
                rateLimitKey, key -> new Window()
        );

        long now = clock.millis();
        long windowIndex = now / windowMillis;

        /*
         * CAS 循环：窗口滚动与计数递增作为一个原子整体完成。
         * long[] 布局：[0] = 窗口编号，[1] = 窗口内已用次数。
         */
        while (true) {
            long[] current = window.state.get();

            long[] next;
            if (current[0] != windowIndex) {
                // 进入新窗口：计数清零，本次占用第 1 个名额。
                next = new long[]{windowIndex, 1};
            } else {
                next = new long[]{windowIndex, current[1] + 1};
            }

            if (window.state.compareAndSet(current, next)) {
                if (next[1] <= maxRequests) {
                    return RateLimitResult.allow(maxRequests);
                }

                // 超限：回滚刚才占用的名额，保持计数语义干净。
                rollback(window, windowIndex);

                long remainMillis = windowMillis - (now % windowMillis);
                long waitSeconds = (remainMillis + 999) / 1000;

                log.warn(
                        "Agent 请求被限流：key={}, limit={}, waitSeconds={}",
                        rateLimitKey, maxRequests, waitSeconds
                );
                return RateLimitResult.reject(maxRequests, waitSeconds);
            }
            // CAS 失败：其他线程已更新，重新读取并重试。
        }
    }

    /**
     * 超限后回滚名额：仍在同一窗口时才递减，
     * 避免把新窗口刚重置的计数误减。
     */
    private void rollback(Window window, long windowIndex) {
        while (true) {
            long[] current = window.state.get();
            if (current[0] != windowIndex) {
                return;
            }
            long[] next = new long[]{windowIndex, current[1] - 1};
            if (window.state.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /**
     * 单个限流键的窗口状态。
     */
    private static final class Window {

        private final AtomicReference<long[]> state =
                new AtomicReference<>(new long[]{-1L, 0L});
    }
}
