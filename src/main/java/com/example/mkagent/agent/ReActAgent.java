package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;

import java.util.concurrent.Executor;

/**
 * ReActAgent 规定每一轮 Agent 的固定执行流程：
 *
 * Think（思考）
 *   ↓
 * Act（行动）
 *   ↓
 * Observe（观察）
 *
 * Observe 没有单独定义为方法，
 * 因为 ToolCallAgent.act(ctx) 在执行工具后，
 * 会将 ToolResponseMessage 写回 ctx.messages。
 */
public abstract class ReActAgent extends BaseAgent {

    public ReActAgent(Executor agentExecutor) {
        super(agentExecutor);
    }

    /**
     * 思考阶段。
     *
     * 调用模型，判断模型是否请求调用工具。
     *
     * @return true：模型请求工具，需要继续 act(ctx)
     *         false：模型没有请求工具，通常已经给出最终文本回答
     */
    protected abstract boolean think(AgentRunContext ctx);

    /**
     * 行动阶段。
     *
     * 真正执行模型请求的工具。
     */
    protected abstract String act(AgentRunContext ctx);

    /**
     * 定义一轮 ReAct 的固定顺序。
     *
     * 这里是模板方法模式：
     * ReActAgent 固定流程，
     * ToolCallAgent 实现具体的 think 和 act。
     */
    @Override
    protected String step(AgentRunContext ctx) {
        try {
            boolean shouldAct = think(ctx);

            if (!shouldAct) {
                return "模型本轮未请求工具，已得到最终回答。";
            }

            return act(ctx);

        } catch (Exception e) {
            /*
             * 不在这里吞掉异常。
             *
             * 继续抛给 BaseAgent.run()，
             * 由 BaseAgent 统一：
             * 1. 设置 ERROR 状态
             * 2. 打印完整日志
             * 3. 结束当前任务
             */
            throw new RuntimeException(
                    "Agent 第 " + ctx.getCurrentStep() + " 步执行失败",
                    e
            );
        }
    }
}