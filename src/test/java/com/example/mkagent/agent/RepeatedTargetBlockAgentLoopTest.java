package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.resilience.ToolRetryWrapper;
import com.example.mkagent.resilience.ToolTargetExhaustionTracker;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import com.example.mkagent.tools.ToolResultMarkers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点级熔断在真实 Agent Loop 中的行为（桩测试，不联网、不启动 Spring）。
 *
 * 这组测试的重点不是重试逻辑本身，而是**上下文传递链路**：
 * 计数器存在 AgentRunContext 里，工具回调是单例，
 * 两者只能通过 ToolCallAgent.act() 在执行工具期间的线程绑定连起来
 * （ToolTargetExhaustionTracker.bind / unbind）。
 * 这条链路任何一环断了（比如忘了 finally 解除、或 bind 位置放错），
 * 熔断在生产上都会静默失效——所以必须用"真的跑一遍 Agent Loop"来验证。
 */
class RepeatedTargetBlockAgentLoopTest {

    private static final String TOOL_NAME = "web_scrape";

    @Test
    @DisplayName("同一站点连续耗尽后，第 3 轮不再执行工具，模型看到熔断文案并收尾")
    void repeatedFailureOnSameHostStopsExecutionAndModelWrapsUp() {
        StubScrapeTool scrapeTool = new StubScrapeTool();
        ScriptedChatModel model = new ScriptedChatModel(
                List.of(
                        "https://github.com/a",
                        "https://github.com/b",
                        "https://github.com/c"
                )
        );

        ToolCallAgent agent = newAgent(model, wrap(scrapeTool, 2));
        AgentRunContext ctx = new AgentRunContext();

        agent.run("把 https://github.com 的文档总结一下", ctx);

        /*
         * 1. 底层工具只被调用 4 次：前两轮各 maxAttempts=2 次，
         *    第 3 轮被熔断、一次都没执行。
         */
        assertEquals(4, scrapeTool.callCount.get(),
                "第 3 轮不得再执行底层工具，实际 URL 序列：" + scrapeTool.urls);

        // 2. 三轮工具调用都真实发生（熔断那次仍占预算，模型也能看到结果）。
        assertEquals(3, ctx.getToolCallCount(),
                "熔断的调用仍然算一次工具调用，预算口径不变");

        // 3. 模型在第 3 轮之后看到了熔断文案。
        assertNotNull(model.lastToolResults, "模型应已收到工具结果");
        assertTrue(model.lastToolResults.contains(ToolResultMarkers.TARGET_BLOCKED),
                "模型必须看到'该站点连续失败'，实际：" + model.lastToolResults);
        assertTrue(model.lastToolResults.contains("terminate_task"),
                "文案要给出收尾选项，实际：" + model.lastToolResults);

        // 4. 模型据此换方案/收尾，任务正常成功。
        assertEquals(AgentState.SUCCEEDED, ctx.getState(),
                "熔断不得让任务失败，实际终态：" + ctx.getState());
        assertTrue(ctx.getFinalAnswer() != null && !ctx.getFinalAnswer().isBlank());

        // 5. 计数落在"任务级"对象上，且口径正确。
        assertEquals(2,
                ctx.getToolTargetExhaustionTracker()
                        .count("web_scrape|github.com"),
                "两次耗尽应记为 2");

        /*
         * 6. 线程绑定必须在 act() 结束后解除。
         * agent 线程来自线程池，残留绑定会让下一个任务继承上一个任务的计数
         * （表现为"新任务第一次抓取就被熔断"，极难排查）。
         */
        assertNull(ToolTargetExhaustionTracker.current(),
                "act() 结束后必须解除线程绑定，避免污染线程池里的下一个任务");
    }

