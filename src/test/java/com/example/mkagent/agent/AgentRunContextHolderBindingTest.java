package com.example.mkagent.agent;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 工具执行期间能否拿到"当前任务的上下文"（桩测试，不联网、不启动 Spring）。
 *
 * 这条链路是音乐工具的安全基础：
 * 工具是 Spring 单例，userId 不能作为参数，
 * 只能靠 ToolCallAgent.act() 在执行工具期间把 AgentRunContext
 * 绑定到当前线程。
 *
 * 链路任何一环断了都会**静默越权**：
 * 要么工具拿不到 userId（直接报错，还好），
 * 要么拿到上一个任务的 userId（最坏——用户 A 读到用户 B 的歌单）。
 * 所以必须用"真的跑一遍 Agent Loop"来验证，并且断言跑完之后绑定被清除。
 */
class AgentRunContextHolderBindingTest {

    @AfterEach
    void tearDown() {
        AgentRunContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("工具执行期间能读到本次任务的 userId")
    void toolSeesCurrentRunContext() {
        CapturingTool tool = new CapturingTool();
        ToolCallAgent agent = newAgent(new TwoRoundChatModel(), tool);

        /*
         * 必须走 UserContextHolder，而不是直接 ctx.setUserId(...)：
         * BaseAgent.run() 会用 UserContextHolder.getOrDefault()
         * 覆盖传入 ctx 的 userId（模拟"在 Web 线程固化身份"）。
         * 直接 set 会被覆盖成 anonymous，测试就变成假绿。
         */
        UserContextHolder.set("music-user-1");
        AgentRunContext ctx = new AgentRunContext();

        agent.run("看一下我的歌单", ctx);

        assertEquals("music-user-1", tool.capturedUserId,
                "工具必须读到本次任务的用户，而不是 null 或匿名");
        assertSame(ctx, tool.capturedContext,
                "绑定的应是本次任务的 ctx 实例");
    }

    @Test
    @DisplayName("任务结束后绑定被清除：下一个任务不会继承上一个用户的身份")
    void bindingIsClearedAfterRun() {
        CapturingTool tool = new CapturingTool();
        ToolCallAgent agent = newAgent(new TwoRoundChatModel(), tool);

        UserContextHolder.set("music-user-1");
        agent.run("看一下我的歌单", new AgentRunContext());

        assertNull(AgentRunContextHolder.current(),
                "act() 结束后必须清除绑定，否则线程池复用会让下一个任务继承上一个用户");

        UserContextHolder.set("music-user-2");
        agent.run("再看一次", new AgentRunContext());

        assertEquals("music-user-2", tool.capturedUserId,
                "第二次任务应读到自己的用户，而不是第一次的");
    }

    // ===== 测试辅助 =====

    private ToolCallAgent newAgent(ChatModel chatModel, CapturingTool tool) {
        ToolCallAgent agent = new ToolCallAgent(
                chatModel,
                ToolCallbacks.from(tool),
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("test-agent");
        agent.setSystemPrompt("你是测试智能体。");
        agent.setMaxSteps(4);
        agent.setMaxToolCalls(4);
        agent.setTimeout(Duration.ofSeconds(10));
        return agent;
    }

    /**
     * 记录工具执行期间读到的上下文。
     */
    public static final class CapturingTool {

        private volatile AgentRunContext capturedContext;

        private volatile String capturedUserId;

        @Tool(name = "capture_user", description = "测试用：记录当前任务上下文")
        public String capture() {
            capturedContext = AgentRunContextHolder.current();
            capturedUserId = capturedContext == null
                    ? null
                    : capturedContext.getUserId();
            return "ok";
        }
    }

    /**
     * 两轮模型：本轮上下文里还没有工具结果就请求工具，有了就收尾。
     *
     * 用"看上下文"而不是"数调用次数"判断第几轮：
     * 计数器会被同一实例的第二次 run 继承，
     * 导致第二次 run 直接走收尾分支、工具根本不再执行，
     * 于是"绑定是否被清除"的断言永远测不到。
     */
    private static final class TwoRoundChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean sawToolResult = prompt.getInstructions().stream()
                    .anyMatch(ToolResponseMessage.class::isInstance);

            if (!sawToolResult) {
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "",
                                Map.of(),
                                List.of(new AssistantMessage.ToolCall(
                                        "call_1", "function", "capture_user", "{}"))
                        ))),
                        ChatResponseMetadata.builder().model("stub-model").build()
                );
            }

            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("已拿到上下文。"))),
                    ChatResponseMetadata.builder().model("stub-model").build()
            );
        }
    }
}
