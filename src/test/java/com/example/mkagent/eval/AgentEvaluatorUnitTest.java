package com.example.mkagent.eval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentEvaluator 自身单元测试。
 *
 * 全程使用桩 ChatModel 与内存工具，不启动 Spring、不调用真实模型/网络：
 * 1. cases.json 的数量与六类场景覆盖；
 * 2. 确定性检查：工具命中、禁用工具、预算、关键词；
 * 3. 关键词无法判定时使用 LLM-as-judge，且 judge 提示词包含执行轨迹；
 * 4. judge JSON 解析（含 Markdown 代码块）；
 * 5. toolTrace 只记录工具名、不记录参数；
 * 6. Markdown 报告指标与失败清单；
 * 7. 报告落盘与低温度评委包装。
 */
@Timeout(30)
class AgentEvaluatorUnitTest {

    private static final ToolCallback[] TOOL_CALLBACKS =
            ToolCallbacks.from(new FakeTools());

    // ==================== 1. 用例集 ====================

    @Test
    void bundledCasesCoverRequiredCategories() {
        List<EvalCase> cases = new EvalCaseLoader()
                .load("classpath:eval/cases.json");

        assertThat(cases)
                .as("评测用例数量")
                .hasSizeGreaterThanOrEqualTo(30);

        Set<String> ids = cases.stream()
                .map(EvalCase::getId)
                .collect(Collectors.toSet());
        assertThat(ids).hasSameSizeAs(cases);

        Set<String> categories = cases.stream()
                .map(EvalCase::getCategory)
                .collect(Collectors.toSet());
        assertThat(categories).contains(
                "SEARCH",
                "NO_TOOL",
                "URL_SCRAPE",
                "SSRF",
                "PROMPT_INJECTION",
                "INSUFFICIENT_INFO"
        );

        Map<String, Long> categoryCounts = cases.stream()
                .collect(Collectors.groupingBy(
                        EvalCase::getCategory,
                        Collectors.counting()
                ));
        assertThat(categoryCounts)
                .containsEntry("SEARCH", 8L)
                .containsEntry("NO_TOOL", 6L)
                .containsEntry("URL_SCRAPE", 5L)
                .containsEntry("SSRF", 4L)
                .containsEntry("PROMPT_INJECTION", 5L)
                .containsEntry("INSUFFICIENT_INFO", 4L);

        assertThat(cases).anyMatch(evalCase ->
                "SEARCH".equals(evalCase.getCategory())
                        && evalCase.getExpectedTools().contains("web_search"));

        assertThat(cases).anyMatch(evalCase ->
                "NO_TOOL".equals(evalCase.getCategory())
                        && evalCase.getForbiddenTools().contains("web_search"));

        assertThat(cases).anyMatch(evalCase ->
                "URL_SCRAPE".equals(evalCase.getCategory())
                        && evalCase.getExpectedTools().contains("web_scrape"));

        assertThat(cases).anyMatch(evalCase ->
                "SSRF".equals(evalCase.getCategory())
                        && evalCase.getUserPrompt().contains("127.0.0.1"));

        assertThat(cases).anyMatch(evalCase ->
                "PROMPT_INJECTION".equals(evalCase.getCategory())
                        && evalCase.getUserPrompt().contains("IGNORE"));

        assertThat(cases).anyMatch(evalCase ->
                "INSUFFICIENT_INFO".equals(evalCase.getCategory()));
    }

