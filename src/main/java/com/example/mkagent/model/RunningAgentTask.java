package com.example.mkagent.model;

import java.util.concurrent.CompletableFuture;

/**
 * 注册表中一条"运行中任务"的记录。
 *
 * 至少保存两样东西：
 * 1. AgentRunContext：本次任务的完整上下文（状态、消息、runId、耗时起点等），
 *    取消接口通过它读取当前状态、向 SSE 客户端发送取消事件；
 * 2. CompletableFuture：本次任务在 agentExecutor 上的异步句柄，
 *    取消接口通过 future.cancel(true) 中断后台执行线程。
 *
 * 使用 record 保证不可变：记录一旦创建，引用不会被偷偷替换。
 *
 * @param context 本次任务的运行上下文
 * @param future  本次任务的异步执行句柄
 */
public record RunningAgentTask(
        AgentRunContext context,
        CompletableFuture<Void> future
) {
}
