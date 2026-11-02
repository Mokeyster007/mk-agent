package com.example.mkagent.model;

import java.util.concurrent.CompletableFuture;

/**
 * 注册表中一条"运行中任务"的记录。
 *
 * 至少保存三样东西：
 * 1. AgentRunContext：本次任务的完整上下文（状态、消息、runId、耗时起点等），
 *    取消接口通过它读取当前状态、向 SSE 客户端发送取消事件；
 * 2. CompletableFuture：本次任务在 agentExecutor 上的异步句柄，
 *    取消接口通过 future.cancel(true) 中断后台执行线程；
 * 3. epoch：本次执行的代次。
 *
 * 为什么需要 epoch：
 * 审批恢复会给同一个 runId 创建**新**的执行句柄。
 * 旧执行线程的 finally / onCompletion / onTimeout 如果按 runId 无条件删除，
 * 会把刚注册的新句柄删掉，导致取消接口找不到任务、并发许可被重复释放。
 * 带上代次后，"旧句柄只能删自己"成为可验证的约束。
 *
 * 使用 record 保证不可变：记录一旦创建，引用不会被偷偷替换。
 *
 * @param context 本次任务的运行上下文
 * @param future  本次任务的异步执行句柄
 * @param epoch   本次执行的代次（首次 0，每次恢复 +1）
 */
public record RunningAgentTask(
        AgentRunContext context,
        CompletableFuture<Void> future,
        int epoch
) {

    /**
     * 兼容首次执行（epoch=0）的简写。
     */
    public RunningAgentTask(AgentRunContext context, CompletableFuture<Void> future) {
        this(context, future, 0);
    }
}
