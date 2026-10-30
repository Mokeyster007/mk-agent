package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import com.example.mkagent.tools.TerminateTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * terminate_task 的 final_answer 取值优先级（全部桩测试）。
 *
 * 真实运行暴露的问题：模型在同一轮既输出说明文字、又调用 terminate_task 时，
 * final_answer 只取了工具摘要，用户拿到的是
 * "任务结束：任务结束已确认"——等于没有答案。
 *
 * 修复后的优先级（四种来源）：
 * 1. terminate_task 的 final_answer 参数；
 * 2. 同一轮 assistant 文本；
 * 3. ctx 上已有的 finalAnswer；
 * 4. 工具执行摘要（旧行为兜底）。
 *
 * 关于三处输出一致性：
 * SSE 的 final_answer 事件与落库的 agent_run.final_answer 都直接读
 * {@code ctx.getFinalAnswer()}（act() 是唯一写入点），同步 run() 的返回值
 * 同样来自该字段，因此一致性是结构性的；这里断言同步路径，
 * 并覆盖"已有答案不被工具摘要覆盖"这条防回归。
 */
class TerminateFinalAnswerPriorityTest {

    private static final String ARG_ANSWER =
            "Spring AI 1.0.0 支持通过 @Tool 注解声明工具。来源：https://docs.spring.io/spring-ai/reference/";

    private static final String ASSISTANT_TEXT =
            "我查到官方文档说明了 Tool Calling 的用法，详情见上。";

    // ==================== 1. 参数优先 ====================

    @Test
    @DisplayName("优先使用 final_answer 参数")
    void usesFinalAnswerArgumentFirst() {
        TerminatingChatModel model = new TerminatingChatModel(
                ASSISTANT_TEXT,
                terminateArgs("success", "已找到官方文档", ARG_ANSWER)
        );

        AgentRunContext ctx = new AgentRunContext();
        String returned = newAgent(model).run("查一下 Spring AI 的 Tool Calling 文档", ctx);

        assertEquals(AgentState.SUCCEEDED, ctx.getState());
        assertEquals(
                ARG_ANSWER,
                ctx.getFinalAnswer(),
                "有 final_answer 参数时必须优先使用它，而不是 assistant 文本或工具摘要"
        );
        assertEquals(returned, ctx.getFinalAnswer(), "run() 返回值必须与落库值一致");
    }

    @Test
    @DisplayName("参数名兼容 snake_case：final_answer")
    void acceptsSnakeCaseArgumentKey() {
        TerminatingChatModel model = new TerminatingChatModel(
                "",
                "{\"status\":\"success\",\"reason\":\"ok\","
                        + "\"final_answer\":\"snake_case 的答案\"}"
        );

        AgentRunContext ctx = new AgentRunContext();
        newAgent(model).run("随便问问", ctx);

        assertEquals(
                "snake_case 的答案",
                ctx.getFinalAnswer(),
                "模型按直觉写 final_answer 时也要能取到"
        );
    }

    // ==================== 2. 回退到同轮 assistant 文本 ====================

    @Test
    @DisplayName("没有 final_answer 参数时回退到同轮 assistant 文本")
    void fallsBackToAssistantText() {
        TerminatingChatModel model = new TerminatingChatModel(
                ASSISTANT_TEXT,
                terminateArgs("success", "任务完成", null)
        );

        AgentRunContext ctx = new AgentRunContext();
        newAgent(model).run("帮我总结一下", ctx);

        assertEquals(
                ASSISTANT_TEXT,
                ctx.getFinalAnswer(),
                "缺 final_answer 参数时应使用同一轮 assistant 文本"
        );
        assertFalse(
                ctx.getFinalAnswer().contains("任务结束："),
                "不能退化成工具摘要"
        );
    }

    // ==================== 3. 回退到 ctx 上已有的答案 ====================

    @Test
    @DisplayName("参数与文本都没有时，不覆盖 ctx 上已有的答案")
    void keepsExistingFinalAnswer() {
        TerminatingChatModel model = new TerminatingChatModel(
                "",
                terminateArgs("success", "任务完成", null)
        );

        AgentRunContext ctx = new AgentRunContext();
        ctx.setFinalAnswer("之前步骤已经确定的答案");

        newAgent(model).run("收尾", ctx);

        assertEquals(
                "之前步骤已经确定的答案",
                ctx.getFinalAnswer(),
                "已有答案不能被工具摘要覆盖，否则就是纯粹的信息丢失"
        );
    }

    // ==================== 4. 回退到工具摘要 ====================