    @Test
    void caseLoaderRejectsOverlappingTools(@TempDir Path tempDir) throws Exception {
        Path caseFile = tempDir.resolve("cases.json");
        Files.writeString(caseFile, """
                [
                  {
                    "id": "BAD-01",
                    "category": "SEARCH",
                    "userPrompt": "测试",
                    "expectedTools": ["web_search"],
                    "forbiddenTools": ["web_search"],
                    "maxSteps": 2,
                    "maxToolCalls": 1,
                    "expectedKeywords": []
                  }
                ]
                """);

        assertThatThrownBy(() -> new EvalCaseLoader().load(caseFile.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突");
    }

    // ==================== 2. 确定性检查 ====================

    @Test
    void searchCasePassesDeterministically() {
        ScriptedChatModel agent = new ScriptedChatModel(prompt -> {
            if (!hasToolResult(prompt)) {
                return toolCallResponse(
                        "call-1",
                        "web_search",
                        "{\"query\":\"Spring AI Tool Calling\"}"
                );
            }
            return textResponse("Spring AI Tool Calling 允许模型请求调用工具。");
        });
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse("{\"pass\":true,\"reason\":\"ok\"}")
        );

        EvalCase evalCase = evalCase(
                "T-SEARCH",
                "搜索 Spring AI Tool Calling 文档",
                List.of("web_search"),
                List.of(),
                4,
                3,
                List.of("Tool"),
                "SEARCH"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isTrue();
        assertThat(result.deterministicPassed()).isTrue();
        assertThat(result.judgeUsed()).isFalse();
        assertThat(result.execution().state()).isEqualTo(AgentState.SUCCEEDED);
        assertThat(result.execution().steps()).isEqualTo(2);
        assertThat(result.execution().toolTrace()).containsExactly("web_search");
        assertThat(result.execution().totalTokens()).isPositive();
        assertThat(result.execution().finalAnswer()).contains("Tool");
    }

    @Test
    void missingExpectedToolFails() {
        ScriptedChatModel agent = new ScriptedChatModel(
                prompt -> textResponse("这是直接回答，没有调用工具。")
        );
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse("{\"pass\":true,\"reason\":\"ok\"}")
        );

        EvalCase evalCase = evalCase(
                "T-MISSING",
                "搜索一个资料",
                List.of("web_search"),
                List.of(),
                2,
                1,
                List.of("直接回答"),
                "SEARCH"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isFalse();
        assertThat(result.judgeUsed()).isFalse();
        assertThat(result.failureReasons())
                .anyMatch(reason -> reason.contains("缺少必须调用的工具"));
    }

    @Test
    void forbiddenToolFails() {
        ScriptedChatModel agent = new ScriptedChatModel(prompt -> {
            if (!hasToolResult(prompt)) {
                return toolCallResponse(
                        "call-1",
                        "web_scrape",
                        "{\"url\":\"https://example.com\"}"
                );
            }
            return textResponse("已尝试抓取网页。");
        });
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse("{\"pass\":true,\"reason\":\"ok\"}")
        );

        EvalCase evalCase = evalCase(
                "T-FORBIDDEN",
                "只做搜索，不要抓取",
                List.of(),
                List.of("web_scrape"),
                3,
                2,
                List.of("抓取"),
                "NO_TOOL"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isFalse();
        assertThat(result.execution().toolTrace()).containsExactly("web_scrape");
        assertThat(result.failureReasons())
                .anyMatch(reason -> reason.contains("调用了禁用工具"));
    }

    @Test
    void stepBudgetExceededFails() {
        ScriptedChatModel agent = new ScriptedChatModel(prompt ->
                toolCallResponse(
                        "call-1",
                        "web_search",
                        "{\"query\":\"一直搜索\"}"
                )
        );
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse("{\"pass\":true,\"reason\":\"ok\"}")
        );

        EvalCase evalCase = evalCase(
                "T-BUDGET",
                "预算测试",
                List.of("web_search"),
                List.of(),
                1,
                5,
                List.of(),
                "SEARCH"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isFalse();
        assertThat(result.execution().state())
                .isEqualTo(AgentState.MAX_STEPS_REACHED);
        assertThat(result.execution().steps()).isEqualTo(1);
        assertThat(result.failureReasons())
                .anyMatch(reason -> reason.contains("终态不是 SUCCEEDED"));
    }

    @Test
    void toolTraceRecordsNamesOnlyInOrder() {
        ScriptedChatModel agent = new ScriptedChatModel(prompt -> {
            int toolResults = toolResultCount(prompt);
            if (toolResults == 0) {
                return toolCallResponse(
                        "call-1",
                        "web_search",
                        "{\"query\":\"secret-token-123\"}"
                );
            }
            if (toolResults == 1) {
                return toolCallResponse(
                        "call-2",
                        "web_search",
                        "{\"query\":\"second-secret\"}"
                );
            }
            return textResponse("搜索完成。");
        });
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse("{\"pass\":true,\"reason\":\"ok\"}")
        );

        EvalCase evalCase = evalCase(
                "T-TRACE",
                "连续搜索两次",
                List.of("web_search"),
                List.of(),
                4,
                4,
                List.of("完成"),
                "SEARCH"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isTrue();
        assertThat(result.execution().toolTrace())
                .containsExactly("web_search", "web_search");
        assertThat(result.execution().toolCallCount()).isEqualTo(2);
        assertThat(result.execution().steps()).isEqualTo(3);

        // 工具参数原文绝不能进入 trace。
        assertThat(result.execution().toolTrace().toString())
                .doesNotContain("secret-token-123")
                .doesNotContain("second-secret");
    }

    // ==================== 3. LLM-as-judge ====================

    @Test
    void usesJudgeWhenKeywordsCannotDecide() {
        ScriptedChatModel agent = new ScriptedChatModel(
                prompt -> textResponse("信息不足，请补充具体项目名称。")
        );
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse(
                        "{\"pass\":true,\"reason\":\"已明确要求补充信息\"}")
        );

        EvalCase evalCase = evalCase(
                "T-JUDGE",
                "帮我查一下那个项目",
                List.of(),
                List.of(),
                2,
                1,
                List.of(),
                "INSUFFICIENT_INFO"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isTrue();
        assertThat(result.judgeUsed()).isTrue();
        assertThat(result.judgePassed()).isTrue();
        assertThat(judge.prompts).isNotEmpty();

        String judgePrompt = judge.prompts.get(0)
                .getInstructions()
                .get(0)
                .getText();

        // 评委提示词必须包含执行轨迹，而不是只看用户问题。
        assertThat(judgePrompt)
                .contains("执行轨迹")
                .contains("工具名序列")
                .contains("最终回答")
                .contains("信息不足");
    }

    @Test
    void judgePromptIncludesActualToolTrace() {
        ScriptedChatModel agent = new ScriptedChatModel(prompt -> {
            if (!hasToolResult(prompt)) {
                return toolCallResponse(
                        "call-1",
                        "web_scrape",
                        "{\"url\":\"https://example.com\"}"
                );
            }
            return textResponse("无法确认页面内容，抓取工具返回失败。");
        });
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse(
                        "{\"pass\":true,\"reason\":\"已如实说明抓取失败\"}")
        );

        EvalCase evalCase = evalCase(
                "T-JUDGE-TRACE",
                "抓取 https://example.com 并总结",
                List.of("web_scrape"),
                List.of("web_search"),
                3,
                2,
                List.of(),
                "URL_SCRAPE"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isTrue();
        assertThat(result.judgeUsed()).isTrue();

        String judgePrompt = judge.prompts.get(0)
                .getInstructions()
                .get(0)
                .getText();

        // 轨迹中的实际工具名必须进入评委提示词。
        assertThat(judgePrompt)
                .contains("web_scrape")
                .contains("工具名序列")
                .contains("无法确认页面内容");
    }

    @Test
    void judgeParsesCodeFencedJson() {
        ScriptedChatModel agent = new ScriptedChatModel(
                prompt -> textResponse("无法确定，请补充信息。")
        );
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse("""
                        ```json
                        {"pass": true, "reason": "已明确拒绝并说明原因"}
                        ```
                        """)
        );

        EvalCase evalCase = evalCase(
                "T-JUDGE-FENCE",
                "信息不足的任务",
                List.of(),
                List.of(),
                2,
                1,
                List.of(),
                "INSUFFICIENT_INFO"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isTrue();
        assertThat(result.judgePassed()).isTrue();
        assertThat(result.judgeReason()).contains("明确拒绝");
    }

    @Test
    void judgeFailureFailsCase() {
        ScriptedChatModel agent = new ScriptedChatModel(
                prompt -> textResponse("随便编一个答案。")
        );
        ScriptedChatModel judge = new ScriptedChatModel(
                prompt -> textResponse(
                        "{\"pass\":false,\"reason\":\"没有拒绝，存在编造\"}")
        );

        EvalCase evalCase = evalCase(
                "T-JUDGE-FAIL",
                "信息不足的任务",
                List.of(),
                List.of(),
                2,
                1,
                List.of(),
                "INSUFFICIENT_INFO"
        );

        EvalResult result = evaluator(agent, judge)
                .evaluate(List.of(evalCase))
                .get(0);

        assertThat(result.passed()).isFalse();
        assertThat(result.judgeUsed()).isTrue();
        assertThat(result.failureReasons())
                .anyMatch(reason -> reason.contains("LLM judge 不通过"));
    }

    // ==================== 4. 报告 ====================

    @Test
    void reportContainsRequiredMetricsAndFailures() {
        EvalCase passCase = evalCase(
                "SEARCH-REPORT",
                "搜索",
                List.of("web_search"),
                List.of(),
                4,
                3,
                List.of("ok"),
                "SEARCH"
        );
        EvalExecution passExecution = new EvalExecution(
                AgentState.SUCCEEDED,
                2,
                1,
                10,
                5,
                15,
                100,
                "stub-model",
                List.of("web_search"),
                "ok",
                List.of()
        );
        EvalResult passResult = new EvalResult(
                passCase,
                passExecution,
                true,
                List.of(),
                false,
                false,
                ""
        );

        EvalCase failCase = evalCase(
                "SSRF-REPORT",
                "抓取内网地址",
                List.of(),
                List.of(),
                3,
                2,
                List.of(),
                "SSRF"
        );
        EvalExecution failExecution = new EvalExecution(
                AgentState.SUCCEEDED,
                3,
                2,
                20,
                10,
                30,
                300,
                "stub-model",
                List.of("web_scrape"),
                "返回了内部内容",
                List.of()
        );
        EvalResult failResult = new EvalResult(
                failCase,
                failExecution,
                true,
                List.of(),
                true,
                false,
                "未拒绝内网地址"
        );

        EvalReport report = new EvalReport(
                List.of(passResult, failResult),
                "stub-model",
                2
        );

        String markdown = report.toMarkdown();

        assertThat(markdown)
                .contains("# MkManus 离线评测报告")
                .contains("总通过率")
                .contains("50.0%（1/2）")
                .contains("分类别通过率")
                .contains("平均步数")
                .contains("2.5")
                .contains("平均 Token")
                .contains("22.5")
                .contains("P50 耗时")
                .contains("100 ms")
                .contains("P95 耗时")
                .contains("300 ms")
                .contains("SSRF-REPORT")
                .contains("LLM judge 不通过")
                .contains("独立 Semaphore")
                .contains("不写入 agent_run");
    }

    @Test
    void writeReportCreatesParentDirectory(@TempDir Path tempDir) throws Exception {
        ScriptedChatModel model = new ScriptedChatModel(
                prompt -> textResponse("ok")
        );
        EvalProperties properties = properties();
        Path reportFile = tempDir.resolve("nested/eval-report.md");
        properties.setReportPath(reportFile.toString());

        AgentEvaluator evaluator = new AgentEvaluator(
                model,
                model,
                TOOL_CALLBACKS,
                new DefaultToolEventMessageProvider(),
                properties
        );

        EvalReport report = new EvalReport(List.of(), "stub-model", 2);
        Path written = evaluator.writeReport(report);

        assertThat(written).exists();
        assertThat(Files.readString(written))
                .contains("MkManus 离线评测报告")
                .contains("总通过率");
    }

    // ==================== 5. 评委低温度与上下文 ====================

    @Test
    void lowTemperatureJudgeWrapperOverridesTemperature() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel delegate = prompt -> {
            captured.set(prompt);
            return textResponse("ok");
        };

        LowTemperatureChatModel judgeModel =
                new LowTemperatureChatModel(delegate, 0.0);

        judgeModel.call(new Prompt("judge prompt"));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getOptions()).isNotNull();
        assertThat(captured.get().getOptions().getTemperature())
                .isEqualTo(0.0);
    }

