package com.example.mkagent.eval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;

import java.util.List;

/**
 * 一条用例执行完成后的观测快照。
 *
 * 数据全部来自 AgentRunContext，不读取数据库：
 * state / step / toolCallCount / token / 模型名 / 工具名轨迹 / 最终回答。
 * 耗时由评测器在 run() 外层测量。
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
        String finalAnswer
) {

    public EvalExecution {
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        model = model == null ? "" : model;
        finalAnswer = finalAnswer == null ? "" : finalAnswer;
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
                ctx.getFinalAnswer()
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
                errorMessage == null ? "" : errorMessage
        );
    }
}
