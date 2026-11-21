package com.example.mkagent.conversation;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.UUID;

/**
 * 一轮开始时要注入给 Agent 的会话上下文。
 *
 * <p>只带三样东西：本轮 run 的 id（由入口先生成，便于先落用户消息）、
 * 最近几轮的纯文本历史、以及一段"已知对象"事实清单。
 *
 * <p>为什么不带工具调用：历史工具调用被回放会变成"新一轮指令"，
 * 模型可能把上一轮的 create_playlist 再执行一次。
 */
public record ConversationSeed(
        String conversationId,
        UUID runId,
        List<Message> history,
        String refsBlock
) {

    /**
     * 无会话时的空 seed（老的独立任务入口）。
     */
    public static ConversationSeed none(UUID runId) {
        return new ConversationSeed(null, runId, List.of(), null);
    }

    public boolean hasConversation() {
        return conversationId != null && !conversationId.isBlank();
    }
}
