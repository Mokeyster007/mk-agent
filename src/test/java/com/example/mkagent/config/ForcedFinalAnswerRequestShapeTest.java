package com.example.mkagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.mkagent.agent.ToolCallAgent;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 收尾调用"出站请求形状"的验证：**看真正发出去的 JSON**，不看本地 builder 字段。
 *
 * 为什么必须做到 HTTP 这一层：
 * 本地把 Prompt 的 toolCallbacks 置空**不等于**请求里没有工具。
 * DashScopeChatModel#buildRequestPrompt 在合并选项后会执行
 * mergeToolCallbacks(runtime, defaultOptions) / mergeToolNames(...)，
 * 而这两个方法的语义是"runtime 为空就返回 defaults"。
 * 只断言 Prompt.getOptions() 的测试会在默认选项带工具时**静默通过**，
 * 真实请求却把工具带了回去——这正是本次要锁住的地方。
 *
 * 全程不触网：MockRestServiceServer 绑住 RestClient.Builder。
 */
@Timeout(60)
class ForcedFinalAnswerRequestShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DASHSCOPE_BASE_URL = "https://dashscope.invalid";

    private static final String TOOL_NAME = "counter";

    /**
     * DashScope 的 tool_call 响应（字段名来自 DashScopeApi 的记录类）。
     */
    private static final String DASHSCOPE_TOOL_CALL = """
            {
              "request_id": "req-1",
              "output": {
                "choices": [{
                  "finish_reason": "tool_calls",
                  "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [{
                      "id": "call_1",
                      "type": "function",
                      "function": {"name": "counter", "arguments": "{}"}
                    }]
                  }
                }]
              },
              "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
            }
            """;

    private static final String DASHSCOPE_TEXT = """
            {
              "request_id": "req-2",
              "output": {
                "choices": [{
                  "finish_reason": "stop",
                  "message": {"role": "assistant", "content": "收尾文本"}
                }]
              },
              "usage": {"input_tokens": 7, "output_tokens": 3, "total_tokens": 10}
            }
            """;

    private static final String OPENAI_TOOL_CALL = """
            {
              "id": "cmpl-1",
              "object": "chat.completion",
              "created": 1,
              "model": "qwen-plus",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "counter", "arguments": "{}"}
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
            """;

    private static final String OPENAI_TEXT = """
            {
              "id": "cmpl-2",
              "object": "chat.completion",
              "created": 2,
              "model": "qwen-plus",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "收尾文本"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}
            }
            """;

    // ==================== 1. DashScope ====================

    @Test
    @DisplayName("DashScope：常规轮带 tools，收尾调用不带 tools（看真实请求体）")
    void dashScopeForcedCallCarriesNoTools() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        AtomicReference<String> toolRound = new AtomicReference<>();
        AtomicReference<String> forcedRound = new AtomicReference<>();

        server.expect(ExpectedCount.once(), contains("/text-generation"))
                .andExpect(capture(toolRound))
                .andRespond(withSuccess(DASHSCOPE_TOOL_CALL, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), contains("/text-generation"))
                .andExpect(capture(forcedRound))
                .andRespond(withSuccess(DASHSCOPE_TEXT, MediaType.APPLICATION_JSON));

        DashScopeChatModel model = dashScopeModel(builder, null);
        AgentRunContext ctx = runOneToolRoundThenExhaust(model);

        server.verify();

        // 对照组：常规轮必须真的带了工具，否则"收尾没带"是假通过。
        assertThat(toolsOf(toolRound.get()))
                .as("常规工具轮的请求体必须包含 tools")
                .hasSize(1);
        assertThat(toolsOf(toolRound.get()).get(0).path("function").path("name").asText())
                .isEqualTo(TOOL_NAME);

        // 收尾轮：没有 tools、没有 tool_choice。
        assertThat(toolsOf(forcedRound.get()))
                .as("收尾调用不得携带任何工具定义，实际请求体：%s", forcedRound.get())
                .isNullOrEmpty();
        assertThat(parametersOf(forcedRound.get()).path("tool_choice").isMissingNode()
                || parametersOf(forcedRound.get()).path("tool_choice").isNull())
                .as("收尾调用不得携带 tool_choice")
                .isTrue();

        // 收尾轮最后一条消息是 system 指令（显式消解 terminate_task 冲突）。
        JsonNode messages = forcedRound.get() == null
                ? null
                : JSON.readTree(forcedRound.get()).path("input").path("messages");
        assertThat(messages).isNotNull();
        assertThat(messages.get(messages.size() - 1).path("role").asText())
                .isEqualTo("system");
        assertThat(messages.get(messages.size() - 1).path("content").asText())
                .contains("terminate_task")
                .contains("不适用");

        assertThat(ctx.getState()).isEqualTo(AgentState.MAX_STEPS_REACHED);
        assertThat(ctx.getFinalAnswer()).contains("收尾文本");
    }

    @Test
    @DisplayName("DashScope：defaultOptions 带工具时，收尾调用会被合并回工具（当前行为刻画）")
    void dashScopeMergesDefaultToolsBackIntoForcedCall() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        AtomicReference<String> toolRound = new AtomicReference<>();
        AtomicReference<String> forcedRound = new AtomicReference<>();

        server.expect(ExpectedCount.once(), contains("/text-generation"))
                .andExpect(capture(toolRound))
                .andRespond(withSuccess(DASHSCOPE_TOOL_CALL, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), contains("/text-generation"))
                .andExpect(capture(forcedRound))
                .andRespond(withSuccess(DASHSCOPE_TEXT, MediaType.APPLICATION_JSON));

        DashScopeChatOptions defaults = DashScopeChatOptions.builder()
                .withModel("qwen-plus")
                .withToolCallbacks(java.util.List.of(counterCallback()))
                .build();

        DashScopeChatModel model = dashScopeModel(builder, defaults);
        runOneToolRoundThenExhaust(model);

        server.verify();

        /*
         * 这条断言刻画的是**当前依赖版本的真实行为**：
         * 收尾调用明明没有传 toolCallbacks，工具仍然出现在请求里。
         * 所以"在 Prompt 侧置空"不构成保证——保证必须来自默认选项为空。
         */
        assertThat(toolsOf(forcedRound.get()))
                .as("默认选项带工具时，收尾调用仍会带上工具（mergeToolCallbacks 回退到 defaults）")
                .hasSize(1);
    }

    @Test
    @DisplayName("DashScope：默认选项的 tools/toolChoice 同样会漏进收尾请求（当前行为刻画）")
    void dashScopeMergesDefaultToolsAndToolChoiceBackIntoForcedCall() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        AtomicReference<String> forcedRound = new AtomicReference<>();

        server.expect(ExpectedCount.once(), contains("/text-generation"))
                .andRespond(withSuccess(DASHSCOPE_TOOL_CALL, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), contains("/text-generation"))
                .andExpect(capture(forcedRound))
                .andRespond(withSuccess(DASHSCOPE_TEXT, MediaType.APPLICATION_JSON));

        /*
         * 关键：只配 tools / toolChoice，**不配** toolCallbacks 与 toolNames。
         * 如果"清空 callbacks/names"就够了，这条断言会失败——
         * 它证明 DashScopeChatOptions 里另外两个字段同样会进请求。
         */
        DashScopeChatOptions defaults = DashScopeChatOptions.builder()
                .withModel("qwen-plus")
                .withTools(java.util.List.of(functionTool()))
                .withToolChoice("required")
                .build();

        runOneToolRoundThenExhaust(dashScopeModel(builder, defaults));

        server.verify();

        assertThat(toolsOf(forcedRound.get()))
                .as("默认选项的 tools 会被 merge 进收尾请求，实际请求体：%s", forcedRound.get())
                .hasSize(1);
        assertThat(parametersOf(forcedRound.get()).path("tool_choice").asText())
                .as("默认选项的 toolChoice 也会进收尾请求")
                .isEqualTo("required");
    }

    @Test
    @DisplayName("DashScope：stripDefaultTools 之后，tools/toolChoice 也不再进收尾请求")
    void strippedDefaultsLeaveForcedCallWithoutToolsOrChoice() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        AtomicReference<String> forcedRound = new AtomicReference<>();

        server.expect(ExpectedCount.once(), contains("/text-generation"))
                .andRespond(withSuccess(DASHSCOPE_TOOL_CALL, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), contains("/text-generation"))
                .andExpect(capture(forcedRound))
                .andRespond(withSuccess(DASHSCOPE_TEXT, MediaType.APPLICATION_JSON));

        DashScopeChatOptions defaults = DashScopeChatOptions.builder()
                .withModel("qwen-plus")
                .withTools(java.util.List.of(functionTool()))
                .withToolChoice("required")
                .build();
        DashScopeChatModelConfig.stripDefaultTools(defaults);

        runOneToolRoundThenExhaust(dashScopeModel(builder, defaults));

        server.verify();

        assertThat(toolsOf(forcedRound.get()))
                .as("清空后收尾请求不得再带 tools，实际请求体：%s", forcedRound.get())
                .isNullOrEmpty();
        assertThat(parametersOf(forcedRound.get()).path("tool_choice").isMissingNode()
                || parametersOf(forcedRound.get()).path("tool_choice").isNull())
                .as("清空后收尾请求不得再带 tool_choice")
                .isTrue();
    }

    @Test
    @DisplayName("配置层 stripDefaultTools 后，四个工具字段全部清空且其它默认选项不变")
    void stripDefaultToolsRemovesConfiguredTools() {
        /*
         * 四个与工具相关的字段一起配，验证"只清 callbacks/names 不够"：
         * tools / toolChoice 也会被 merge 进请求（见方法注释与 COST.md 6.5）。
         */
        DashScopeChatOptions defaults = DashScopeChatOptions.builder()
                .withModel("qwen-plus")
                .withTemperature(0.3)
                .withMaxToken(512)
                .withToolCallbacks(java.util.List.of(counterCallback()))
                .withToolName(TOOL_NAME)
                .withTools(java.util.List.of(
                        new com.alibaba.cloud.ai.dashscope.api.DashScopeApi.FunctionTool(
                                new com.alibaba.cloud.ai.dashscope.api.DashScopeApi
                                        .FunctionTool.Function(
                                        TOOL_NAME, "计数", java.util.Map.of()))))
                .withToolChoice("required")
                .build();

        DashScopeChatModelConfig.stripDefaultTools(defaults);

        assertThat(defaults.getToolCallbacks()).isEmpty();
        assertThat(defaults.getToolNames()).isEmpty();
        assertThat(defaults.getTools())
                .as("已解析的 FunctionTool 列表也会进请求，必须一起清")
                .isNull();
        assertThat(defaults.getToolChoice())
                .as("toolChoice 无工具时没有意义，且会原样进请求")
                .isNull();

        assertThat(defaults.getModel())
                .as("清空工具不得影响模型名")
                .isEqualTo("qwen-plus");
        assertThat(defaults.getTemperature())
                .as("清空工具不得影响 temperature")
                .isEqualTo(0.3);
        assertThat(defaults.getMaxTokens())
                .as("清空工具不得影响 maxTokens")
                .isEqualTo(512);
    }

    @Test
    @DisplayName("stripDefaultTools 对没有工具配置的默认选项是空操作")
    void stripDefaultToolsIsNoOpWhenNothingConfigured() {
        DashScopeChatOptions defaults = DashScopeChatOptions.builder()
                .withModel("qwen-plus")
                .build();

        DashScopeChatModelConfig.stripDefaultTools(defaults);

        assertThat(defaults.getToolCallbacks()).isEmpty();
        assertThat(defaults.getToolNames()).isEmpty();
        assertThat(defaults.getTools()).isNull();
        assertThat(defaults.getToolChoice()).isNull();
        assertThat(defaults.getModel()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("装配层：properties 配了 tool-names，最终模型的默认选项里也必须是空的")
    void contextStripsToolsConfiguredThroughProperties() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();

        new ApplicationContextRunner()
                .withUserConfiguration(
                        DashScopeChatModelConfig.class,
                        ModelRetryConfig.class,
                        RetryPropertiesConfig.class
                )
                .withConfiguration(AutoConfigurations.of(
                        SpringAiRetryAutoConfiguration.class,
                        DashScopeChatAutoConfiguration.class
                ))
                .withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build())
                .withBean(RestClient.Builder.class, () -> builder)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .withPropertyValues(
                        "spring.ai.dashscope.api-key=test-api-key",
                        "spring.ai.dashscope.chat.options.model=qwen-plus",
                        "spring.ai.dashscope.chat.options.tool-names=" + TOOL_NAME
                )
                .run(context -> {
                    DashScopeChatModel model = context.getBean(DashScopeChatModel.class);
                    DashScopeChatOptions options =
                            (DashScopeChatOptions) model.getDefaultOptions();

                    assertThat(options.getToolNames())
                            .as("默认选项里的工具必须在装配时被清空")
                            .isEmpty();
                    assertThat(options.getToolCallbacks()).isEmpty();
                });
    }

    // ==================== 2. OpenAI 兼容 ====================

    @Test
    @DisplayName("OpenAI 兼容：常规轮带 tools，收尾调用不带 tools（看真实请求体）")
    void openAiCompatibleForcedCallCarriesNoTools() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        AtomicReference<String> toolRound = new AtomicReference<>();
        AtomicReference<String> forcedRound = new AtomicReference<>();

        server.expect(ExpectedCount.once(), contains("/chat/completions"))
                .andExpect(capture(toolRound))
                .andRespond(withSuccess(OPENAI_TOOL_CALL, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), contains("/chat/completions"))
                .andExpect(capture(forcedRound))
                .andRespond(withSuccess(OPENAI_TEXT, MediaType.APPLICATION_JSON));

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://openai-compatible.invalid")
                .apiKey("test-key")
                .restClientBuilder(builder)
                .webClientBuilder(WebClient.builder())
                .build();

        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("qwen-plus")
                        .temperature(0.0)
                        .build())
                .retryTemplate(new RetryTemplate())
                .toolCallingManager(ToolCallingManager.builder().build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        AgentRunContext ctx = runOneToolRoundThenExhaust(model);

        server.verify();

        assertThat(toolsOf(toolRound.get()))
                .as("常规工具轮的请求体必须包含 tools")
                .hasSize(1);
        assertThat(toolsOf(forcedRound.get()))
                .as("收尾调用不得携带任何工具定义，实际请求体：%s", forcedRound.get())
                .isNullOrEmpty();

        assertThat(ctx.getState()).isEqualTo(AgentState.MAX_STEPS_REACHED);
        assertThat(ctx.getFinalAnswer()).contains("收尾文本");
    }

    // ==================== 辅助 ====================

    /**
     * 跑一轮工具调用直到预算耗尽：第 1 步调工具，第 2 次（收尾）走无工具分支。
     */
    private AgentRunContext runOneToolRoundThenExhaust(
            org.springframework.ai.chat.model.ChatModel model
    ) {
        ToolCallback[] tools = MethodToolCallbackProvider.builder()
                .toolObjects(new CounterTool())
                .build()
                .getToolCallbacks();

        ToolCallAgent agent = new ToolCallAgent(
                model,
                tools,
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("request-shape-agent");
        agent.setSystemPrompt("你是测试用智能体。");
        agent.setMaxSteps(1);
        agent.setMaxToolCalls(1);
        agent.setTimeout(java.time.Duration.ofSeconds(30));

        AgentRunContext ctx = new AgentRunContext();
        agent.run("随便问点什么", ctx);
        return ctx;
    }

    private DashScopeChatModel dashScopeModel(
            RestClient.Builder builder,
            DashScopeChatOptions defaults
    ) {
        DashScopeApiFactory factory = new DashScopeApiFactory(builder);
        return factory.build(defaults);
    }

    /**
     * 只为了把 DashScopeApi 的构建参数收在一处。
     */
    private static final class DashScopeApiFactory {

        private final RestClient.Builder builder;

        private DashScopeApiFactory(RestClient.Builder builder) {
            this.builder = builder;
        }

        private DashScopeChatModel build(DashScopeChatOptions defaults) {
            com.alibaba.cloud.ai.dashscope.api.DashScopeApi api =
                    com.alibaba.cloud.ai.dashscope.api.DashScopeApi.builder()
                            .apiKey("test-api-key")
                            .baseUrl(DASHSCOPE_BASE_URL)
                            .restClientBuilder(builder)
                            .webClientBuilder(WebClient.builder())
                            .build();

            return DashScopeChatModel.builder()
                    .dashScopeApi(api)
                    .defaultOptions(defaults == null
                            ? DashScopeChatOptions.builder()
                                    .withModel("qwen-plus")
                                    .build()
                            : defaults)
                    .retryTemplate(new RetryTemplate())
                    .toolCallingManager(ToolCallingManager.builder().build())
                    .observationRegistry(ObservationRegistry.NOOP)
                    .build();
        }
    }

    private static JsonNode parametersOf(String body) throws Exception {
        return JSON.readTree(body).path("parameters");
    }

    /**
     * 一个已解析好的 DashScope 工具定义（用于验证 tools 字段的合并行为）。
     */
    private static com.alibaba.cloud.ai.dashscope.api.DashScopeApi.FunctionTool functionTool() {
        return new com.alibaba.cloud.ai.dashscope.api.DashScopeApi.FunctionTool(
                new com.alibaba.cloud.ai.dashscope.api.DashScopeApi.FunctionTool.Function(
                        TOOL_NAME, "计数", java.util.Map.of()));
    }

    /**
     * 取请求体里的工具数组；缺失/为 null 时返回 null，便于断言"没有工具"。
     */
    private static com.fasterxml.jackson.databind.node.ArrayNode toolsOf(String body)
            throws Exception {
        if (body == null) {
            return null;
        }
        JsonNode root = JSON.readTree(body);
        JsonNode tools = root.has("parameters")
                ? root.path("parameters").path("tools")
                : root.path("tools");
        if (tools.isMissingNode() || tools.isNull()) {
            return null;
        }
        return tools.isArray() ? (com.fasterxml.jackson.databind.node.ArrayNode) tools : null;
    }

    private static RequestMatcher contains(String fragment) {
        return requestTo(org.hamcrest.Matchers.containsString(fragment));
    }

    /**
     * 把真实请求体抓出来：断言的是出站 JSON，而不是本地对象字段。
     */
    private static RequestMatcher capture(AtomicReference<String> sink) {
        return request -> {
            if (request instanceof MockClientHttpRequest mock) {
                sink.set(mock.getBodyAsString());
            }
        };
    }

    private static ToolCallback counterCallback() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new CounterTool())
                .build()
                .getToolCallbacks()[0];
    }

    /**
     * 测试用工具：被调用时返回固定文本。
     */
    public static final class CounterTool {

        @Tool(name = TOOL_NAME, description = "计数")
        public String count() {
            return "ok";
        }
    }

    /**
     * 让 mkagent.model-retry.* 可绑定。
     */
    @Configuration
    @EnableConfigurationProperties(ModelRetryProperties.class)
    static class RetryPropertiesConfig {
    }
}
