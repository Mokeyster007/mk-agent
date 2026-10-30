package com.example.mkagent.resilience;

import com.example.mkagent.tools.ToolResultMarkers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点级熔断的单元测试（不联网、不启动 Spring）。
 *
 * 背景：on-exhausted=return 之后任务不再失败，但模型会反复重试同一个
 * 打不开的站点，出现过 MAX_STEPS_REACHED、28308 Token 的任务。
 *
 * 约定：
 * 1. 同一目标连续耗尽达到阈值后，后续调用**不再执行底层工具**，
 *    直接返回"该站点连续失败……本次未再执行"；
 * 2. 不同主机互不影响；
 * 3. 未绑定计数器（脱离 Agent 链路直接调用）时保持原有行为。
 */
class RepeatedTargetBlockCallbackTest {

    private static final String TOOL_NAME = "web_scrape";

    private StubScrapeTool scrapeTool;

    private ToolTargetExhaustionTracker tracker;

    @BeforeEach
    void setUp() {
        scrapeTool = new StubScrapeTool();
        tracker = new ToolTargetExhaustionTracker();
        // 生产链路由 ToolCallAgent.act() 绑定；这里手动绑定模拟同一时刻。
        ToolTargetExhaustionTracker.bind(tracker);
    }

    @AfterEach
    void tearDown() {
        ToolTargetExhaustionTracker.unbind();
    }

    @Test
    @DisplayName("同一目标连续耗尽 2 次后，第 3 次不再真正调用底层工具")
    void thirdCallAfterRepeatedExhaustionIsNotExecuted() {
        ToolCallback callback = wrap(2);

        String first = callback.call(
                input("https://github.com/a"));
        String second = callback.call(
                input("https://github.com/b"));

        // 前两次都是"真失败"：maxAttempts=2 → 2 次底层调用 / 次。
        assertEquals(4, scrapeTool.callCount.get(),
                "前两次调用各应尝试 maxAttempts=2 次");
        assertTrue(first.contains(ToolResultMarkers.EXHAUSTED));
        assertTrue(second.contains(ToolResultMarkers.EXHAUSTED));

        String third = callback.call(
                input("https://github.com/c"));

        /*
         * 核心断言：第 3 次完全没有触碰底层工具。
         * 这里用调用次数而不是返回值来断言——
         * 只要底层被调用过，次数就会增加。
         */
        assertEquals(4, scrapeTool.callCount.get(),
                "达到阈值后不得再执行底层工具（否则每轮白烧 2 次尝试 + 退避等待）");

        assertTrue(third.contains(ToolResultMarkers.TARGET_BLOCKED),
                "必须返回站点级熔断文案，实际：" + third);
        assertTrue(third.contains("请换用其他来源"),
                "文案要给出换方案的指引，实际：" + third);
        assertTrue(third.contains("terminate_task"),
                "文案要给出收尾选项，实际：" + third);
        assertFalse(third.contains(ToolResultMarkers.EXHAUSTED),
                "熔断文案不能与'这次试了但失败'混淆，否则模型会以为还能再试");
    }

    @Test
    @DisplayName("不同主机互不影响：一个站点被熔断后，另一个站点照常执行")
    void differentHostsAreNotAffected() {
        ToolCallback callback = wrap(2);

        callback.call(input("https://github.com/a"));
        callback.call(input("https://github.com/b"));

        // github.com 已熔断。
        String blocked = callback.call(input("https://github.com/c"));
        assertTrue(blocked.contains(ToolResultMarkers.TARGET_BLOCKED));

        int beforeOtherHost = scrapeTool.callCount.get();

        String otherHost = callback.call(
                input("https://docs.example.com/x"));

        assertEquals(beforeOtherHost + 2, scrapeTool.callCount.get(),
                "另一个主机必须真的被执行（maxAttempts=2）");
        assertTrue(otherHost.contains(ToolResultMarkers.EXHAUSTED),
                "另一个主机自己失败，但不该被别人的熔断牵连：" + otherHost);
        assertFalse(otherHost.contains(ToolResultMarkers.TARGET_BLOCKED));
    }

    @Test
    @DisplayName("阈值可配置：阈值为 1 时第 2 次即被拦截")
    void thresholdIsConfigurable() {
        ToolCallback callback = wrap(1);

        callback.call(input("https://github.com/a"));
        int afterFirst = scrapeTool.callCount.get();

        String second = callback.call(
                input("https://github.com/b"));

        assertEquals(afterFirst, scrapeTool.callCount.get(),
                "阈值 1 时第 2 次就应被拦截");
        assertTrue(second.contains(ToolResultMarkers.TARGET_BLOCKED));
    }