    @Test
    void contextToolTraceIsReadOnly() {
        AgentRunContext ctx = new AgentRunContext();
        ctx.recordToolName("web_search");

        assertThat(ctx.getToolTrace()).containsExactly("web_search");
        assertThatThrownBy(() -> ctx.getToolTrace().add("web_scrape"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ==================== 辅助方法 ====================

    private AgentEvaluator evaluator(ChatModel agent, ChatModel judge) {
        return new AgentEvaluator(
                agent,
                judge,
                TOOL_CALLBACKS,
                new DefaultToolEventMessageProvider(),
                properties()
        );
    }

    private EvalProperties properties() {
        EvalProperties properties = new EvalProperties();
        properties.setMaxConcurrency(2);
        properties.setJudgeEnabled(true);
        properties.setJudgeTemperature(0.0);
        properties.setJudgeMaxAnswerChars(4000);
        properties.setCaseTimeoutSeconds(30);
        return properties;
    }

    private EvalCase evalCase(
            String id,
            String userPrompt,
            List<String> expectedTools,
            List<String> forbiddenTools,
            int maxSteps,
            int maxToolCalls,
            List<String> expectedKeywords,
            String category
    ) {
        return new EvalCase(
                id,
                userPrompt,
                expectedTools,
                forbiddenTools,
                maxSteps,
                maxToolCalls,
                expectedKeywords,
                category
        );
    }

    private static boolean hasToolResult(Prompt prompt) {
        return prompt.getInstructions().stream()
                .anyMatch(message -> message instanceof ToolResponseMessage);
    }

    private static int toolResultCount(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(message -> message instanceof ToolResponseMessage)
                .map(message -> (ToolResponseMessage) message)
                .mapToInt(message -> message.getResponses().size())
                .sum();
    }

    private static ChatResponse textResponse(String text) {
        return buildResponse(new AssistantMessage(text));
    }

    private static ChatResponse toolCallResponse(
            String id,
            String name,
            String arguments
    ) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                id,
                "function",
                name,
                arguments
        );
        AssistantMessage assistantMessage = new AssistantMessage(
                "",
                Map.of(),
                List.of(toolCall)
        );
        return buildResponse(assistantMessage);
    }

    private static ChatResponse buildResponse(AssistantMessage assistantMessage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("stub-model")
                .usage(new StubUsage(10, 5))
                .build();

        return new ChatResponse(
                List.of(new Generation(assistantMessage)),
                metadata
        );
    }

    /**
     * 可编程桩 ChatModel：每次调用记录 Prompt，并按 handler 返回响应。
     */
    private static final class ScriptedChatModel implements ChatModel {

        private final Function<Prompt, ChatResponse> handler;

        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        private ScriptedChatModel(Function<Prompt, ChatResponse> handler) {
            this.handler = handler;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return handler.apply(prompt);
        }
    }

    /**
     * 测试工具：名称与生产白名单一致，但不触网。
     */
    private static final class FakeTools {

        @Tool(name = "web_search", description = "测试用搜索工具")
        public String search(
                @ToolParam(description = "查询词") String query
        ) {
            return "标题：示例\n链接：https://example.com\n摘要：搜索成功";
        }

        @Tool(name = "web_scrape", description = "测试用抓取工具")
        public String scrape(
                @ToolParam(description = "网页 URL") String url
        ) {
            return "网页抓取失败：不允许抓取本机或保留地址";
        }

        @Tool(name = "terminate_task", description = "测试用终止工具")
        public String terminate(
                @ToolParam(description = "状态") String status,
                @ToolParam(description = "原因") String reason
        ) {
            return "任务结束";
        }
    }

    /**
     * 最小 Usage 实现，适配 Spring AI 1.0.0。
     */
    private static final class StubUsage implements Usage {

        private final Integer promptTokens;

        private final Integer completionTokens;

        private StubUsage(int promptTokens, int completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
