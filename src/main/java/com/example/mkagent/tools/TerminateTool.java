package com.example.mkagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 终止当前 Agent 任务的工具。
 *
 * ================== 为什么要加 final_answer ==================
 *
 * 真实运行里模型经常在**同一轮**既输出说明文字、又调用 terminate_task。
 * 旧的终止逻辑只看工具结果，于是 final_answer 变成了
 * "任务结束：任务结束已确认"——用户什么答案都拿不到。
 *
 * 现在 terminate_task 自己携带 final_answer：
 * 模型必须把"给用户看的完整回答"作为参数交过来，
 * 而不是指望框架从工具摘要里拼出来。
 *
 * 参数名说明：Java 侧用 finalAnswer（驼峰，符合本项目风格），
 * 工具 schema 里的字段名随之是 finalAnswer；
 * 读取侧（ToolCallAgent）同时兼容 final_answer 与 finalAnswer 两种写法，
 * 避免模型按直觉写 snake_case 时丢答案。
 */
@Component
public class TerminateTool {

    /**
     * final_answer 的最大长度。
     *
     * 4000 字符足够写"结论 + 要点 + 来源链接"，
     * 又不会把一次终止变成一次超长输出（成本与上下文都要控）。
     * 落库上限是 20000（AgentRunRecorder），这里先收窄。
     */
    public static final int MAX_FINAL_ANSWER_LENGTH = 4000;

    @Tool(
            name = "terminate_task",
            description = """
                    当用户任务已经完成，或者确认无法继续完成任务时调用。

                    调用该工具表示当前 Agent 任务结束。
                    status 只能是 success 或 failure。

                    final_answer 是给用户的完整最终回答：
                    必须能独立读懂，包含结论、关键要点与来源链接。
                    不要写"任务完成"这类空话——用户只会看到 final_answer。
                    """
    )
    public TerminateResult doTerminate(
            @ToolParam(
                    description = "任务结束状态，只能是 success 或 failure"
            )
            String status,

            @ToolParam(
                    description = "任务完成或失败的简短原因，最多 200 字"
            )
            String reason,

            @ToolParam(
                    required = false,
                    description = """
                            给用户的完整最终回答，最多 4000 字。
                            任务成功时必须包含：结论、关键要点、
                            以及来自 web_search / web_scrape 实际返回结果的来源链接。
                            任务失败时说明卡在哪里、已尝试过什么。
                            """
            )
            String finalAnswer
    ) {
        if (!Set.of("success", "failure").contains(status)) {
            throw new IllegalArgumentException(
                    "status 只能为 success 或 failure"
            );
        }

        return new TerminateResult(
                status,
                reason,
                truncate(finalAnswer)
        );
    }

    /**
     * 截断标记：告诉模型/用户"这里被截了"，而不是让人以为答案就到此为止。
     */
    private static final String TRUNCATED_SUFFIX = "…（回答过长已截断）";

    /**
     * 超长时截断并显式标注，避免模型以为答案完整。
     *
     * 注意截断长度要扣掉标记本身的长度，
     * 保证返回值**始终**不超过 {@link #MAX_FINAL_ANSWER_LENGTH}
     * （否则"上限"就成了假的，测试也不好断言）。
     */
    private String truncate(String finalAnswer) {
        if (finalAnswer == null) {
            return null;
        }
        String trimmed = finalAnswer.trim();
        if (trimmed.length() <= MAX_FINAL_ANSWER_LENGTH) {
            return trimmed;
        }
        int keep = MAX_FINAL_ANSWER_LENGTH - TRUNCATED_SUFFIX.length();
        return trimmed.substring(0, keep) + TRUNCATED_SUFFIX;
    }

    public record TerminateResult(
            String status,
            String reason,
            String finalAnswer
    ) {
    }
}
