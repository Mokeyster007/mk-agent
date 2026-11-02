package com.example.mkagent.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检查点编解码：Spring AI Message ↔ 可序列化 DTO ↔ JSON。
 *
 * 为什么不用 Kryo / 直接序列化 Message：
 * 1. {@code AssistantMessage} / {@code ToolResponseMessage} 没有
 *    {@code @JsonCreator}、没有无参构造，Jackson 反序列化必失败；
 * 2. 检查点要能被人读懂、能被 SQL 直接查，
 *    二进制格式在排查"为什么恢复失败"时毫无帮助；
 * 3. 显式 DTO 能保证字段只增不减、语义可版本化（schemaVersion）。
 *
 * 未知的消息类型**直接抛异常**而不是跳过：
 * 静默丢一条消息会让恢复后的对话缺一段，
 * 模型看到的历史与实际发生的不一致，比拒绝恢复更难排查。
 */
public final class CheckpointCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CheckpointCodec() {
    }

    /**
     * 检查点 → JSON。
     */
    public static String encode(ApprovalCheckpoint checkpoint) {
        try {
            return MAPPER.writeValueAsString(checkpoint);
        } catch (Exception e) {
            throw new CheckpointFormatException("检查点序列化失败", e);
        }
    }

    /**
     * JSON → 检查点。
     */
    public static ApprovalCheckpoint decode(String json) {
        try {
            ApprovalCheckpoint checkpoint =
                    MAPPER.readValue(json, ApprovalCheckpoint.class);
            if (checkpoint == null) {
                throw new CheckpointFormatException("检查点内容为空");
            }
            return checkpoint;
        } catch (CheckpointFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckpointFormatException("检查点反序列化失败", e);
        }
    }

    /**
     * Spring AI 消息列表 → DTO 列表。
     */
    public static List<ApprovalCheckpoint.CheckpointMessage> toCheckpointMessages(
            List<Message> messages
    ) {
        List<ApprovalCheckpoint.CheckpointMessage> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (Message message : messages) {
            result.add(toCheckpointMessage(message));
        }
        return result;
    }

    /**
     * 单条消息 → DTO。
     */
    public static ApprovalCheckpoint.CheckpointMessage toCheckpointMessage(Message message) {
        if (message instanceof SystemMessage system) {
            return new ApprovalCheckpoint.CheckpointMessage(
                    "system", system.getText(), List.of(), List.of());
        }
        if (message instanceof UserMessage user) {
            return new ApprovalCheckpoint.CheckpointMessage(
                    "user", user.getText(), List.of(), List.of());
        }
        if (message instanceof AssistantMessage assistant) {
            return new ApprovalCheckpoint.CheckpointMessage(
                    "assistant",
                    assistant.getText(),
                    toPendingToolCalls(assistant.getToolCalls()),
                    List.of()
            );
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            List<ApprovalCheckpoint.ToolResponseItem> items = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                items.add(new ApprovalCheckpoint.ToolResponseItem(
                        response.id(), response.name(), response.responseData()));
            }
            return new ApprovalCheckpoint.CheckpointMessage(
                    "tool", null, List.of(), items);
        }
        throw new CheckpointFormatException(
                "不支持的消息类型：" + message.getClass().getName());
    }

    /**
     * DTO → Spring AI 消息。
     */
    public static Message toMessage(ApprovalCheckpoint.CheckpointMessage message) {
        if (message == null) {
            throw new CheckpointFormatException("消息为空");
        }
        String role = message.role() == null ? "" : message.role();
        return switch (role) {
            case "system" -> new SystemMessage(nullToEmpty(message.text()));
            case "user" -> new UserMessage(nullToEmpty(message.text()));
            case "assistant" -> new AssistantMessage(
                    nullToEmpty(message.text()),
                    Map.of(),
                    toToolCalls(message.toolCalls())
            );
            case "tool" -> new ToolResponseMessage(toToolResponses(message.toolResponses()));
            default -> throw new CheckpointFormatException("未知消息角色：" + role);
        };
    }

    /**
     * DTO 列表 → Spring AI 消息列表。
     */
    public static List<Message> toMessages(
            List<ApprovalCheckpoint.CheckpointMessage> messages
    ) {
        List<Message> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (ApprovalCheckpoint.CheckpointMessage message : messages) {
            result.add(toMessage(message));
        }
        return result;
    }

    /**
     * AssistantMessage → 检查点消息（用于 pendingAssistant）。
     */
    public static ApprovalCheckpoint.CheckpointMessage fromAssistant(
            AssistantMessage assistantMessage
    ) {
        if (assistantMessage == null) {
            throw new CheckpointFormatException("pendingAssistant 为空");
        }
        return new ApprovalCheckpoint.CheckpointMessage(
                "assistant",
                assistantMessage.getText(),
                toPendingToolCalls(assistantMessage.getToolCalls()),
                List.of()
        );
    }

    private static List<ApprovalCheckpoint.PendingToolCall> toPendingToolCalls(
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        List<ApprovalCheckpoint.PendingToolCall> result = new ArrayList<>();
        if (toolCalls == null) {
            return result;
        }
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            result.add(new ApprovalCheckpoint.PendingToolCall(
                    toolCall.id(),
                    toolCall.type(),
                    toolCall.name(),
                    toolCall.arguments(),
                    ApprovalHashes.argsHash(toolCall.arguments())
            ));
        }
        return result;
    }

    private static List<AssistantMessage.ToolCall> toToolCalls(
            List<ApprovalCheckpoint.PendingToolCall> pendingToolCalls
    ) {
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        if (pendingToolCalls == null) {
            return result;
        }
        for (ApprovalCheckpoint.PendingToolCall call : pendingToolCalls) {
            result.add(new AssistantMessage.ToolCall(
                    call.id(),
                    call.type() == null ? "function" : call.type(),
                    call.name(),
                    call.arguments()
            ));
        }
        return result;
    }

    private static List<ToolResponseMessage.ToolResponse> toToolResponses(
            List<ApprovalCheckpoint.ToolResponseItem> items
    ) {
        List<ToolResponseMessage.ToolResponse> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (ApprovalCheckpoint.ToolResponseItem item : items) {
            result.add(new ToolResponseMessage.ToolResponse(
                    item.id(), item.name(), item.responseData()));
        }
        return result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 检查点格式错误（不可恢复）。
     *
     * 继承 RuntimeException：调用方按"失败关闭"处理，
     * 不做"尽力恢复"。
     */
    public static class CheckpointFormatException extends RuntimeException {

        public CheckpointFormatException(String message) {
            super(message);
        }

        public CheckpointFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
