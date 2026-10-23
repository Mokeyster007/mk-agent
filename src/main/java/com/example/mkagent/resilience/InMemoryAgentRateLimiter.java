package com.example.mkagent.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 * 内存回收（惰性清理）：
 * 若只增不删，windows 会随限流键数量无限增长（用户量即键数量）。
 * 因此每次 tryAcquire 会判断是否满足清理条件：
 * 1. 累计尝试次数达到 cleanupEveryAcquires（默认 256）；
 * 2. 或距上次清理已超过一个窗口；
 * 满足则由单个线程执行一次扫描，移除“窗口编号早于当前窗口”的键。
 *
 * 清理与计数的并发安全：
 * - 清理使用 windows.computeIfPresent，在回调里重新读取窗口状态再决定是否移除；
 * - tryAcquire 在 CAS 成功后校验 windows.get(key) == window：
 *   若窗口已被清理，先尝试 putIfAbsent 把同一实例放回（自增仍有效）；
 *   若已有新窗口，则丢弃这次自增并直接在新窗口上重新计数。
 *   计数最终总是落在“map 中当前登记的那个 Window 实例”上，
 *   因此既不会多放行，也不会少放行，最坏情况只是多一次 CAS 重试。
 *
 * 局限（文档见 docs/agent-learning/05-agent-resilience-and-observability.md）：
 * 1. 仅对单实例生效，多实例部署需切换 Redis 实现；
 * 2. 固定窗口在窗口切换瞬间可能短时通过 2 倍流量，
 *    对 Agent 这种低频高成本场景可接受。
 */
@Component
public class InMemoryAgentRateLimiter implements AgentRequestRateLimiter {

    private static final Logger log =
            LoggerFactory.getLogger(InMemoryAgentRateLimiter.class);

    /**
     * 默认清理周期：每 N 次 tryAcquire 触发一次扫描。
     */
    private static final int DEFAULT_CLEANUP_EVERY_ACQUIRES = 256;

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
     * 计数触发的清理周期。
     */
    private final int cleanupEveryAcquires;

    /**
     * key = 限流键，value = 该键的窗口计数状态。
     */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * tryAcquire 累计调用次数，用于计数触发清理。
     */
    private final AtomicLong acquireSequence = new AtomicLong();