    @Test
    @DisplayName("不同主机互不影响：前一个站点被熔断，换一个站点仍然真的执行")
    void differentHostIsStillExecuted() {
        StubScrapeTool scrapeTool = new StubScrapeTool();
        ScriptedChatModel model = new ScriptedChatModel(
                List.of(
                        "https://github.com/a",
                        "https://github.com/b",
                        "https://docs.example.com/x"
                )
        );

        ToolCallAgent agent = newAgent(model, wrap(scrapeTool, 2));
        AgentRunContext ctx = new AgentRunContext();

        agent.run("先看 github，再看另一个站点", ctx);

        /*
         * github.com 两轮 × 2 次 = 4 次；
         * docs.example.com 第 3 轮必须真的执行 → 2 次，合计 6 次。
         * 如果熔断错误地按工具名计数，这里会是 4。
         */
        assertEquals(6, scrapeTool.callCount.get(),
                "不同主机必须各自独立，实际 URL 序列：" + scrapeTool.urls);
        assertEquals(2L, scrapeTool.urls.stream()
                        .filter(url -> url.contains("docs.example.com"))
                        .count(),
                "新站点应完整执行 maxAttempts 次");

        assertEquals(AgentState.SUCCEEDED, ctx.getState());
    }

    @Test
    @DisplayName("阈值配置为 0 时关闭熔断：每轮都真的执行")
    void zeroThresholdDisablesBreakerInAgentLoop() {
        StubScrapeTool scrapeTool = new StubScrapeTool();
        ScriptedChatModel model = new ScriptedChatModel(
                List.of(
                        "https://github.com/a",
                        "https://github.com/b",
                        "https://github.com/c"
                )
        );

        ToolCallAgent agent = newAgent(model, wrap(scrapeTool, 0));
        AgentRunContext ctx = new AgentRunContext();

        agent.run("反复抓取同一个站点", ctx);

        assertEquals(6, scrapeTool.callCount.get(),
                "关闭熔断后三轮都应完整执行（3 × 2 次尝试）");
        assertEquals(AgentState.SUCCEEDED, ctx.getState());
    }

    // ===== 测试辅助 =====

    private ToolCallAgent newAgent(ChatModel chatModel, ToolCallback[] tools) {
        ToolCallAgent agent = new ToolCallAgent(
                chatModel,
                tools,
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(8);
        agent.setMaxToolCalls(8);
        agent.setTimeout(Duration.ofSeconds(30));
        return agent;
    }

    /**
     * 用生产同款配置包装工具：maxAttempts=2、退避 1ms、可配置站点熔断阈值。
     */
    private ToolCallback[] wrap(StubScrapeTool tool, int maxExhaustedPerTarget) {
        ToolRetryWrapper wrapper = new ToolRetryWrapper(
                true,
                2,
                1,
                1.0,
                TOOL_NAME,
                "web_scrape:网页抓取失败",
                "return",
                maxExhaustedPerTarget
        );
        return wrapper.wrap(ToolCallbacks.from(tool));
    }

    /**
     * 始终失败的抓取工具，同时记录每次被真正执行的 URL。
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
     * 脚本化 ChatModel：
     * 依次按 urls 列表要求抓取；
     * 一旦在上下文里看到熔断文案，就停止调用工具并给出最终回答。
     */
    private static final class ScriptedChatModel implements ChatModel {

        private final Deque<String> urls;

        private volatile String lastToolResults;

        private ScriptedChatModel(List<String> urls) {
            this.urls = new ArrayDeque<>(urls);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();

            lastToolResults = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(Collectors.joining("\n"));

            boolean blocked = lastToolResults.contains(
                    ToolResultMarkers.TARGET_BLOCKED
            );

            if (blocked || urls.isEmpty()) {
                return textResponse("该站点打不开，已改用其他来源作答。");
            }

            return toolCallResponse(urls.poll());
        }

        private ChatResponse toolCallResponse(String url) {
            AssistantMessage assistantMessage = new AssistantMessage(
                    "",
                    Map.of(),
                    List.of(new AssistantMessage.ToolCall(
                            "call_" + url.hashCode(),
                            "function",
                            TOOL_NAME,
                            "{\"url\":\"" + url + "\"}"
                    ))
            );
            return new ChatResponse(
                    List.of(new Generation(assistantMessage)),
                    ChatResponseMetadata.builder().model("stub-model").build()
            );
        }

        private ChatResponse textResponse(String text) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(text))),
                    ChatResponseMetadata.builder().model("stub-model").build()
            );
        }
    }
}