    @Test
    @DisplayName("前三项都没有时回退到工具摘要")
    void fallsBackToToolSummary() {
        TerminatingChatModel model = new TerminatingChatModel(
                "",
                terminateArgs("success", "任务完成", null)
        );

        AgentRunContext ctx = new AgentRunContext();
        newAgent(model).run("帮我处理一下", ctx);

        assertNotNull(ctx.getFinalAnswer());
        assertTrue(
                ctx.getFinalAnswer().startsWith("任务结束："),
                "前三项都没有时应保持旧的工具摘要兜底，实际：" + ctx.getFinalAnswer()
        );
    }

    // ==================== 4. 长度截断 ====================

    @Test
    @DisplayName("超长 final_answer 截断到 4000 字符以内")
    void truncatesOverlongFinalAnswer() {
        String longAnswer = "答案".repeat(3000); // 6000 字符

        TerminatingChatModel model = new TerminatingChatModel(
                "",
                terminateArgs("success", "完成", longAnswer)
        );

        AgentRunContext ctx = new AgentRunContext();
        newAgent(model).run("给我一份长答案", ctx);

        String answer = ctx.getFinalAnswer();
        assertTrue(
                answer.length() <= TerminateTool.MAX_FINAL_ANSWER_LENGTH,
                "最终回答不得超过上限，实际长度：" + answer.length()
        );
        assertTrue(
                answer.endsWith("…（回答过长已截断）"),
                "截断处应有显式标记，实际尾部：" + answer.substring(answer.length() - 20)
        );
    }

    @Test
    @DisplayName("刚好在上限内不截断")
    void keepsAnswerWithinLimitIntact() {
        String exact = "x".repeat(TerminateTool.MAX_FINAL_ANSWER_LENGTH);

        TerminatingChatModel model = new TerminatingChatModel(
                "",
                terminateArgs("success", "完成", exact)
        );

        AgentRunContext ctx = new AgentRunContext();
        newAgent(model).run("给我答案", ctx);

        assertEquals(
                exact,
                ctx.getFinalAnswer(),
                "等于上限时不应被截断"
        );
    }

    // ==================== 5. 同步返回值与落库字段一致 ====================

    @Test
    @DisplayName("run() 返回值与 ctx.getFinalAnswer() 一致")
    void returnValueMatchesContextFinalAnswer() {
        TerminatingChatModel model = new TerminatingChatModel(
                "",
                terminateArgs("success", "完成", ARG_ANSWER)
        );

        AgentRunContext ctx = new AgentRunContext();
        String returned = newAgent(model).run("查官方文档", ctx);

        assertEquals(ARG_ANSWER, ctx.getFinalAnswer());
        assertEquals(
                ARG_ANSWER,
                returned,
                "同步 run() 的返回值必须就是落库的那个字段"
        );
    }

    // ==================== 测试辅助 ====================

    private ToolCallAgent newAgent(ChatModel chatModel) {
        ToolCallAgent agent = new ToolCallAgent(
                chatModel,
                ToolCallbacks.from(new TerminateTool()),
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("terminate-test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(3);
        agent.setMaxToolCalls(3);
        agent.setTimeout(Duration.ofSeconds(10));
        return agent;
    }

    /**
     * 构造 terminate_task 的调用参数；finalAnswer 为 null 时不写该字段，
     * 模拟"模型没有提供完整回答"的情况。
     */
    private static String terminateArgs(
            String status,
            String reason,
            String finalAnswer
    ) {
        StringBuilder json = new StringBuilder()
                .append("{\"status\":\"").append(status)
                .append("\",\"reason\":\"").append(reason).append("\"");

        if (finalAnswer != null) {
            json.append(",\"finalAnswer\":\"")
                    .append(escape(finalAnswer))
                    .append("\"");
        }

        return json.append("}").toString();
    }

    /**
     * JSON 字符串转义：测试里会出现换行与引号。
     */
    private static String escape(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    /**
     * 单轮终止模型：assistant 文本 + 一个 terminate_task 调用。
     */
    private static final class TerminatingChatModel implements ChatModel {

        private final String assistantText;

        private final String arguments;

        private TerminatingChatModel(String assistantText, String arguments) {
            this.assistantText = assistantText;
            this.arguments = arguments;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            AssistantMessage message = new AssistantMessage(
                    assistantText,
                    Map.of(),
                    List.of(new AssistantMessage.ToolCall(
                            "call_1",
                            "function",
                            ToolCallAgent.TERMINATE_TOOL_NAME,
                            arguments
                    ))
            );

            return new ChatResponse(
                    List.of(new Generation(message)),
                    ChatResponseMetadata.builder().model("stub-model").build()
            );
        }
    }

    /**
     * 记录 SSE 事件的 emitter 替身。
     */
}
