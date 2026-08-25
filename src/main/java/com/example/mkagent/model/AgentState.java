package com.example.mkagent.model;

/**
 * Agent 任务状态机。
 *
 * 状态分两类：
 * 1. 过程态：IDLE、RUNNING，任务仍在推进；
 * 2. 终态：SUCCEEDED、FAILED、CANCELLED、TIMED_OUT、MAX_STEPS_REACHED，
 *    任务已经结束，不允许再被修改。
 *
 * 合法流转（由 AgentRunContext.transitionTo 强制约束）：
 *
 * IDLE ──→ RUNNING
 * RUNNING ──→ SUCCEEDED          模型给出有效最终回答
 * RUNNING ──→ FAILED             模型/工具/系统异常
 * RUNNING ──→ CANCELLED          用户主动取消或客户端提前断开
 * RUNNING ──→ TIMED_OUT          超过 Agent 任务超时时间
 * RUNNING ──→ MAX_STEPS_REACHED  达到最大步骤数或最大工具调用次数
 *
 * 任何终态都不允许再转换到其他状态（包括重新变回 RUNNING），
 * 避免已结束的任务被误判、被覆盖或被重复处理。
 */
public enum AgentState {

    /** 初始状态：任务已创建但尚未开始执行。 */
    IDLE,

    /** 任务正在 Agent Loop 中执行。 */
    RUNNING,

    /** 任务成功完成，且已经得到有效的最终回答。 */
    SUCCEEDED,

    /** 任务失败：模型调用、工具执行或系统内部出现异常。 */
    FAILED,

    /** 任务被取消：用户主动停止，或客户端提前断开 SSE 连接。 */
    CANCELLED,

    /** 任务超时：运行时间超过 Agent 配置的 timeout。 */
    TIMED_OUT,

    /** 任务被安全停止：达到最大步骤数或最大工具调用次数。 */
    MAX_STEPS_REACHED;

    /**
     * 是否为终态。
     *
     * 终态表示任务生命周期已经结束。一旦进入终态，
     * AgentRunContext.transitionTo 会拒绝任何后续状态变更，
     * 从而避免：
     * 1. 已结束的任务被重新标记为 RUNNING；
     * 2. SUCCEEDED 被 onCompletion 误判为 CANCELLED；
     * 3. 取消接口与任务线程竞态时互相覆盖状态。
     */
    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == FAILED
                || this == CANCELLED
                || this == TIMED_OUT
                || this == MAX_STEPS_REACHED;
    }
}
