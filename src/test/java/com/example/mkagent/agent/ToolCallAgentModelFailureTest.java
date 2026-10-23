package com.example.mkagent.agent;

import com.example.mkagent.config.ModelRetryConfig;
import com.example.mkagent.config.ModelRetryProperties;
import com.example.mkagent.model.AgentEvent;
import com.example.mkagent.model.AgentEventType;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用失败时 Agent 的快速失败语义（全部使用桩模型，不联网）。
 *
 * 验收点：
 * 1. 400（NonTransientAiException）只调用 1 次；
 * 2. 任务立刻进入 FAILED，而不是挂到 Agent 总超时（TIMED_OUT）；
 * 3. 前端收到一条脱敏的 ERROR 事件（不含 HTTP 原始报文）；
 * 4. 5xx/429 重试耗尽后同样快速失败，文案区分"稍后重试"。
 */
class ToolCallAgentModelFailureTest {

    @Test
    @DisplayName("400 参数错误：只调用 1 次、任务 FAILED、立即收到脱敏 ERROR 事件")
    void nonTransientFailsFast() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel failingModel = prompt -> {
            calls.incrementAndGet();
            throw new NonTransientAiException(
                    "HTTP 400 - InvalidParameter: url is invalid, request_id=abc"
            );
        };

        ToolCallAgent agent = newAgent(failingModel, Duration.ofSeconds(30));
        AgentRunContext ctx = new AgentRunContext();
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        ctx.setEmitter(emitter);

        long start = System.currentTimeMillis();
        String result = agent.run("帮我查一下今天的天气", ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, calls.get(), "4xx 属于不可重试错误，模型只应被调用一次");
        assertEquals(AgentState.FAILED, ctx.getState(), "应立刻 FAILED，而不是等到超时");

        // 30 秒超时的任务，实际应在毫秒级结束（留足 CI 抖动余量）。
        assertTrue(elapsed < 5_000, "失败应立即返回，实际耗时=" + elapsed + "ms");

        AgentEvent errorEvent = singleEvent(emitter, AgentEventType.ERROR);
        assertEquals(
                ToolCallAgent.MODEL_CONFIG_FAILED_MESSAGE,
                errorEvent.getMessage()
        );

        // 脱敏：不能把 HTTP 原始报文/request_id 推给前端。
        assertFalse(errorEvent.getMessage().contains("InvalidParameter"));
        assertFalse(errorEvent.getMessage().contains("request_id"));

