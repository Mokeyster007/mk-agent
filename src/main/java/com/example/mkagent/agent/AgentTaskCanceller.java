package com.example.mkagent.agent;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentEvent;
import com.example.mkagent.model.AgentEventType;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.RunningAgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 任务取消执行器：封装"取消一个运行中任务"的完整动作。
 *
 * 从 AiController 抽取，供两处复用：
 * 1. 旧接口 POST /ai/manus/{runId}/cancel（按注册表直接取消）；
 * 2. 新接口 POST /ai/runs/{runId}/cancel（先做数据库归属校验再取消）。
 *
 * 取消动作包含：
 * 1. 状态置为 CANCELLED（transitionTo 保证不被后到的终态覆盖）；
 * 2. future.cancel(true) + 显式中断执行线程
 *    （CompletableFuture.cancel 不会中断线程，必须双管齐下）；
 * 3. 尝试向 SSE 客户端推送 cancelled 事件并关闭连接。
 *
 * 注册表移除由任务线程的 finally 完成，这里不重复移除。
 */
@Component
public class AgentTaskCanceller {

    private static final Logger log =
            LoggerFactory.getLogger(AgentTaskCanceller.class);

    /**
     * 取消结果：终态名与提示消息。
     */
    public record CancelResult(String state, String message) {
    }

    /**
     * 取消一个运行中的任务。
     *
     * @throws BusinessException 409 任务已是终态或状态竞态下无法取消
     */
    public CancelResult cancel(RunningAgentTask task) {
        AgentRunContext ctx = task.context();

        if (ctx.getState().isTerminal()) {
            throw new BusinessException(
                    409,
                    "任务已结束，无法取消。当前状态：" + ctx.getState()
            );
        }

        /*
         * 先把状态置为 CANCELLED。
         * 若任务线程刚好在此瞬间完成任务，
         * transitionTo 返回 false，同样视为无法取消。
         */
        boolean cancelled = ctx.transitionTo(AgentState.CANCELLED);
        if (!cancelled) {
            throw new BusinessException(
                    409,
                    "任务状态已变更，无法取消。当前状态：" + ctx.getState()
            );
        }

        /*
         * 中断后台线程，使 Agent Loop 中的中断检测生效。
         */
        CompletableFuture<Void> future = task.future();
        if (future != null) {
            future.cancel(true);
        }
        ctx.interruptExecutingThread();

        /*
         * 尝试通知 SSE 客户端。客户端可能已断开，发送失败则忽略。
         * cancelled 事件使用统一 AgentEvent DTO。
         */
        SseEmitter emitter = ctx.getEmitter();
        if (emitter != null) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name("cancelled")
                                .data(AgentEvent.of(
                                        ctx.getRunId().toString(),
                                        AgentEventType.CANCELLED,
                                        "任务已取消",
                                        ctx.getCurrentStep()
                                ))
                );
            } catch (IOException e) {
                log.warn("取消事件发送失败（SSE 可能已断开）：runId={}",
                        ctx.getRunId());
            } catch (IllegalStateException e) {
                log.debug("SSE emitter 已关闭，跳过取消事件：runId={}",
                        ctx.getRunId());
            }
            emitter.complete();
        }

        log.info(
                "任务已取消：runId={}, step={}, thread={}",
                ctx.getRunId(),
                ctx.getCurrentStep(),
                Thread.currentThread().getName()
        );

        return new CancelResult(ctx.getState().name(), "任务已取消");
    }
}
