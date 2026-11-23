package com.example.mkagent.agent;

import com.example.mkagent.approval.ApprovalCheckpoint;
import com.example.mkagent.approval.ApprovalPauseGateway;
import com.example.mkagent.approval.ApprovalProperties;
import com.example.mkagent.approval.ApprovalSummary;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.approval.PauseResult;
import com.example.mkagent.approval.RunHandoffLocks;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import com.example.mkagent.tools.RequiresApproval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批暂停的单元回归（批次 1）。
 *
 * 这里验证的是"暂停这一刻"的硬约束：
 * 1. 一个工具都不执行（读也不执行）；
 * 2. 步数只算一次（暂停那一轮已计步，恢复不再 +1）；
 * 3. 检查点里 history 不含 pendingAssistant，pending 调用完整；
 * 4. 暂停后不再调用模型（不追加总结）；
 * 5. 执行权交接锁在收尾后释放。
 *
 * 恢复与批准在批次 2，这里不覆盖。
 */
class ApprovalPauseBehaviourTest {

    private static final String WRITE_TOOL = "create_playlist";

    private static final String READ_TOOL = "search_songs";

    @Test
    @DisplayName("命中写工具：整轮暂停，一个工具都不执行")
    void pauseExecutesNoTool() {
        StubTools tools = new StubTools();
        ToolCallModel model = new ToolCallModel(List.of(
                new AssistantMessage.ToolCall(
                        "call-1", "function", WRITE_TOOL,
                        "{\"name\":\"夜曲\",\"songIds\":[1,2]}")
        ));

        RecordingGateway gateway = new RecordingGateway();
        RunHandoffLocks locks = new RunHandoffLocks();
        ToolCallAgent agent = newAgent(model, 4, true, gateway, locks, tools);

        AgentRunContext ctx = new AgentRunContext();
        String result = agent.run("帮我建一个歌单", ctx);

        assertEquals(AgentState.WAITING_APPROVAL, ctx.getState(),
                "命中写工具后必须进入 WAITING_APPROVAL");
        assertEquals(0, tools.writeExecutions.get(), "写工具绝不能被执行");
        assertEquals(0, tools.readExecutions.get(), "整轮暂停，读工具也不执行");
        assertEquals(0, ctx.getToolCallCount(), "工具调用计数不得增加");
        assertEquals(1, ctx.getCurrentStep(), "暂停发生在第 1 步");
        assertEquals(1, model.calls.get(), "暂停后不得再调用模型");
        assertEquals(1, gateway.persistCalls, "只提交一次暂停");

        assertNotNull(gateway.checkpoint, "必须落检查点");
        assertEquals(1, gateway.checkpoint.pendingToolCalls().size());
        assertEquals("call-1", gateway.checkpoint.pendingToolCalls().get(0).id());
        assertEquals(WRITE_TOOL, gateway.checkpoint.pendingToolCalls().get(0).name());

        assertEquals(2, gateway.checkpoint.messages().size(),
                "history 只含 system + user，不含 pendingAssistant");
        assertEquals(1, gateway.checkpoint.pendingAssistant().toolCalls().size(),
                "pendingAssistant 必须携带本轮 toolCalls");

        assertNotNull(ctx.getPendingApprovalJson(), "必须准备 approval_required 事件");
        assertTrue(ctx.getPendingApprovalJson().contains("ap-1"));
        assertFalse(ctx.getPendingApprovalJson().contains("songIds"),
                "事件 data 不得包含参数原文");

        assertEquals("任务已暂停，等待人工确认。", result);
        assertFalse(locks.exists(ctx.getRunId().toString()),
                "收尾后必须释放执行权交接锁");
        assertNull(gateway.abandoned, "状态未变化时不应撤销审批");
    }

    @Test
    @DisplayName("整轮暂停：读写混合轮两个都不执行")
    void mixedRoundPausesWholeRound() {
        StubTools tools = new StubTools();
        ToolCallModel model = new ToolCallModel(List.of(
                new AssistantMessage.ToolCall(
                        "call-1", "function", READ_TOOL, "{\"q\":\"夜\"}"),
                new AssistantMessage.ToolCall(
                        "call-2", "function", WRITE_TOOL,
                        "{\"name\":\"夜曲\",\"songIds\":[1]}")
        ));

        RecordingGateway gateway = new RecordingGateway();
        ToolCallAgent agent = newAgent(
                model, 4, true, gateway, new RunHandoffLocks(), tools);

        AgentRunContext ctx = new AgentRunContext();
        agent.run("先搜再建", ctx);

        assertEquals(0, tools.readExecutions.get(), "读工具也不得执行");
        assertEquals(0, tools.writeExecutions.get());
        assertEquals(2, gateway.checkpoint.pendingToolCalls().size(),
                "整轮两个调用都要进检查点");
        assertEquals(2, gateway.summary.roundToolCount(),
                "摘要要说明本轮有几个操作");
    }