        // 同步返回值同样不应泄露原始报文。
        assertTrue(result.startsWith("执行错误："));
        assertFalse(result.contains("InvalidParameter"));
    }

    @Test
    @DisplayName("5xx 重试耗尽：调用 3 次后快速失败，文案为“稍后重试”")
    void transientExhaustedFailsFast() {
        ModelRetryProperties properties = new ModelRetryProperties();
        properties.setMaxAttempts(3);
        properties.setInitialBackoff(Duration.ofMillis(5));
        properties.setMultiplier(1.0);
        properties.setMaxBackoff(Duration.ofMillis(5));

        RetryTemplate retryTemplate = new ModelRetryConfig().retryTemplate(properties);
        AtomicInteger calls = new AtomicInteger();

        /*
         * 模拟真实链路：重试发生在 ChatModel 内部（DashScopeChatModel 持有 RetryTemplate），
         * Agent 看到的永远是"重试已经结束"的异常。
         */
        ChatModel retryingModel = prompt -> retryTemplate.execute(context -> {
            calls.incrementAndGet();
            throw new TransientAiException("HTTP 503 - Service Unavailable");
        });

        ToolCallAgent agent = newAgent(retryingModel, Duration.ofSeconds(30));
        AgentRunContext ctx = new AgentRunContext();
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        ctx.setEmitter(emitter);

        long start = System.currentTimeMillis();
        agent.run("帮我总结一下这份文档", ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(3, calls.get(), "可重试错误应重试到 max-attempts");
        assertEquals(AgentState.FAILED, ctx.getState());
        assertTrue(elapsed < 5_000, "重试耗尽后应立即失败，实际耗时=" + elapsed + "ms");

        AgentEvent errorEvent = singleEvent(emitter, AgentEventType.ERROR);
        assertEquals(
                ToolCallAgent.MODEL_UNAVAILABLE_MESSAGE,
                errorEvent.getMessage()
        );
    }

    @Test
    @DisplayName("非模型类异常保持原有传播语义（不伪装成模型失败）")
    void otherExceptionsKeepGenericMessage() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel brokenModel = prompt -> {
            calls.incrementAndGet();
            throw new IllegalStateException("内部状态异常");
        };

        ToolCallAgent agent = newAgent(brokenModel, Duration.ofSeconds(30));
        AgentRunContext ctx = new AgentRunContext();
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        ctx.setEmitter(emitter);

        agent.run("随便聊聊", ctx);

        assertEquals(1, calls.get());
        assertEquals(AgentState.FAILED, ctx.getState());

        /*
         * 非模型类异常不应被伪装成"模型服务调用失败"：
         * 同步 run() 路径本来就不推送通用 ERROR 事件（那是 runStream 的职责），
         * 这里断言没有产生任何"模型失败"专属事件。
         */
        assertTrue(
                emitter.events.stream()
                        .noneMatch(event -> AgentEventType.ERROR.wireName().equals(event.getType())),
                "非模型异常不应触发模型失败 ERROR 事件"
        );
    }

    @Test
    @DisplayName("未包装成 AiException 的 HTTP 400 同样快速失败（地址/参数配错）")
    void rawHttpClientErrorAlsoFailsFast() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel failingModel = prompt -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    HttpHeaders.EMPTY,
                    "{\"code\":\"InvalidParameter\"}".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8
            );
        };

        ToolCallAgent agent = newAgent(failingModel, Duration.ofSeconds(30));
        AgentRunContext ctx = new AgentRunContext();
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        ctx.setEmitter(emitter);

        long start = System.currentTimeMillis();
        agent.run("帮我规划一次上海三日游", ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, calls.get());
        assertEquals(AgentState.FAILED, ctx.getState());
        assertTrue(elapsed < 5_000);

        AgentEvent errorEvent = singleEvent(emitter, AgentEventType.ERROR);
        assertEquals(
                ToolCallAgent.MODEL_CONFIG_FAILED_MESSAGE,
                errorEvent.getMessage()
        );
    }

    private ToolCallAgent newAgent(ChatModel chatModel, Duration timeout) {
        ToolCallAgent agent = new ToolCallAgent(
                chatModel,
                new ToolCallback[0],
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(3);
        agent.setMaxToolCalls(3);
        agent.setTimeout(timeout);
        return agent;
    }

    private AgentEvent singleEvent(RecordingSseEmitter emitter, AgentEventType type) {
        List<AgentEvent> matched = emitter.events.stream()
                .filter(event -> type.wireName().equals(event.getType()))
                .toList();

        assertFalse(matched.isEmpty(), "应收到 " + type.wireName() + " 事件");
        assertEquals(1, matched.size(), "同一类事件应只出现一次");
        return matched.get(0);
    }

    /**
     * 记录 SSE 事件的 emitter 替身（不建立真实连接）。
     */
    static class RecordingSseEmitter extends SseEmitter {

        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) {
            builder.build().forEach(data -> {
                Object payload = data.getData();
                if (payload instanceof AgentEvent event) {
                    events.add(event);
                }
            });
        }

        @Override
        public synchronized void send(Object object) {
            if (object instanceof AgentEvent event) {
                events.add(event);
            }
        }

        @Override
        public synchronized void complete() {
            // 不触发真实连接生命周期回调，避免测试里误改任务状态。
        }
    }

    @Test
    @DisplayName("RecordingSseEmitter 自检：未发送事件时列表为空")
    void emitterStartsEmpty() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        assertNotNull(emitter.events);
        assertTrue(emitter.events.isEmpty());
    }
}
