package com.example.mkagent.agent;

import com.example.mkagent.config.AgentExecutorConfig;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentEvent;
import com.example.mkagent.model.AgentEventType;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 所有 Agent 的基类。
 *
 * 主要职责：
 * 1. 创建一次任务专属的 AgentRunContext
 * 2. 初始化 SystemMessage 和 UserMessage
 * 3. 控制 Agent Loop
 * 4. 限制最大步骤数、最大工具调用次数和超时时间
 * 5. 统一处理异常和清理资源
 */
public abstract class BaseAgent {

    private static final Logger log =
            LoggerFactory.getLogger(BaseAgent.class);

    /**
     * Agent 名称，例如 YuManus。
     */
    private String name;

    /**
     * 系统提示词。
     *
     * 约束模型身份、工具使用规则、终止规则等。
     */
    private String systemPrompt;

    /**
     * 最多执行多少轮 Agent Loop。
     */
    private int maxSteps = 10;

    /**
     * 一次任务最多调用多少次工具。
     */
    private int maxToolCalls = 20;

    /**
     * 一次 Agent 任务最长允许运行多久。
     *
     * Duration.ofMinutes(2) 表示 2 分钟。
     */
    private Duration timeout = Duration.ofMinutes(2);

    private final Executor agentExecutor;

    /**
     * 运行中任务注册表（可选）。
     *
     * 由具体 Agent（MkManus）通过 setter 注入。
     * 为 null 时任务不注册（例如纯单元测试场景）。
     */
    private AgentTaskRegistry taskRegistry;

    /**
     * AgentRun 生命周期记录器（可选）。
     *
     * 由具体 Agent（MkManus）通过 setter 注入。
     * 为 null 时不做任何持久化（例如纯单元测试场景）。
     * 记录器内部已保证：数据库失败不中断任务主流程。
     */
    private AgentRunRecorder runRecorder;

    /**
     * 全局并发闸门（可选）。
     *
     * 由具体 Agent（MkManus）通过 setter 注入。
     * 为 null 时不做并发限制（例如纯单元测试场景）。
     * 许可在任务开始前获取，任何终态（成功/失败/取消/超时）
     * 后的 finally 中释放，保证不泄漏。
     */
    private AgentConcurrencyGuard concurrencyGuard;

    /**
     * 并发已满时返回给客户端的业务提示。
     */
    public static final String CONCURRENCY_LIMIT_MESSAGE =
            "当前智能体任务较多，请稍后重试。";

    protected BaseAgent(Executor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }



    /**
     * 执行一次完整 Agent 任务（同步）。
     *
     * @param userPrompt 用户本次输入的任务
     * @return 最终模型回答；若异常或达到预算，则返回执行摘要
     */
    public String run(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("用户任务不能为空");
        }

        /*
         * 全局并发控制：获取不到许可直接拒绝（429），
         * 不阻塞等待，避免请求在队列里堆积。
         * 许可在最外层 finally 释放，覆盖成功/失败/取消/超时所有场景。
         */
        acquireConcurrencyPermit();

        try {

        /*
         * 每次 run 都创建新的 ctx。
         *
         * ctx 属于“本次任务”，不能放到成员变量中，
         * 否则多个用户并发请求会互相污染消息和状态。
         */
        AgentRunContext ctx = new AgentRunContext();

        /*
         * 固化用户身份与 Agent 类型：
         * 此时仍在 Web 线程，能读到用户上下文 ThreadLocal。
         */
        ctx.setUserId(UserContextHolder.getOrDefault());
        ctx.setAgentType(getAgentType());

        ctx.transitionTo(AgentState.RUNNING);

        /*
         * 任务开始前持久化 RUNNING 记录（失败只记日志，不阻断任务）。
         */
        recordRunStart(ctx, userPrompt);

        /*
         * 第 1 条消息：SystemMessage。
         */
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ctx.getMessages().add(
                    new SystemMessage(systemPrompt)
            );
        }

        /*
         * 第 2 条消息：当前用户请求。
         */
        ctx.getMessages().add(
                new UserMessage(userPrompt)
        );

        /*
         * 记录每一轮的工具执行摘要。
         * 当任务没有得到 finalAnswer 时，可以作为兜底返回。
         */
        List<String> stepResults = new ArrayList<>();

        long startMillis = System.currentTimeMillis();

        /*
         * 失败分支的脱敏错误信息，finally 中随终态写入数据库。
         */
        String errorMessage = null;

