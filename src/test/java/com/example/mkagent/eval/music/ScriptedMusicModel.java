package com.example.mkagent.eval.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 脚本化模型：按任务集给定的回合依次返回工具调用或文本。
 *
 * <p>它**不是**用来"替模型答题"的。它的作用是让整条执行链路
 * （工具参数绑定 → 审批暂停 → 恢复执行 → 事务落库 → 运行记录）
 * 在一个完全确定、可复现的输入下跑通，从而验证：
 * 1. 评测执行器本身正确（数据准备、审批驱动、结果采集、清理）；
 * 2. 判定器能识别对与错（配合负向用例）。
 *
 * <p>脚本用尽后返回一条明确的兜底文本并置位 {@link #exhausted()}，
 * 由执行器转成"执行器异常"——绝不静默当作正常回答，
 * 否则一个漏写的回合会被误判成模型行为。
 */
public class ScriptedMusicModel implements ChatModel {

    private static final String EXHAUSTED_TEXT = "（评测脚本已用尽，未提供更多回合）";

    private final Deque<ChatResponse> script = new ArrayDeque<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicInteger calls = new AtomicInteger();

    private volatile boolean exhausted;

    private volatile Prompt lastPrompt;

    /**
     * @param turns 已解析占位符的脚本回合
     */
    public ScriptedMusicModel(List<MusicEvalTask.ScriptTurn> turns) {
        for (MusicEvalTask.ScriptTurn turn : turns) {
            if (turn.isToolCall()) {
                script.add(toolResponse(turn.getTool(), toJson(turn.getArgs())));
            } else {
                script.add(textResponse(turn.getAnswer()));
            }
        }
    }

    /**
     * 额外追加一个回合（负向用例用来构造"本不该发生的调用"）。
     */
    public ScriptedMusicModel appendToolCall(String tool, Map<String, Object> args) {
        script.add(toolResponse(tool, toJson(args)));
        return this;
    }

    public int calls() {
        return calls.get();
    }

    public boolean exhausted() {
        return exhausted;
    }

    public Prompt lastPrompt() {
        return lastPrompt;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        lastPrompt = prompt;

        ChatResponse next = script.poll();
        if (next == null) {
            exhausted = true;
            return textResponse(EXHAUSTED_TEXT);
        }
        return next;
    }

    private String toJson(Map<String, Object> args) {
        try {
            return objectMapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            throw new IllegalStateException("脚本参数无法序列化", e);
        }
    }

    private ChatResponse toolResponse(String tool, String argsJson) {
        AssistantMessage message = new AssistantMessage(
                "",
                Map.of(),
                List.of(new AssistantMessage.ToolCall(
                        "call-" + calls.get() + "-" + tool,
                        "function",
                        tool,
                        argsJson
                ))
        );
        return new ChatResponse(
                List.of(new Generation(message)),
                ChatResponseMetadata.builder()
                        .model("scripted-eval-model")
                        .usage(new StubUsage(0, 0))
                        .build()
        );
    }

    private ChatResponse textResponse(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text == null ? "" : text))),
                ChatResponseMetadata.builder()
                        .model("scripted-eval-model")
                        .usage(new StubUsage(0, 0))
                        .build()
        );
    }

    /**
     * 脚本化模式下的用量桩。
     *
     * <p>返回 0 而不是伪造数字：报告里会把 0 显示为"未知"，
     * 避免把"没有真实调用"写成"消耗为零"。
     */
    private record StubUsage(int promptTokens, int completionTokens) implements Usage {

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

    /**
     * 把脚本展开成可读文本，便于报告与排查。
     */
    public static List<String> describe(List<MusicEvalTask.ScriptTurn> turns) {
        List<String> lines = new ArrayList<>();
        for (MusicEvalTask.ScriptTurn turn : turns) {
            if (turn.isToolCall()) {
                lines.add(turn.getTool() + " " + turn.getArgs());
            } else {
                lines.add("answer: " + turn.getAnswer());
            }
        }
        return lines;
    }
}
