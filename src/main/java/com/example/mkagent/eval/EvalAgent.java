package com.example.mkagent.eval;

import com.example.mkagent.agent.MkManus;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 评测专用 MkManus 实例。
 *
 * 复用生产 MkManus 的：
 * 1. 系统提示词与预算；
 * 2. MANUS 工具白名单；
 * 3. ToolCallAgent 的 think / act 流程。
 *
 * 与生产实例的差异（仅限评测隔离）：
 * 1. AgentType=EVAL；
 * 2. 不注入 AgentTaskRegistry：评测任务不进入线上运行任务注册表；
 * 3. 不注入 AgentRunRecorder：评测前后不会写入 / 污染 agent_run 表；
 * 4. 使用独立的评测并发闸门（由 AgentEvaluator 创建）。
 *
 * 该 Agent 不是 Spring Bean，每次评测由 AgentEvaluator 手动创建，
 * 避免把外部可传 ctx 的评测调用暴露到生产单例上。
 */
public class EvalAgent extends MkManus {

    public EvalAgent(
            ChatModel chatModel,
            ToolCallback[] tools,
            ToolEventMessageProvider toolEventMessageProvider,
            AgentConcurrencyGuard evalConcurrencyGuard
    ) {
        /*
         * 第 4、6 个参数分别是 AgentTaskRegistry 与 AgentRunRecorder，
         * 评测场景显式传 null，从源头保证不注册、不落库。
         * 同步 run() 不使用 Executor，因此这里用直接执行器。
         */
        super(
                chatModel,
                tools,
                Runnable::run,
                null,
                toolEventMessageProvider,
                null,
                evalConcurrencyGuard
        );
    }

    @Override
    protected AgentType getAgentType() {
        return AgentType.EVAL;
    }
}