        try {
            while (canContinue(ctx)) {
                /*
                 * 中断检测：当前线程已被中断（外部取消等）时，
                 * 不再进入下一轮 step。
                 */
                if (Thread.currentThread().isInterrupted()) {
                    ctx.transitionTo(AgentState.CANCELLED);
                    break;
                }

                ctx.nextStep();

                log.info(
                        "Agent 执行中：runId={}, step={}/{}, thread={}",
                        ctx.getRunId(),
                        ctx.getCurrentStep(),
                        maxSteps,
                        Thread.currentThread().getName()
                );

                String stepResult = step(ctx);

                /*
                 * 每完成一轮 step 同步一次数据库进度（不是每个 token）。
                 */
                recordRunProgress(ctx);

                if (stepResult != null && !stepResult.isBlank()) {
                    stepResults.add(stepResult);
                }
            }

            /*
             * 循环退出时如果仍是 RUNNING，
             * 根据具体停止原因判定终态（超时 / 成功 / 达到预算）。
             */
            finishIfStillRunning(ctx);

            if (ctx.getState() == AgentState.MAX_STEPS_REACHED) {
                stepResults.add(
                        "安全停止：达到最大步骤数或最大工具调用次数限制。"
                );
            } else if (ctx.getState() == AgentState.TIMED_OUT) {
                stepResults.add("任务运行超时，已强制停止。");
            }

            /*
             * 优先返回模型最终的自然语言回答。
             */
            if (ctx.getFinalAnswer() != null
                    && !ctx.getFinalAnswer().isBlank()) {
                return ctx.getFinalAnswer();
            }

            /*
             * 兜底：模型没有有效最终回答时，返回工具执行过程摘要。
             */
            return String.join("\n", stepResults);

        } catch (Exception e) {
            failOrCancel(ctx, e);

            errorMessage = buildErrorMessage(e);

            log.error(
                    "Agent 执行失败：runId={}, state={}, thread={}",
                    ctx.getRunId(),
                    ctx.getState(),
                    Thread.currentThread().getName(),
                    e
            );

            return "执行错误：" + e.getMessage();

        } finally {
            /*
             * 终态单点写库：成功/失败/取消/超时/达到预算都在这里落盘。
             */
            recordRunFinish(ctx, errorMessage, startMillis);

            logTaskSummary(ctx, startMillis);
            cleanup(ctx);
        }

        } finally {
            /*
             * 同步任务终态后释放并发许可。
             */
            releaseConcurrencyPermit();
        }
    }

    /**
     * 执行一次完整 Agent 任务（异步 + SSE 流式）。
     *
     * 生命周期：
     * 1. 创建 ctx 并转为 RUNNING，SSE 超时与 Agent timeout 对齐；
     * 2. 将 Agent Loop 提交给 agentExecutor 后台线程；
     * 3. 注册到 AgentTaskRegistry，供取消接口按 runId 查询；
     * 4. 任务以任何终态结束后，从注册表移除并只执行一次 cleanup。
     */
    public SseEmitter runStream(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("用户任务不能为空");
        }

        /*
         * 全局并发控制：同步段（仍在 Web 线程）获取许可，
         * 获取不到直接 429 拒绝，不创建 SSE 连接。
         *
         * 释放点：异步任务线程的 finally（见下方），
         * 覆盖成功/失败/取消/超时所有终态；
         * 若同步段在提交异步任务前抛异常，由 catch 块负责释放。
         */
        acquireConcurrencyPermit();

        try {

        /*
         * SSE HTTP 连接最长存活时间。
         * 与 Agent 任务 timeout 保持一致。
         */
        SseEmitter emitter = new SseEmitter(timeout.toMillis());

        /*
         * 每次 Agent 请求拥有独立上下文。
         * 不要将 ctx 放到 BaseAgent 成员变量。
         */
        AgentRunContext ctx = new AgentRunContext();

        /*
         * 固化用户身份与 Agent 类型：
         * runStream 同步段仍在 Web 线程，能读到用户上下文 ThreadLocal。
         */
        ctx.setUserId(UserContextHolder.getOrDefault());
        ctx.setAgentType(getAgentType());

        ctx.transitionTo(AgentState.RUNNING);

        /*
         * 任务开始前持久化 RUNNING 记录（失败只记日志，不阻断任务）。
         * 必须在提交异步任务之前完成，保证前端拿到 runId 后立即可查。
         */
        recordRunStart(ctx, userPrompt);

        /*
         * 保存本次任务的 SSE 连接。
         * 工具事件（tool_start / tool_result）等统一事件通过它推送。
         */
        ctx.setEmitter(emitter);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ctx.getMessages().add(new SystemMessage(systemPrompt));
        }

        ctx.getMessages().add(new UserMessage(userPrompt));

        /*
         * 防止 finally、onTimeout、onCompletion、取消接口重复 cleanup。
         */
        AtomicBoolean cleaned = new AtomicBoolean(false);

        String runId = ctx.getRunId().toString();
        long startMillis = System.currentTimeMillis();

        /*
         * 将完整 Agent Loop 作为一个任务提交给 agentExecutor。
         */
        /*
         * 先创建任务句柄并注册，再提交异步任务。
         *
         * 顺序很重要：注册必须发生在任务真正开始执行之前，
         * 否则存在“任务已执行完并从注册表移除，随后才被注册”的竞态窗口，
         * 导致注册表残留已结束任务（同步执行器下必然发生）。
         *
         * CompletableFuture.cancel(true) 不会中断执行线程，
         * 取消/超时依赖 ctx 中记录的执行线程显式中断。
         */
        CompletableFuture<Void> task = new CompletableFuture<>();

        if (taskRegistry != null) {
            taskRegistry.register(runId, new RunningAgentTask(ctx, task));
        }

        CompletableFuture.runAsync(() -> {
            /*
             * 记录执行线程：取消/超时需要显式中断该线程。
             */
            ctx.setExecutingThread(Thread.currentThread());

            /*
             * 失败分支的脱敏错误信息，finally 中随终态写入数据库。
             */
            String errorMessage = null;

            try {
                sendEvent(ctx, AgentEventType.STATUS, "Agent 已开始执行");

                /*
                 * 保留 run_id 事件兼容旧前端；
                 * 新协议下每条 AgentEvent 本身都携带 runId。
                 */
                sendEvent(
                        emitter,
                        AgentEvent.of(
                                runId,
                                AgentEventType.RUN_ID,
                                runId,
                                ctx.getCurrentStep()
                        )
                );

                while (canContinue(ctx)) {
                    /*
                     * 中断检测：取消或超时已中断当前线程时，
                     * 不再进入下一轮 step。
                     */
                    if (Thread.currentThread().isInterrupted()) {
                        ctx.transitionTo(AgentState.CANCELLED);
                        break;
                    }

                    ctx.nextStep();

                    log.info(
                            "Agent 流式执行中：runId={}, step={}/{}, thread={}",
                            runId,
                            ctx.getCurrentStep(),
                            maxSteps,
                            Thread.currentThread().getName()
                    );

                    String stepResult = step(ctx);

                    /*
                     * 每完成一轮 step 同步一次数据库进度（不是每个 token）。
                     */
                    recordRunProgress(ctx);

                    if (stepResult != null && !stepResult.isBlank()) {
                        sendEvent(
                                emitter,
                                AgentEvent.of(
                                        runId,
                                        AgentEventType.STEP,
                                        stepResult,
                                        ctx.getCurrentStep()
                                )
                        );
                    }
                }

                /*
                 * 循环退出但仍是 RUNNING：
                 * 根据具体原因判定终态（超时 / 成功 / 达到预算）。
                 */
                if (ctx.getState() == AgentState.RUNNING) {
                    finishIfStillRunning(ctx);

                    if (ctx.getState() == AgentState.TIMED_OUT) {
                        sendEvent(
                                ctx,
                                AgentEventType.STATUS,
                                "任务运行超时，已强制停止。"
                        );
                    } else if (ctx.getState() == AgentState.MAX_STEPS_REACHED) {
                        sendEvent(
                                ctx,
                                AgentEventType.STATUS,
                                "安全停止：达到最大步骤数或最大工具调用次数限制。"
                        );
                    }
                }

                /*
                 * 仅在成功完成且有最终回答时推送 final_answer。
                 */
                if (ctx.getState() == AgentState.SUCCEEDED
                        && ctx.getFinalAnswer() != null
                        && !ctx.getFinalAnswer().isBlank()) {
                    sendEvent(
                            emitter,
                            AgentEvent.of(
                                    runId,
                                    AgentEventType.FINAL_ANSWER,
                                    ctx.getFinalAnswer(),
                                    ctx.getCurrentStep()
                            )
                    );
                }

                sendEvent(
                        emitter,
                        AgentEvent.of(
                                runId,
                                AgentEventType.DONE,
                                "[DONE]",
                                ctx.getCurrentStep()
                        )
                );

                /*
                 * 重要顺序：此时状态必已是终态（上方已保证），
                 * 再调用 emitter.complete()。
                 * 否则 onCompletion 会看到 RUNNING，
                 * 把正常完成误判为客户端断开并改成 CANCELLED。
                 */
                emitter.complete();

            } catch (Exception e) {
                /*
                 * 区分中断（取消/超时）与真实异常。
                 * 若取消接口或 onTimeout 已先行设置终态，
                 * transitionTo 会被拒绝，原有终态保持不变。
                 */
                failOrCancel(ctx, e);

                errorMessage = buildErrorMessage(e);

                log.error(
                        "Agent 流式执行失败：runId={}, state={}, thread={}",
                        runId,
                        ctx.getState(),
                        Thread.currentThread().getName(),
                        e
                );

                /*
                 * 错误事件只发送脱敏提示，
                 * 异常详情只记录在服务端日志，不外发堆栈。
                 */
                sendEvent(
                        emitter,
                        AgentEvent.of(
                                runId,
                                AgentEventType.ERROR,
                                "智能体执行失败，请稍后重试。",
                                ctx.getCurrentStep()
                        )
                );

                emitter.complete();

            } finally {
                /*
                 * 终态单点写库：成功/失败/取消/超时/达到预算都在这里落盘。
                 * 取消接口与 onTimeout 不单独写库，避免并发写冲突。
                 */
                recordRunFinish(ctx, errorMessage, startMillis);

                logTaskSummary(ctx, startMillis);
                cleanupOnce(ctx, cleaned);
                unregisterTask(runId);

                /*
                 * 任务终态后释放并发许可（异步线程释放，
                 * Semaphore 支持跨线程释放）。
                 */
                releaseConcurrencyPermit();
            }

        }, agentExecutor).whenComplete((result, error) -> {
            /*
             * 异步任务真正执行完后，把结果回写到预先注册的句柄。
             * 若句柄已被取消接口 cancel，这里的 complete 会静默失败，无副作用。
             */
            if (error != null) {
                task.completeExceptionally(error);
            } else {
                task.complete(null);
            }
        });

        emitter.onTimeout(() -> {
            /*
             * SSE 连接超时：优先把任务标记为 TIMED_OUT。
             * 若任务线程已先到达其他终态，本调用会被拒绝，不会覆盖。
             */
            ctx.transitionTo(AgentState.TIMED_OUT);

            log.warn(
                    "SSE 连接超时：runId={}, state={}, step={}, thread={}, duration={}ms",
                    runId,
                    ctx.getState(),
                    ctx.getCurrentStep(),
                    Thread.currentThread().getName(),
                    System.currentTimeMillis() - startMillis
            );

            /*
             * 尝试中断后台 Agent 任务。
             *
             * 注意 1：CompletableFuture.cancel(true) 不会中断执行线程，
             * 必须再显式中断 ctx 中记录的执行线程；
             * 注意 2：不保证底层模型 HTTP 调用一定立即停止。
             */
            task.cancel(true);
            ctx.interruptExecutingThread();

            cleanupOnce(ctx, cleaned);
            unregisterTask(runId);
        });

        emitter.onCompletion(() -> {
            /*
             * 完成时仍是 RUNNING，说明任务没有正常走到终态，
             * 通常是客户端提前断开，视为取消。
             *
             * 正常完成时，任务线程已在 emitter.complete() 之前
             * 设置 SUCCEEDED（或其他终态），不会进入本分支，
             * 这是避免把正常完成误判为 CANCELLED 的关键。
             */
            if (ctx.getState() == AgentState.RUNNING) {
                ctx.transitionTo(AgentState.CANCELLED);

                log.warn(
                        "客户端提前断开，任务已取消：runId={}, state={}, step={}, thread={}, duration={}ms",
                        runId,
                        ctx.getState(),
                        ctx.getCurrentStep(),
                        Thread.currentThread().getName(),
                        System.currentTimeMillis() - startMillis
                );

                task.cancel(true);
                ctx.interruptExecutingThread();
            } else {
                log.info(
                        "SSE 连接完成：runId={}, state={}",
                        runId,
                        ctx.getState()
                );
            }

            cleanupOnce(ctx, cleaned);
            unregisterTask(runId);
        });

        return emitter;

        } catch (RuntimeException e) {
            /*
             * 同步段在异步任务提交成功前失败（例如线程池拒绝）：
             * 此时异步 finally 不会执行，必须由本处释放许可。
             */
            releaseConcurrencyPermit();
            throw e;
        }
    }

    /**
     * 判断当前任务是否允许继续进入下一轮 Agent Loop。
     */
    private boolean canContinue(AgentRunContext ctx) {
        return ctx.getState() == AgentState.RUNNING
                && ctx.getCurrentStep() < maxSteps
                && ctx.getToolCallCount() < maxToolCalls
                && Duration.between(
                ctx.getStartedAt(),
                Instant.now()
        ).compareTo(timeout) < 0;
    }

    /**
     * 执行一轮 Agent 工作。
     *
     * BaseAgent 不实现它；
     * ReActAgent 会重写它。
     */
    protected abstract String step(AgentRunContext ctx);

    /**
     * 任务结束后的清理钩子。
     *
     * 后续可以放：
     * 1. 临时文件清理
     * 2. 浏览器资源关闭
     * 3. AgentRun 日志/数据库记录
     * 4. 临时目录删除
     */
    protected void cleanup(AgentRunContext ctx) {
    }

    // ===== 状态机辅助方法 =====

    /**
     * 循环退出时若仍是 RUNNING，根据具体停止原因判定终态：
     *
     * 1. 已超时              → TIMED_OUT
     * 2. 已有有效最终回答      → SUCCEEDED（子类未显式设状态时的兜底）
     * 3. 其余（达到步数/工具上限）→ MAX_STEPS_REACHED
     *
     * 注意区分：maxSteps / maxToolCalls 与 timeout 都会让 canContinue 返回 false，
     * 必须在这里根据实际耗时和上下文区分具体原因。
     */
    private void finishIfStillRunning(AgentRunContext ctx) {
        if (ctx.getState() != AgentState.RUNNING) {
            return;
        }

        boolean timedOut = Duration.between(ctx.getStartedAt(), Instant.now())
                .compareTo(timeout) >= 0;

        if (timedOut) {
            ctx.transitionTo(AgentState.TIMED_OUT);
            return;
        }

        if (ctx.getFinalAnswer() != null && !ctx.getFinalAnswer().isBlank()) {
            ctx.transitionTo(AgentState.SUCCEEDED);
            return;
        }

        ctx.transitionTo(AgentState.MAX_STEPS_REACHED);
    }

    /**
     * 异常分支的状态判定：
     * 1. 当前线程已被中断，或异常链中包含 InterruptedException → CANCELLED；
     * 2. 其余真实异常 → FAILED。
     *
     * 若取消接口 / onTimeout 已先行设置终态，
     * transitionTo 会拒绝本次变更，原有终态保持不变。
     */
    private void failOrCancel(AgentRunContext ctx, Exception e) {
        if (Thread.currentThread().isInterrupted() || causedByInterruption(e)) {
            ctx.transitionTo(AgentState.CANCELLED);
        } else {
            ctx.transitionTo(AgentState.FAILED);
        }
    }

    /**
     * 判断异常链中是否包含 InterruptedException（中断导致的失败）。
     */
    private boolean causedByInterruption(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 任务汇总日志：runId、终态、步数、工具调用次数、线程名、总耗时。
     */
    private void logTaskSummary(AgentRunContext ctx, long startMillis) {
        log.info(
                "Agent 任务结束：runId={}, state={}, step={}, toolCallCount={}, thread={}, totalDuration={}ms",
                ctx.getRunId(),
                ctx.getState(),
                ctx.getCurrentStep(),
                ctx.getToolCallCount(),
                Thread.currentThread().getName(),
                System.currentTimeMillis() - startMillis
        );
    }

    /**
     * 从注册表移除已结束的任务（注册表未注入时为空操作）。
     */
    private void unregisterTask(String runId) {
        if (taskRegistry != null) {
            taskRegistry.remove(runId);
        }
    }

    // ===== 全局并发控制 =====

    /**
     * 获取全局并发许可；已满时抛 429 业务异常。
     * 闸门未注入（纯单测场景）时不做限制。
     */
    private void acquireConcurrencyPermit() {
        if (concurrencyGuard != null && !concurrencyGuard.tryAcquire()) {
            throw new BusinessException(429, CONCURRENCY_LIMIT_MESSAGE);
        }
    }

    /**
     * 释放全局并发许可；只能在成功获取后调用一次。
     */
    private void releaseConcurrencyPermit() {
        if (concurrencyGuard != null) {
            concurrencyGuard.release();
        }
    }

    // ===== AgentRun 持久化钩子（记录器未注入时均为空操作） =====

    /**
     * 当前 Agent 的类型，用于 agent_run 持久化。
     * 具体 Agent（如 MkManus）重写；默认 null 时落库为 UNKNOWN。
     */
    protected AgentType getAgentType() {
        return null;
    }

    private void recordRunStart(AgentRunContext ctx, String userPrompt) {
        if (runRecorder != null) {
            runRecorder.recordStart(ctx, userPrompt);
        }
    }

    private void recordRunProgress(AgentRunContext ctx) {
        if (runRecorder != null) {
            runRecorder.recordProgress(ctx);
        }
    }

    private void recordRunFinish(
            AgentRunContext ctx,
            String errorMessage,
            long startMillis
    ) {
        if (runRecorder != null) {
            runRecorder.recordFinish(
                    ctx,
                    errorMessage,
                    System.currentTimeMillis() - startMillis
            );
        }
    }

    /**
     * 构造脱敏错误信息：只保留异常类型与简短消息并截断，
     * 不包含堆栈，避免敏感信息落库。
     *
     * step 层会把真实异常包装成 RuntimeException（“Agent 第 X 步执行失败”），
     * 因此这里递归解包到根因，保证库中能看到真实失败类型。
     */
    private String buildErrorMessage(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String raw = root.getClass().getSimpleName()
                + (message == null ? "" : "：" + message);
        return raw.length() <= 500 ? raw : raw.substring(0, 500);
    }

    // ===== Getter / Setter =====

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * 发送统一 SSE 事件。
     *
     * event name 与 data 中的 type 字段保持一致，
     * 前端既可以用 EventSource 命名事件精确监听，
     * 也可以直接反序列化 data 中的 AgentEvent。
     */
    public void sendEvent(SseEmitter emitter, AgentEvent event) {

        try {
            emitter.send(
                    SseEmitter.event()
                            .name(event.getType())
                            .data(event)
            );
        } catch (IOException e) {
            // 客户端已断开：发送失败是正常场景，不影响任务主流程。
            log.warn("SSE 事件发送失败（连接可能已断开）：event={}", event.getType());
        } catch (IllegalStateException e) {
            // emitter 已被 complete（例如取消接口已关闭连接），跳过本次发送。
            log.debug("SSE emitter 已关闭，跳过事件：event={}", event.getType());
        }
    }

    /**
     * 基于任务上下文发送统一 SSE 事件。
     *
     * 自动从 ctx 填充 runId 与当前步数；
     * 同步 run()（无 emitter）场景下为空操作。
     */
    protected void sendEvent(
            AgentRunContext ctx,
            AgentEventType type,
            String message
    ) {
        SseEmitter emitter = ctx.getEmitter();
        if (emitter == null) {
            return;
        }

        sendEvent(
                emitter,
                AgentEvent.of(
                        ctx.getRunId().toString(),
                        type,
                        message,
                        ctx.getCurrentStep()
                )
        );
    }

    public AgentTaskRegistry getTaskRegistry() {
        return taskRegistry;
    }

    /**
     * 注入运行中任务注册表（由具体 Agent 的构造器转交）。
     */
    public void setTaskRegistry(AgentTaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    /**
     * 注入 AgentRun 生命周期记录器（由具体 Agent 的构造器转交）。
     */
    public void setRunRecorder(AgentRunRecorder runRecorder) {
        this.runRecorder = runRecorder;
    }

    /**
     * 注入全局并发闸门（由具体 Agent 的构造器转交）。
     */
    public void setConcurrencyGuard(AgentConcurrencyGuard concurrencyGuard) {
        this.concurrencyGuard = concurrencyGuard;
    }

    public AgentConcurrencyGuard getConcurrencyGuard() {
        return concurrencyGuard;
    }


    public void cleanupOnce(AgentRunContext ctx, AtomicBoolean cleaned) {

        if(cleaned.compareAndSet(false,true)) {
            cleanup(ctx);
        }
    }
}