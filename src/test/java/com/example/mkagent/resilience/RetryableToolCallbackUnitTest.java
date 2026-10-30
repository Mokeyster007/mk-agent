package com.example.mkagent.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具重试单元测试（不启动 Spring 上下文）。
 *
 * 覆盖用户要求的验证点：
 * 1. 可重试工具在临时失败（抛异常 / 返回失败文案）后重试成功；
 * 2. 重试次数有限，耗尽后抛出最终失败原因，绝不无限重试；
 * 3. 不在白名单的高风险工具不会被包装，
 *    通过真实 ToolCallingManager 执行时恰好执行一次，不被自动重复执行。
 */
class RetryableToolCallbackUnitTest {

    /**
     * 与生产一致的重试配置：白名单仅 web_search / web_scrape。
     */
    private ToolRetryWrapper wrapper;

    @BeforeEach
    void setUp() {
        RetryableToolCallback.resetGlobalRetryCount();
        wrapper = new ToolRetryWrapper(
                true,
                3,
                1,
                2.0,
                "web_search,web_scrape",
                "web_search:搜索工具执行失败,web_scrape:网页抓取失败"
        );
    }

    @Test
    void retryOnExceptionThenSucceed() {
        AtomicInteger delegateCalls = new AtomicInteger(0);

        ToolCallback flaky = simpleCallback("web_search", input -> {
            int call = delegateCalls.incrementAndGet();
            if (call <= 2) {
                throw new RuntimeException("模拟网络超时");
            }
            return "搜索结果：Spring AI";
        });

        ToolCallback retryable = wrapper.wrapIfNeeded(flaky);
        assertTrue(retryable instanceof RetryableToolCallback,
                "白名单内工具应被包装为可重试工具");

        String result = retryable.call("{}");

        assertEquals("搜索结果：Spring AI", result);
        assertEquals(3, delegateCalls.get(),
                "前 2 次失败 + 第 3 次成功，应恰好调用 3 次");
        assertEquals(2, ((RetryableToolCallback) retryable).getRetryCount(),
                "应记录 2 次重试");
        assertEquals(2, RetryableToolCallback.getGlobalRetryCount());
    }

    @Test
    void retryOnFailureMarkerTextThenSucceed() {
        // 与生产 WebSearchTool 相同的行为：内部吞异常返回失败文案。
        // 注意：MethodToolCallback 会把返回值序列化为 JSON 字符串
        // （外层带双引号），重试识别必须能在其中匹配到失败关键词。
        FlakySearchTool flakyTool = new FlakySearchTool(1);
        ToolCallback callback = ToolCallbacks.from(flakyTool)[0];

        ToolCallback retryable = wrapper.wrapIfNeeded(callback);
        String result = retryable.call("{\"query\":\"Spring AI\"}");

        assertTrue(result.contains("搜索结果"),
                "重试成功后应返回真实结果，实际：" + result);
        assertEquals(2, flakyTool.getCallCount(),
                "失败文案应触发重试：第 1 次失败 + 第 2 次成功");
    }

    /**
     * 默认行为（on-exhausted=return）：耗尽后**不抛异常**，
     * 而是把一条脱敏的失败文案作为工具结果交还给模型。
     *
     * 这正是真实运行里 web_scrape 因 GitHub 超时重试耗尽的场景：
     * 旧实现会抛异常，把整个任务判成 FAILED；
     * 新实现让模型看到"这条路走不通"，自己换地址或收尾。
     */
    @Test
    void exhaustedRetriesReturnFailureMessageInsteadOfThrowing() {
        AtomicInteger delegateCalls = new AtomicInteger(0);

        ToolCallback alwaysFail = simpleCallback("web_scrape", input -> {
            delegateCalls.incrementAndGet();
            throw new RuntimeException("Read timed out");
        });

        ToolCallback retryable = wrapper.wrapIfNeeded(alwaysFail);

        // 关键断言：不抛异常，正常返回。
        String result = retryable.call("{}");

        assertTrue(result.contains("web_scrape"),
                "失败文案必须点明是哪个工具失败，实际：" + result);
        assertTrue(result.contains("多次尝试仍失败"),
                "失败文案必须说明重试已耗尽，实际：" + result);
        assertTrue(result.contains("Read timed out"),
                "失败文案必须带简短原因，实际：" + result);
        assertTrue(result.contains("terminate_task"),
                "失败文案必须给出下一步选项（换方式 / 收尾），实际：" + result);

        assertEquals(3, delegateCalls.get(),
                "maxAttempts=3 后必须停止，绝不无限重试");
        assertEquals(3, ((RetryableToolCallback) retryable).getRetryCount());
    }