    /**
     * 上次清理时间（毫秒），用于时间触发清理。
     */
    private final AtomicLong lastCleanupMillis = new AtomicLong();

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
        this(
                maxRequests,
                windowSeconds,
                enabled,
                clock,
                DEFAULT_CLEANUP_EVERY_ACQUIRES
        );
    }

    /**
     * 可注入时钟与清理周期的构造器：单测可缩短清理周期验证惰性清理。
     */
    public InMemoryAgentRateLimiter(
            int maxRequests,
            long windowSeconds,
            boolean enabled,
            Clock clock,
            int cleanupEveryAcquires
    ) {
        if (maxRequests <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "限流配置必须为正数：maxRequests=" + maxRequests
                            + ", windowSeconds=" + windowSeconds
            );
        }
        if (cleanupEveryAcquires <= 0) {
            throw new IllegalArgumentException(
                    "清理周期必须为正数：cleanupEveryAcquires=" + cleanupEveryAcquires
            );
        }
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000;
        this.enabled = enabled;
        this.clock = clock;
        this.cleanupEveryAcquires = cleanupEveryAcquires;
        log.info(
                "Agent 限流器初始化：enabled={}, maxRequests={}, windowSeconds={}, cleanupEveryAcquires={}",
                enabled, maxRequests, windowSeconds, cleanupEveryAcquires
        );
    }

    @Override
    public RateLimitResult tryAcquire(String rateLimitKey) {
        if (!enabled) {
            return RateLimitResult.allow(maxRequests);
        }

        long now = clock.millis();
        long windowIndex = now / windowMillis;

        /*
         * 惰性清理：放在计数之前，避免清理动作被超限分支跳过。
         */
        maybeCleanup(now, windowIndex);

        Window window = windows.computeIfAbsent(
                rateLimitKey, key -> new Window()
        );

        while (true) {
            long[] current = window.state.get();

            long[] next;
            if (current[0] != windowIndex) {
                // 进入新窗口：计数清零，本次占用第 1 个名额。
                next = new long[]{windowIndex, 1};
            } else {
                next = new long[]{windowIndex, current[1] + 1};
            }

            if (!window.state.compareAndSet(current, next)) {
                // CAS 失败：其他线程已更新，重新读取并重试。
                continue;
            }

            /*
             * CAS 成功后再确认该窗口仍在 map 中：
             * 清理线程可能刚把它移除，此时这次自增写在了“孤儿对象”上。
             *
             * 处理分两种：
             * 1. putIfAbsent 放回成功 → 自增仍然有效（计数落在同一个对象上），
             *    窗口也重新登记，后续清理不会再删（它已是最新窗口编号）；
             * 2. 已有别的线程建了新窗口 → 放弃这次自增，
             *    直接切到新窗口重新计数。
             *
             * 第 2 种情况下请求本身不会丢：外层循环会基于新窗口重新走一次 CAS，
             * 只多一次重试，不会“多放行一次”，也不会“少放行一次”。
             */
            if (windows.get(rateLimitKey) != window) {
                Window liveWindow = windows.putIfAbsent(rateLimitKey, window);
                if (liveWindow != null) {
                    window = liveWindow;
                    continue;
                }
            }

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
    }

    /**
     * 惰性清理触发判断：计数触发 + 时间触发。
     *
     * 用 CAS 保证同一时刻只有一个线程真正执行扫描，
     * 避免高并发下每个请求都去遍历整个 map。
     */
    private void maybeCleanup(long now, long windowIndex) {
        boolean byCount =
                acquireSequence.incrementAndGet() % cleanupEveryAcquires == 0;

        long lastCleanup = lastCleanupMillis.get();
        boolean byTime = now - lastCleanup >= windowMillis;

        if (!byCount && !byTime) {
            return;
        }

        if (!lastCleanupMillis.compareAndSet(lastCleanup, now)) {
            // 其他线程已在清理，跳过本次。
            return;
        }

        cleanupStaleWindows(windowIndex);
    }

    /**
     * 移除窗口编号早于当前窗口的键。
     *
     * 使用 computeIfPresent：回调内重新读取状态再决定是否返回 null，
     * 与 tryAcquire 的 CAS 形成“检查 + 删除”的原子组合。
     */
    private void cleanupStaleWindows(long currentWindowIndex) {
        int sizeBefore = windows.size();

        for (String key : windows.keySet()) {
            windows.computeIfPresent(key, (k, window) -> {
                long[] state = window.state.get();

                /*
                 * state[0] == -1 表示该窗口刚被创建、尚未完成首次 CAS。
                 * 此时删除会让另一个线程把计数写进孤儿对象（少算），
                 * 因此必须跳过。
                 */
                if (state[0] == -1L) {
                    return window;
                }

                // 只清理“上一个窗口及更早”的键；当前窗口的键必须保留。
                return state[0] < currentWindowIndex ? null : window;
            });
        }

        int removed = sizeBefore - windows.size();
        if (removed > 0) {
            log.debug(
                    "限流窗口惰性清理完成：removed={}, remaining={}, currentWindow={}",
                    removed, windows.size(), currentWindowIndex
            );
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
     * 当前登记的限流键数量。
     *
     * 仅供单元测试与观测使用（生产代码不要依赖该值做逻辑判断）。
     */
    int trackedKeyCount() {
        return windows.size();
    }

    /**
     * 单个限流键的窗口状态。
     */
    private static final class Window {

        private final AtomicReference<long[]> state =
                new AtomicReference<>(new long[]{-1L, 0L});
    }
}
