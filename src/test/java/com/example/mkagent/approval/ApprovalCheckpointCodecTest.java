package com.example.mkagent.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检查点编解码的回归测试。
 *
 * 重点不是"能不能转成 JSON"，而是三件会导致恢复失败的事：
 * 1. {@code toolCallId} 是否原样保留（配对断了，模型会看到孤儿工具结果）；
 * 2. pendingAssistant 是否只出现一次（重复追加会让模型以为工具被调了两次）；
 * 3. 真实 {@link ToolCallingManager} 拿到恢复后的历史，能否正常执行并产出
 *    每个 call 一条 response。
 */
class ApprovalCheckpointCodecTest {

    @Test
    @DisplayName("消息往返：角色、文本、toolCallId 与工具结果完全保留")
    void messagesRoundTripKeepsToolCallPairing() {
        List<Message> original = List.of(
                new SystemMessage("你是音乐助手"),
                new UserMessage("帮我建个歌单"),
                new AssistantMessage("", Map.of(), List.of(
                        new AssistantMessage.ToolCall(
                                "call-1", "function", "search_songs", "{\"q\":\"夜\"}"),
                        new AssistantMessage.ToolCall(
                                "call-2", "function", "create_playlist",
                                "{\"name\":\"夜曲\",\"songIds\":[1,2]}")
                )),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse(
                                "call-1", "search_songs", "[\"城市霓虹\"]")
                ))
        );

        ApprovalCheckpoint checkpoint = checkpointWith(
                CheckpointCodec.toCheckpointMessages(original), null);

        ApprovalCheckpoint decoded = CheckpointCodec.decode(
                CheckpointCodec.encode(checkpoint));
        List<Message> restored = CheckpointCodec.toMessages(decoded.messages());

        assertEquals(4, restored.size());
        assertTrue(restored.get(0) instanceof SystemMessage);
        assertEquals("你是音乐助手", ((SystemMessage) restored.get(0)).getText());
        assertEquals("帮我建个歌单", ((UserMessage) restored.get(1)).getText());

        AssistantMessage assistant = (AssistantMessage) restored.get(2);
        assertEquals(2, assistant.getToolCalls().size());
        assertEquals("call-1", assistant.getToolCalls().get(0).id());
        assertEquals("search_songs", assistant.getToolCalls().get(0).name());
        assertEquals("{\"q\":\"夜\"}", assistant.getToolCalls().get(0).arguments());
        assertEquals("call-2", assistant.getToolCalls().get(1).id());

        ToolResponseMessage toolResponse = (ToolResponseMessage) restored.get(3);
        assertEquals(1, toolResponse.getResponses().size());
        assertEquals("call-1", toolResponse.getResponses().get(0).id());
        assertEquals("[\"城市霓虹\"]", toolResponse.getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("pendingAssistant 单独存放：历史里没有它，追加后只出现一次")
    void pendingAssistantIsStoredSeparately() {
        List<Message> history = List.of(
                new SystemMessage("sys"),
                new UserMessage("建歌单")
        );

        AssistantMessage pending = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall(
                        "call-9", "function", "create_playlist", "{\"name\":\"夜曲\"}")
        ));

        ApprovalCheckpoint checkpoint = checkpointWith(
                CheckpointCodec.toCheckpointMessages(history),
                CheckpointCodec.fromAssistant(pending));

        ApprovalCheckpoint decoded = CheckpointCodec.decode(
                CheckpointCodec.encode(checkpoint));

        List<Message> restored = CheckpointCodec.toMessages(decoded.messages());
        assertEquals(2, restored.size(), "历史里不能包含 pendingAssistant");

        restored.add(CheckpointCodec.toMessage(decoded.pendingAssistant()));

        long assistantCount = restored.stream()
                .filter(AssistantMessage.class::isInstance)
                .count();
        assertEquals(1, assistantCount, "pendingAssistant 必须只出现一次");

        AssistantMessage restoredAssistant =
                (AssistantMessage) restored.get(restored.size() - 1);
        assertEquals("call-9", restoredAssistant.getToolCalls().get(0).id());
        assertEquals("{\"name\":\"夜曲\"}",
                restoredAssistant.getToolCalls().get(0).arguments());
    }

    @Test
    @DisplayName("真实 ToolCallingManager：每个 toolCallId 恰好一条 response，assistant 只出现一次")
    void realToolCallingManagerProducesOneResponsePerCall() {
        CounterTool counter = new CounterTool();
        var tools = ToolCallbacks.from(counter);

        AssistantMessage pending = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-a", "function", "counter", "{\"q\":\"a\"}"),
                new AssistantMessage.ToolCall("call-b", "function", "counter", "{\"q\":\"b\"}")
        ));

        ApprovalCheckpoint checkpoint = checkpointWith(
                CheckpointCodec.toCheckpointMessages(List.of(
                        new SystemMessage("sys"),
                        new UserMessage("数一下")
                )),
                CheckpointCodec.fromAssistant(pending));

        // 模拟恢复：**只**还原历史；pendingAssistant 通过 ChatResponse 交给框架，
        // 由 ToolCallingManager 追加。若这里先追加一次，框架再加一次就会重复。
        List<Message> restored = new ArrayList<>(
                CheckpointCodec.toMessages(checkpoint.messages()));

        Prompt prompt = new Prompt(restored, ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .build());

        ChatResponse response = new ChatResponse(List.of(new Generation(
                (AssistantMessage) CheckpointCodec.toMessage(
                        checkpoint.pendingAssistant()))));

        ToolExecutionResult result = ToolCallingManager.builder()
                .build()
                .executeToolCalls(prompt, response);

        List<Message> history = result.conversationHistory();

        assertEquals(4, history.size(),
                "system + user + assistant + toolResponse");
        assertEquals(1,
                history.stream().filter(AssistantMessage.class::isInstance).count(),
                "assistant 只能出现一次");

        ToolResponseMessage toolResponse =
                (ToolResponseMessage) history.get(history.size() - 1);
        List<String> responseIds = toolResponse.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::id)
                .collect(Collectors.toList());

        assertEquals(List.of("call-a", "call-b"), responseIds,
                "每个 toolCallId 必须恰好一条 response，且顺序一致");
        assertEquals(2, counter.invocations.get(), "两个调用都应真正执行");
    }

    @Test
    @DisplayName("未知角色 / 损坏 JSON：失败关闭，不返回半份检查点")
    void corruptCheckpointFailsClosed() {
        assertThrows(CheckpointCodec.CheckpointFormatException.class,
                () -> CheckpointCodec.decode("{not-json"));

        ApprovalCheckpoint.CheckpointMessage unknown = new ApprovalCheckpoint
                .CheckpointMessage("tool-call", "x", List.of(), List.of());
        assertThrows(CheckpointCodec.CheckpointFormatException.class,
                () -> CheckpointCodec.toMessage(unknown));
    }

    @Test
    @DisplayName("整轮哈希按顺序绑定：顺序不同即不同操作")
    void roundHashBindsOrder() {
        ApprovalCheckpoint.PendingToolCall a = new ApprovalCheckpoint.PendingToolCall(
                "1", "function", "create_playlist", "{\"name\":\"A\"}", "h1");
        ApprovalCheckpoint.PendingToolCall b = new ApprovalCheckpoint.PendingToolCall(
                "2", "function", "add_songs_to_playlist", "{\"playlistId\":1}", "h2");

        String forward = ApprovalHashes.roundHash(List.of(a, b));
        String backward = ApprovalHashes.roundHash(List.of(b, a));

        assertNotNull(forward);
        assertTrue(!forward.equals(backward), "顺序参与哈希，不能相同");
    }

    // ==================== 辅助 ====================

    private static ApprovalCheckpoint checkpointWith(
            List<ApprovalCheckpoint.CheckpointMessage> messages,
            ApprovalCheckpoint.CheckpointMessage pendingAssistant
    ) {
        return new ApprovalCheckpoint(
                ApprovalCheckpoint.CURRENT_SCHEMA_VERSION,
                "cfg-v1",
                "run-1",
                "music-user-1",
                "MUSIC_USER",
                1,
                0,
                messages,
                pendingAssistant,
                pendingAssistant == null ? List.of() : pendingAssistant.toolCalls(),
                new ApprovalCheckpoint.BudgetSnapshot(8, 12, 120),
                new ApprovalCheckpoint.UsageSnapshot("stub", 10, 5, 15, 1, 3),
                new ApprovalCheckpoint.TimingSnapshot(Instant.now().toString(), 1200),
                "wl-hash"
        );
    }

    /**
     * 桩工具：记录真实执行次数。
     */
    static class CounterTool {

        final AtomicInteger invocations = new AtomicInteger();

        @Tool(name = "counter", description = "计数桩工具")
        public String counter(@ToolParam(description = "任意参数") String q) {
            invocations.incrementAndGet();
            return "ok:" + q;
        }
    }
}