    @Test
    @DisplayName("阈值为 0 时关闭熔断，行为回到改动前")
    void zeroThresholdDisablesBreaker() {
        ToolCallback callback = wrap(0);

        for (int i = 0; i < 4; i++) {
            String result = callback.call(
                    input("https://github.com/p" + i));
            assertTrue(result.contains(ToolResultMarkers.EXHAUSTED),
                    "关闭熔断后每次都真执行、真失败：" + result);
        }

        assertEquals(8, scrapeTool.callCount.get(),
                "4 次调用 × maxAttempts=2");
    }

    @Test
    @DisplayName("未绑定计数器时不做熔断（保持脱离 Agent 链路的旧行为）")
    void noBoundTrackerKeepsLegacyBehaviour() {
        // 模拟"工具被直接调用、不在 Agent 执行期内"的场景。
        ToolTargetExhaustionTracker.unbind();

        ToolCallback callback = wrap(2);

        for (int i = 0; i < 4; i++) {
            String result = callback.call(input("https://github.com/p" + i));
            assertTrue(result.contains(ToolResultMarkers.EXHAUSTED),
                    "无绑定时应每次都真执行：" + result);
        }

        assertEquals(8, scrapeTool.callCount.get());
    }

    @Test
    @DisplayName("中间成功一次不清零：目标是'别再撞这个站点'，不是'连续失败次数'")
    void successDoesNotResetTargetCount() {
        FlakyScrapeTool flaky = new FlakyScrapeTool();
        ToolCallback callback = wrapTool(flaky, 2);

        // 第 1 次：耗尽（两次都失败）。
        callback.call(input("https://github.com/a"));
        // 第 2 次：成功（不再失败）。
        String success = callback.call(input("https://github.com/b"));
        assertFalse(success.contains(ToolResultMarkers.EXHAUSTED),
                "这次应成功：" + success);
        // 第 3 次：再次耗尽 → 计数到 2。
        callback.call(input("https://github.com/c"));

        int beforeBlocked = flaky.callCount.get();
        String blocked = callback.call(input("https://github.com/d"));

        assertEquals(beforeBlocked, flaky.callCount.get(),
                "累计两次耗尽后应拦截，中间的成功不清零");
        assertTrue(blocked.contains(ToolResultMarkers.TARGET_BLOCKED));
    }

    // ===== 测试辅助 =====

    private ToolCallback wrap(int maxExhaustedPerTarget) {
        return wrapTool(scrapeTool, maxExhaustedPerTarget);
    }

    private ToolCallback wrapTool(Object tool, int maxExhaustedPerTarget) {
        ToolCallback[] callbacks = ToolCallbacks.from(tool);
        assertEquals(1, callbacks.length);

        return new RetryableToolCallback(
                callbacks[0],
                2,
                Duration.ofMillis(1),
                1.0,
                List.of("网页抓取失败"),
                RetryableToolCallback.ExhaustedStrategy.RETURN,
                maxExhaustedPerTarget
        );
    }

    private String input(String url) {
        return "{\"url\":\"" + url + "\"}";
    }

    /**
     * 始终失败的抓取工具（模拟打不开的站点）。
     */
    public static final class StubScrapeTool {

        private final AtomicInteger callCount = new AtomicInteger();

        private final List<String> urls = new ArrayList<>();

        @Tool(name = TOOL_NAME, description = "测试用网页抓取工具（始终失败）")
        public String scrape(
                @ToolParam(description = "网页地址") String url
        ) {
            callCount.incrementAndGet();
            urls.add(url);
            return "网页抓取失败：Read timed out";
        }
    }

    /**
     * 先失败、后成功的抓取工具（用于验证"成功不清零"）。
     */
    public static final class FlakyScrapeTool {

        private final AtomicInteger callCount = new AtomicInteger();

        @Tool(name = TOOL_NAME, description = "测试用网页抓取工具（先失败后成功）")
        public String scrape(
                @ToolParam(description = "网页地址") String url
        ) {
            int current = callCount.incrementAndGet();
            // 第 3 次成功，其余失败。
            if (current == 3) {
                return "页面标题：Example";
            }
            return "网页抓取失败：Read timed out";
        }
    }
}
