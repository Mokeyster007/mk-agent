package com.example.mkagent.conversation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话接口的响应对象与映射。
 *
 * <p>从 {@code ConversationController} 抽出来，是因为现在有**两个**入口下发同一份结构：
 * 音乐入口 {@code /ai/conversations} 与售后入口 {@code /shop/aftersale/conversations}。
 * 两边各写一份 record，字段一旦增删就会有一边先落后，
 * 而前端只按一套类型解析——落后的那一边会静默丢字段。
 *
 * <p>两个入口的差别只在**守卫与领域过滤**（{@link com.example.mkagent.model.AgentType}），
 * 下发结构必须完全一致。
 */
public final class ConversationViews {

    private ConversationViews() {
    }

    /** 列表项。 */
    public record ConversationVO(
            String conversationId,
            String title,
            int messageCount,
            String lastState,
            LocalDateTime updatedAt
    ) {
    }

    /** 一条消息。role 只有 USER / ASSISTANT 两种。 */
    public record MessageVO(
            String runId,
            String role,
            String content,
            String state,
            LocalDateTime createdAt,
            /** 该轮仍有待审批时为 approvalId，否则 null（用于刷新后重建审批卡）。 */
            String approvalId
    ) {
    }

    /** 会话详情。 */
    public record ConversationDetailVO(
            String conversationId,
            String title,
            List<MessageVO> messages,
            LocalDateTime updatedAt
    ) {
    }

    public static ConversationVO summary(ConversationService.ConversationSummary item) {
        return new ConversationVO(
                item.conversationId(),
                item.title(),
                item.messageCount(),
                item.lastState(),
                item.updatedAt());
    }

    public static ConversationDetailVO detail(ConversationService.ConversationDetail detail) {
        return new ConversationDetailVO(
                detail.conversationId(),
                detail.title(),
                detail.messages().stream()
                        .map(m -> new MessageVO(
                                m.runId(), m.role(), m.content(), m.state(),
                                m.createdAt(), m.approvalId()))
                        .toList(),
                detail.updatedAt());
    }
}
