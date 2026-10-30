package com.example.mkagent.eval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.ToolResultTexts;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条用例执行完成后的观测快照。
 *
 * 数据全部来自 AgentRunContext，不读取数据库：
 * state / step / toolCallCount / token / 模型名 / 工具名轨迹 / 最终回答 /
 * 工具结果原文。
 *
 * toolResults 是为了 CitationFaithfulnessChecker 新增的：
 * 要判断"回答里的 URL 是否真的来自工具结果"，
 * 就必须拿到工具实际返回的文本，光有工具名不够。
 * 这里的文本已用 ToolResultTexts.decode 解码（去掉 JSON 字符串字面量的引号与转义）。
 */
public record EvalExecution(
        AgentState state,
        int steps,
        int toolCallCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long durationMillis,
        String model,
        List<String> toolTrace,
        String finalAnswer,
        List<String> toolResults
) {

    public EvalExecution {
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        model = model == null ? "" : model;
        finalAnswer = finalAnswer == null ? "" : finalAnswer;
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }

    /**
     * 从已结束的 AgentRunContext 构建观测快照。
     */
    public static EvalExecution from(AgentRunContext ctx, long durationMillis) {
        return new EvalExecution(
                ctx.getState(),
                ctx.getCurrentStep(),
                ctx.getToolCallCount(),
                ctx.getPromptTokens(),
                ctx.getCompletionTokens(),
                ctx.getTotalTokens(),
                durationMillis,
                ctx.getModel(),
                ctx.getToolTrace(),
                ctx.getFinalAnswer(),
                extractToolResults(ctx)
        );
    }

    /**
     * 执行异常时的兜底快照，保证单条用例失败不影响整轮评测。
     */
    public static EvalExecution failed(long durationMillis, String errorMessage) {
        return new EvalExecution(
                AgentState.FAILED,
                0,
                0,
                0,
                0,
                0,
                durationMillis,
                "",
                List.of(),
                errorMessage == null ? "" : errorMessage,
                List.of()
        );
    }

    /**
     * 从对话历史里收集所有工具结果原文。
     *
     * 只读，不改上下文；单条结果解析失败时保留原样（解码器本身是安全的）。
     */
    private static List<String> extractToolResults(AgentRunContext ctx) {
        List<String> results = new ArrayList<>();

        for (Message message : ctx.getMessages()) {
            if (!(message instanceof ToolResponseMessage toolResponse)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                String data = response.responseData();
                if (data != null && !data.isBlank()) {
                    results.add(ToolResultTexts.decode(data));
                }
            }
        }

        return results;
    }
}

