package com.example.mkagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MkManus 预算非法值回归测试（纯单元，不启动 Spring）。
 *
 * 规则：≤0 或超过上限的值必须告警并回退默认值 8 / 12 / 2m，
 * 防止 0 步让任务原地结束，或误写的大值让成本线性放大。
 *
 * 这里直接构造 MkManus 并调用 apply* 方法：
 * 1. 覆盖 Spring 方法注入的真实入口；
 * 2. 同时证明方法注入不依赖构造器参数，
 *    EvalAgent 手动 super(...) 的既有路径不受影响。
 */
class MkManusBudgetInvalidValueTest {

    private static final int DEFAULT_MAX_STEPS = 8;
    private static final int DEFAULT_MAX_TOOL_CALLS = 12;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    @Test
    void nonPositiveValuesFallBackToDefaults() {
        MkManus agent = newAgent();

        agent.applyMaxSteps(0);
        agent.applyMaxSteps(-1);
        assertEquals(DEFAULT_MAX_STEPS, agent.getMaxSteps());

        agent.applyMaxToolCalls(0);
        agent.applyMaxToolCalls(-1);
        assertEquals(DEFAULT_MAX_TOOL_CALLS, agent.getMaxToolCalls());

        agent.applyTimeout(Duration.ZERO);
        agent.applyTimeout(Duration.ofMinutes(-1));
        assertEquals(DEFAULT_TIMEOUT, agent.getTimeout());
    }

    @Test
    void valuesAboveUpperBoundFallBackToDefaults() {
        MkManus agent = newAgent();

        agent.applyMaxSteps(31);
        assertEquals(DEFAULT_MAX_STEPS, agent.getMaxSteps());

        agent.applyMaxToolCalls(61);
        assertEquals(DEFAULT_MAX_TOOL_CALLS, agent.getMaxToolCalls());

        agent.applyTimeout(Duration.ofMinutes(31));
        assertEquals(DEFAULT_TIMEOUT, agent.getTimeout());
    }

    @Test
    void boundaryValuesAreAccepted() {
        MkManus agent = newAgent();

        agent.applyMaxSteps(30);
        assertEquals(30, agent.getMaxSteps());

        agent.applyMaxToolCalls(60);
        assertEquals(60, agent.getMaxToolCalls());

        agent.applyTimeout(Duration.ofMinutes(30));
        assertEquals(Duration.ofMinutes(30), agent.getTimeout());
    }

    @Test
    void constructorDefaultsMatchOnlineDefaults() {
        MkManus agent = newAgent();

        assertEquals(DEFAULT_MAX_STEPS, agent.getMaxSteps());
        assertEquals(DEFAULT_MAX_TOOL_CALLS, agent.getMaxToolCalls());
        assertEquals(DEFAULT_TIMEOUT, agent.getTimeout());
    }

    /**
     * 非 Spring 场景：EvalAgent 也通过同样的构造器获得默认预算。
     */
    private MkManus newAgent() {
        return new MkManus(
                null,
                new ToolCallback[0],
                Runnable::run,
                null,
                null,
                null,
                null
        );
    }
}