    /**
     * on-exhausted=throw：保留旧行为（向上抛异常，任务 FAILED），
     * 用于需要回滚时通过配置切回。
     */
    @Test
    void exhaustedRetriesThrowFinalFailureInThrowMode() {
        AtomicInteger delegateCalls = new AtomicInteger(0);

        ToolCallback alwaysFail = simpleCallback("web_search", input -> {
            delegateCalls.incrementAndGet();
            throw new RuntimeException("持续故障");
        });

        ToolCallback retryable = throwModeWrapper().wrapIfNeeded(alwaysFail);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> retryable.call("{}")
        );

        assertEquals("持续故障", thrown.getMessage(),
                "throw 模式下重试耗尽应抛出最终失败原因");
        assertEquals(3, delegateCalls.get(),
                "maxAttempts=3 后必须停止，绝不无限重试");
    }

    /**
     * 耗尽文案本身必须是单行、限长的：
     * 它要进模型上下文，堆栈刷进去既贵又没用。
     */
    @Test
    void exhaustedMessageIsSingleLineAndBounded() {
        ToolCallback alwaysFail = simpleCallback("web_scrape", input -> {
            // 模拟一个带换行的多行异常信息
            throw new RuntimeException(
                    "第一行\n第二行\n" + "x".repeat(500)
            );
        });

        String result = wrapper.wrapIfNeeded(alwaysFail).call("{}");

        assertFalse(result.contains("\n"),
                "耗尽文案必须折叠为单行，实际：" + result);
        assertTrue(result.length() < 400,
                "耗尽文案必须限长，实际长度：" + result.length());
    }

    /**
     * 失败文案经 JSON 字符串序列化（带引号、转义 \n）后，
     * 仍然必须被识别为失败并触发重试。
     */
    @Test
    void exhaustedDetectionWorksOnJsonEncodedFailureText() {
        FlakySearchTool flakyTool = new FlakySearchTool(99);
        ToolCallback callback = ToolCallbacks.from(flakyTool)[0];

        String result = wrapper.wrapIfNeeded(callback).call("{\"query\":\"x\"}");

        assertTrue(result.contains("多次尝试仍失败"),
                "失败文案（内部被序列化为带引号字符串）也应被识别为失败并最终耗尽，实际：" + result);
        assertEquals(3, flakyTool.getCallCount(),
                "三次尝试后停止");
    }

    /**
     * 构造 on-exhausted=throw 的包装器。
     */
    private ToolRetryWrapper throwModeWrapper() {
        return new ToolRetryWrapper(
                true,
                3,
                1,
                2.0,
                "web_search,web_scrape",
                "web_search:搜索工具执行失败,web_scrape:网页抓取失败",
                "throw"
        );
    }

    @Test
    void highRiskToolIsNeverWrapped() {
        FailingFileTool fileTool = new FailingFileTool();
        ToolCallback callback = ToolCallbacks.from(fileTool)[0];

        ToolCallback afterWrapper = wrapper.wrapIfNeeded(callback);

        // 不在白名单：原样返回，绝不获得自动重试能力。
        assertSame(callback, afterWrapper,
                "高风险工具（文件写入）不允许被重试包装");
        assertFalse(afterWrapper instanceof RetryableToolCallback);
    }

    /**
     * 框架级验证：不可重试工具通过真实的 ToolCallingManager 执行，
     * 失败后恰好执行一次，不会被自动重复执行。
     */
    @Test
    void nonRetryableToolExecutedExactlyOnceThroughToolCallingManager() {
        FailingFileTool fileTool = new FailingFileTool();
        ToolCallback callback = ToolCallbacks.from(fileTool)[0];
        ToolCallback afterWrapper = wrapper.wrapIfNeeded(callback);

        /*
         * DefaultToolCallingManager 需要通过 ToolCallbackResolver 查找回调，
         * 用 StaticToolCallbackResolver 注册本次测试的工具回调。
         */
        ToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(
                        new StaticToolCallbackResolver(List.of(afterWrapper))
                )
                .build();

        AssistantMessage toolCallMessage = new AssistantMessage(
                "",
                Map.of(),
                List.of(new AssistantMessage.ToolCall(
                        "call_1",
                        "function",
                        "file_write_demo",
                        "{\"path\":\"a.txt\",\"content\":\"x\"}"
                ))
        );

        ChatResponse response = new ChatResponse(
                List.of(new Generation(toolCallMessage))
        );

        Prompt prompt = new Prompt(List.of(new UserMessage("写文件")));

        ToolExecutionResult executionResult =
                manager.executeToolCalls(prompt, response);

        assertEquals(1, fileTool.getCallCount(),
                "不可重试工具失败后不应被重复执行");

        // 失败结果仍会写回对话历史，模型可以感知失败并自行决策。
        List<Message> history = executionResult.conversationHistory();
        Message last = history.get(history.size() - 1);
        assertTrue(last instanceof ToolResponseMessage,
                "工具失败结果应以 ToolResponseMessage 写回上下文");
        ToolResponseMessage toolResponse = (ToolResponseMessage) last;
        assertTrue(
                toolResponse.getResponses().get(0).responseData()
                        .contains("文件写入失败"),
                "模型应能看到失败原因"
        );
    }

    // ===== 测试专用工具 =====

    /**
     * 构造指定名称、行为可控的最小 ToolCallback。
     */
    private ToolCallback simpleCallback(
            String name,
            java.util.function.Function<String, String> behavior
    ) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("测试工具")
                .inputSchema("{\"type\":\"object\"}")
                .build();

        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return behavior.apply(toolInput);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return behavior.apply(toolInput);
            }
        };
    }

    /**
     * 模拟生产 WebSearchTool 的失败模式：
     * 前 failTimes 次调用吞掉异常并返回失败文案。
     */
    public static class FlakySearchTool {

        private final int failTimes;

        private final AtomicInteger callCount = new AtomicInteger(0);

        public FlakySearchTool(int failTimes) {
            this.failTimes = failTimes;
        }

        @Tool(
                name = "web_search",
                description = "测试用搜索工具"
        )
        public String search(
                @ToolParam(description = "关键词") String query
        ) {
            int call = callCount.incrementAndGet();
            if (call <= failTimes) {
                return "搜索工具执行失败：模拟网络超时";
            }
            return "搜索结果：" + query;
        }

        public int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * 模拟高风险文件写入工具：失败时恰好执行一次，绝不能重试。
     */
    public static class FailingFileTool {

        private final AtomicInteger callCount = new AtomicInteger(0);

        @Tool(
                name = "file_write_demo",
                description = "测试用文件写入工具（有副作用，禁止重试）"
        )
        public String writeFile(
                @ToolParam(description = "文件路径") String path,
                @ToolParam(description = "内容") String content
        ) {
            callCount.incrementAndGet();
            return "文件写入失败：磁盘已满";
        }

        public int getCallCount() {
            return callCount.get();
        }
    }
}
