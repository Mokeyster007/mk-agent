package com.example.mkagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 终止当前 Agent 任务的工具。
 */
@Component
public class TerminateTool {

    @Tool(
            name = "terminate_task",
            description = """
                    当用户任务已经完成，或者确认无法继续完成任务时调用。

                    调用该工具表示当前 Agent 任务结束。
                    status 只能是 success 或 failure。
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
            String reason
    ) {
        if (!Set.of("success", "failure").contains(status)) {
            throw new IllegalArgumentException(
                    "status 只能为 success 或 failure"
            );
        }

        return new TerminateResult(status, reason);
    }

    public record TerminateResult(
            String status,
            String reason
    ) {
    }
}