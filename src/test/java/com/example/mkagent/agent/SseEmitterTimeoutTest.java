package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SseEmitter 超时计算（纯函数，不启动 Spring、不联网）。
 *
 * 背景：emitter 超时原本与 agent timeout 是同一个时刻。
 * Agent 超时后还要推 TIMED_OUT 状态与 DONE，
 * 容器异步超时先到就会把这几条事件吞掉。
 *
 * 这里锁定的是"数"：
 *   emitter 超时 = agent timeout + buffer
 * 真实链路能不能把超时事件送到客户端，由
 * {@code SseTimeoutEventDeliveryTest} 用真实容器验证。
 */
class SseEmitterTimeoutTest {

    /**
     * 最小可实例化的 BaseAgent：
     * BaseAgent 只依赖 step()，其余能力（持久化、并发、注册表）都是可选的。
     */
    private static final class TestAgent extends BaseAgent {

        private TestAgent() {
            super(Runnable::run);
        }

        @Override
        protected String step(AgentRunContext ctx) {
            return "noop";
        }
    }

    @Test
    @DisplayName("默认配置：agent 2m + buffer 30s = 150s")
    void defaultTimeoutIs150Seconds() {
        TestAgent agent = new TestAgent();

        assertEquals(120_000L, agent.getTimeout().toMillis(),
                "BaseAgent 默认 agent 超时仍是 2 分钟（不得因本次改动而变化）");
        assertEquals(30_000L, agent.getSseBuffer().toMillis(),
                "默认缓冲 30 秒");
        assertEquals(150_000L, agent.resolveEmitterTimeout(),
                "emitter 超时应为 agent 超时 + 缓冲");
    }

    @Test
    @DisplayName("自定义缓冲：2m + 10s = 130s")
    void customBufferIsAdded() {
        TestAgent agent = new TestAgent();
        agent.setSseBuffer(Duration.ofSeconds(10));

        assertEquals(130_000L, agent.resolveEmitterTimeout());
    }

    @Test
    @DisplayName("自定义 agent 超时同样参与计算：30s + 30s = 60s")
    void customAgentTimeoutIsAdded() {
        TestAgent agent = new TestAgent();
        agent.setTimeout(Duration.ofSeconds(30));

        assertEquals(60_000L, agent.resolveEmitterTimeout());
    }

    @Test
    @DisplayName("缓冲越界（0s / 10m / null）回退默认 30s，不影响启动")
    void invalidBufferFallsBackToDefault() {
        TestAgent zero = new TestAgent();
        zero.setSseBuffer(Duration.ZERO);
        assertEquals(BaseAgent.DEFAULT_SSE_BUFFER, zero.getSseBuffer());
        assertEquals(150_000L, zero.resolveEmitterTimeout());

        TestAgent tooLarge = new TestAgent();
        tooLarge.setSseBuffer(Duration.ofMinutes(10));
        assertEquals(BaseAgent.DEFAULT_SSE_BUFFER, tooLarge.getSseBuffer());

        TestAgent nullBuffer = new TestAgent();
        nullBuffer.setSseBuffer(null);
        assertEquals(BaseAgent.DEFAULT_SSE_BUFFER, nullBuffer.getSseBuffer());
    }

    @Test
    @DisplayName("缓冲边界值 1s 与 5m 是合法的（不触发回退）")
    void boundaryValuesAreAccepted() {
        TestAgent min = new TestAgent();
        min.setSseBuffer(BaseAgent.MIN_SSE_BUFFER);
        assertEquals(BaseAgent.MIN_SSE_BUFFER, min.getSseBuffer());
        assertEquals(121_000L, min.resolveEmitterTimeout());

        TestAgent max = new TestAgent();
        max.setSseBuffer(BaseAgent.MAX_SSE_BUFFER);
        assertEquals(BaseAgent.MAX_SSE_BUFFER, max.getSseBuffer());
        assertEquals(420_000L, max.resolveEmitterTimeout());
    }

    @Test
    @DisplayName("极端超时相加溢出时退化为上限，绝不返回负数（负数 = 永不过期）")
    void overflowNeverYieldsNegativeTimeout() {
        TestAgent agent = new TestAgent();
        agent.setTimeout(Duration.ofMillis(Long.MAX_VALUE));

        assertEquals(Long.MAX_VALUE, agent.resolveEmitterTimeout(),
                "负数在 SseEmitter 里表示永不过期，会把配置笔误变成连接永不释放");
    }
}
