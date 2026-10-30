package com.example.mkagent.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志治理（离线，用 Logback ListAppender 捕获日志）。
 *
 * 真实运行暴露的问题：8 步任务里"当前提供给模型的工具"被打了 8 遍
 * （3 个工具 × 8 步 = 24 行噪声），而工具结果的完整内容
 * （网页正文、搜索结果）也按 INFO 全量打印，日志体积按 token 量级膨胀。
 *
 * 修复后的约定：
 * 1. 工具清单只在第一步打一次 INFO，其余 DEBUG；
 * 2. 工具结果在 INFO 截断到 200 字符，完整内容只在 DEBUG。
 */
class ToolCallAgentLogGovernanceTest {

    /**
     * 长结果尾部的唯一标记：用来判断 INFO 是否真的截断了。
     */
    private static final String TAIL_MARKER = "TAIL_MARKER_END_OF_RESULT";

    private Logger agentLogger;

    private Level originalLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        agentLogger = (Logger) LoggerFactory.getLogger(ToolCallAgent.class);
        originalLevel = agentLogger.getLevel();

        // 只有调到 DEBUG 才能同时捕获 INFO 与 DEBUG，进而比较两者。
        agentLogger.setLevel(Level.DEBUG);

        appender = new ListAppender<>();
        appender.start();
        agentLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (agentLogger != null && appender != null) {
            agentLogger.detachAppender(appender);
            appender.stop();
        }
        if (agentLogger != null) {
            agentLogger.setLevel(originalLevel);
        }
    }

    @Test
    @DisplayName("工具清单只在第一步打一次 INFO，其余走 DEBUG")
    void availableToolsLoggedOnceAtInfo() {
        runTwoStepTask();

        long infoCount = events(Level.INFO, "当前提供给模型的工具").size();
        long debugCount = events(Level.DEBUG, "工具定义").size();

        assertEquals(
                1,
                infoCount,
                "工具清单只应在第一步打一次 INFO，实际：" + infoCount
        );
        assertTrue(
                debugCount >= 2,
                "每个 step 都应有 DEBUG 级别的工具定义，实际：" + debugCount
        );
    }

    @Test
    @DisplayName("工具结果 INFO 截断到 200 字符，完整内容只在 DEBUG")
    void toolResultTruncatedAtInfoAndFullAtDebug() {
        runTwoStepTask();

        List<ILoggingEvent> infoLogs = events(Level.INFO, "工具实际执行完成");
        List<ILoggingEvent> debugLogs = events(Level.DEBUG, "工具实际执行完成（完整结果）");

        assertEquals(1, infoLogs.size(), "应恰好有一条 INFO 级工具完成日志");
        assertEquals(1, debugLogs.size(), "应恰好有一条 DEBUG 级完整结果日志");

        String infoMessage = infoLogs.get(0).getFormattedMessage();
        String debugMessage = debugLogs.get(0).getFormattedMessage();

        assertFalse(
                infoMessage.contains(TAIL_MARKER),
                "INFO 日志必须截断，不能包含结果尾部，实际：" + infoMessage
        );
        assertTrue(
                infoMessage.contains("…(共 "),
                "截断处应标出原始长度，实际：" + infoMessage
        );
        assertTrue(
                debugMessage.contains(TAIL_MARKER),
                "DEBUG 日志应保留完整结果"
        );
    }

    // ===== 测试辅助 =====

    /**
     * 跑一个两步任务：第一步调用工具（返回超长结果），第二步给出最终回答。
     */
    private void runTwoStepTask() {
        ToolCallAgent agent = new ToolCallAgent(
                new TwoRoundChatModel(),
                ToolCallbacks.from(new LongResultTool()),
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("log-test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(3);
        agent.setMaxToolCalls(3);
        agent.setTimeout(Duration.ofSeconds(10));

        AgentRunContext ctx = new AgentRunContext();
        agent.run("读取一份很长的资料", ctx);

        assertEquals(AgentState.SUCCEEDED, ctx.getState(), "任务应正常结束");
    }

    private List<ILoggingEvent> events(Level level, String contains) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .filter(event -> event.getFormattedMessage().contains(contains))
                .toList();
    }

    /**
     * 返回超长结果的工具：前 200 字符之后放一个唯一尾部标记。
     */
    public static final class LongResultTool {

        @Tool(
                name = "long_result_tool",
                description = "测试用工具，返回超长结果"
        )
        public String read(
                @ToolParam(description = "资源标识") String id
        ) {
            return "x".repeat(500) + TAIL_MARKER;
        }
    }

    /**
     * 两轮脚本模型：先要工具，再给最终回答。
     */
    private static final class TwoRoundChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean hasToolResult = prompt.getInstructions().stream()
                    .anyMatch(message -> message instanceof ToolResponseMessage);

            AssistantMessage output = hasToolResult
                    ? new AssistantMessage("已读取完成。")
                    : new AssistantMessage(
                            "",
                            Map.of(),
                            List.of(new AssistantMessage.ToolCall(
                                    "call_1",
                                    "function",
                                    "long_result_tool",
                                    "{\"id\":\"doc-1\"}"
                            ))
                    );

            return new ChatResponse(
                    List.of(new Generation(output)),
                    ChatResponseMetadata.builder().model("stub-model").build()
            );
        }
    }
}