    @Test
    @DisplayName("maxSteps=1：已计步的暂停轮仍完整落检查点，不被预算跳过")
    void pauseStillWorksWhenStepBudgetIsOne() {
        StubTools tools = new StubTools();
        ToolCallModel model = new ToolCallModel(List.of(
                new AssistantMessage.ToolCall(
                        "call-1", "function", WRITE_TOOL, "{\"name\":\"夜曲\"}")
        ));

        RecordingGateway gateway = new RecordingGateway();
        ToolCallAgent agent = newAgent(
                model, 1, true, gateway, new RunHandoffLocks(), tools);

        AgentRunContext ctx = new AgentRunContext();
        agent.run("建歌单", ctx);

        assertEquals(AgentState.WAITING_APPROVAL, ctx.getState());
        assertEquals(1, ctx.getCurrentStep());
        assertEquals(1, gateway.checkpoint.pendingToolCalls().size(),
                "已计步的 pending round 必须完整保留，恢复时才执行");
        assertEquals(0, tools.writeExecutions.get());
    }

    @Test
    @DisplayName("审批关闭但命中写工具：失败关闭，绝不直接执行")
    void disabledApprovalFailsClosed() {
        StubTools tools = new StubTools();
        ToolCallModel model = new ToolCallModel(List.of(
                new AssistantMessage.ToolCall(
                        "call-1", "function", WRITE_TOOL, "{\"name\":\"夜曲\"}")
        ));

        RecordingGateway gateway = new RecordingGateway();
        ToolCallAgent agent = newAgent(
                model, 4, false, gateway, new RunHandoffLocks(), tools);

        AgentRunContext ctx = new AgentRunContext();
        agent.run("建歌单", ctx);

        assertEquals(AgentState.FAILED, ctx.getState(),
                "审批不可用时必须失败，而不是静默执行");
        assertEquals("APPROVAL_DISABLED", ctx.getErrorCode());
        assertEquals(0, tools.writeExecutions.get());
        assertEquals(0, gateway.persistCalls, "失败关闭不产生审批记录");
    }

    // ==================== 辅助 ====================

    private ToolCallAgent newAgent(
            ChatModel model,
            int maxSteps,
            boolean approvalEnabled,
            ApprovalPauseGateway gateway,
            RunHandoffLocks locks,
            StubTools tools
    ) {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        ToolCallAgent agent = new ToolCallAgent(
                model,
                callbacks,
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("approval-pause-test-agent");
        agent.setSystemPrompt("你是一个测试用的音乐助手。");
        agent.setMaxSteps(maxSteps);
        agent.setMaxToolCalls(10);
        agent.setTimeout(Duration.ofSeconds(30));

        /*
         * 三个参数分别声明"哪些要审批"和"这个 Agent 实际能执行哪些工具"。
         * 后者不能省：审批元数据校验会拒绝任何"能执行但扫描不到"的工具。
         */
        agent.setApprovalToolPolicy(new ApprovalToolPolicy(
                Set.of(WRITE_TOOL), Set.of(WRITE_TOOL, READ_TOOL), approvalEnabled));
        agent.setApprovalPauseGateway(gateway);
        agent.setApprovalSummaryBuilder(new ApprovalSummaryBuilder(
                new ApprovalProperties()
        ));
        agent.setHandoffLocks(locks);

        return agent;
    }

    /**
     * 记录暂停调用，不碰数据库。
     */
    static class RecordingGateway implements ApprovalPauseGateway {

        int persistCalls;

        AgentRunContext context;

        ApprovalCheckpoint checkpoint;

        ApprovalSummary summary;

        String abandoned;

        @Override
        public PauseResult persist(
                AgentRunContext ctx,
                ApprovalCheckpoint checkpoint,
                ApprovalSummary summary
        ) {
            this.persistCalls++;
            this.context = ctx;
            this.checkpoint = checkpoint;
            this.summary = summary;
            return new PauseResult("ap-1", "{\"approvalId\":\"ap-1\"}");
        }

        @Override
        public void abandon(String approvalId, String reason) {
            this.abandoned = approvalId;
        }
    }

    /**
     * 桩工具：写工具带 @RequiresApproval，读工具不带。
     */
    static class StubTools {

        final AtomicInteger writeExecutions = new AtomicInteger();

        final AtomicInteger readExecutions = new AtomicInteger();

        @Tool(name = WRITE_TOOL, description = "创建歌单（写）")
        @RequiresApproval(reason = "会写入数据库")
        public String createPlaylist(
                @ToolParam(description = "歌单名") String name,
                @ToolParam(description = "歌曲 ID") List<Long> songIds
        ) {
            writeExecutions.incrementAndGet();
            return "{\"playlistId\":42}";
        }

        @Tool(name = READ_TOOL, description = "搜索歌曲（读）")
        public String searchSongs(@ToolParam(description = "关键词") String q) {
            readExecutions.incrementAndGet();
            return "[]";
        }
    }

    /**
     * 永远返回同一组 toolCall 的模型。
     */
    static class ToolCallModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        private final List<AssistantMessage.ToolCall> toolCalls;

        ToolCallModel(List<AssistantMessage.ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "", Map.of(), toolCalls))),
                    ChatResponseMetadata.builder()
                            .model("stub-model")
                            .usage(new StubUsage(10, 5))
                            .build()
            );
        }
    }

    private record StubUsage(int promptTokens, int completionTokens)
            implements Usage {

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
