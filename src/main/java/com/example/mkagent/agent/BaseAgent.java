package com.example.mkagent.agent;

import com.example.mkagent.config.AgentExecutorConfig;
import com.example.mkagent.approval.ApprovalClaimService;
import com.example.mkagent.approval.ApprovalExecutionCompletionService;
import com.example.mkagent.approval.RecoveryGate;
import com.example.mkagent.approval.RunHandoffLocks;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.conversation.ConversationSeed;
import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.exception.ModelCallException;
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
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * SSE 缓冲的默认值：30 秒。
     *
     * 依据：超时收尾要发 2~3 条事件（STATUS / DONE），
     * 正常情况下几十毫秒内发完；30 秒足够覆盖"客户端网络抖动 +
     * 反代缓冲 + 服务端 GC 停顿"，又不会让一个已无用的连接长期占着。
     */
    public static final Duration DEFAULT_SSE_BUFFER = Duration.ofSeconds(30);

    /**
     * SSE 缓冲下限：1 秒。
     *
     * 再小就等于没有缓冲——一条事件的网络往返都不止 1 秒的情况很常见，
     * 缓冲小于一个 RTT 时，超时事件仍然会在路上被掐断。
     */
    public static final Duration MIN_SSE_BUFFER = Duration.ofSeconds(1);

    /**
     * SSE 缓冲上限：5 分钟。
     *
     * 缓冲过大意味着"任务早就结束了，连接还挂着"：
     * 容器线程 / socket 一直被占用，客户端也早已放弃，
     * 只是把资源泄漏的时间拉长。
     */
    public static final Duration MAX_SSE_BUFFER = Duration.ofMinutes(5);

    /**
     * SSE 连接在 Agent 超时之后**额外保留**的时间（默认 30 秒）。
     *
     * 为什么必须有这个缓冲：
     * SseEmitter 的超时与 Agent 的 timeout 原本是同一个时刻到期的。
     * Agent 超时后还要做三件事——判定 TIMED_OUT、推送 STATUS 事件、
     * 推送 DONE 并 complete()——而容器的异步超时回调可能先一步到达，
     * 结果就是"超时事件永远发不出去"，前端只能看到连接被掐断。
     *
     * 加上缓冲之后：
     *   emitter 超时 = agent timeout + buffer
     * Agent 内部仍按原来的 timeout 判 TIMED_OUT，
     * 只是把"连接还能活多久"往后推了一段，给收尾事件留出发送窗口。
     */
    private Duration sseBuffer = DEFAULT_SSE_BUFFER;

    private final Executor agentExecutor;

    /**
     * 运行中任务注册表（可选）。
     *
     * 由具体 Agent（MkManus）通过 setter 注入。
     * 为 null 时任务不注册（例如纯单元测试场景）。
     */
    private AgentTaskRegistry taskRegistry;

    /**
     * 执行权交接锁（可选，M3）。
     *
     * 暂停与批准都要先拿它：保证"暂停提交 → 释放许可 → 注销"与
     * "批准 → 取许可 → 提交执行"两段不会重叠。
     * 为 null 时不做交接保护（纯单元测试场景）。
     */
    private RunHandoffLocks handoffLocks;

    /**
     * 审批领取状态机（M3 批次 2）。
     *
     * 恢复执行前必须先用它做"启动标记"（CLAIMED→RUNNING，独立事务提交），
     * 0 行就说明这次领取已失效，一个模型调用都不能发。
     * 为 null 时（纯单测/普通任务）不做任何标记。
     */
    private ApprovalClaimService approvalClaimService;

    /**
     * 恢复执行的终态写入器（run 终态 + 审批 FINISHED 同一事务）。
     *
     * 为 null 时退化为"先 recordFinish、再 finish"，顺序仍然正确，
     * 但两条写入不保证原子；生产装配不会为 null。
     */
    private ApprovalExecutionCompletionService approvalCompletionService;

    /**
     * 启动收尾门禁（批次 4B，可选）。
     *
     * 收尾期间必须拒绝"创建/启动任务"的入口，否则新任务会落进扫描窗口，
     * 或者与扫描器争同一条 run。为 null 时不设门禁（纯单测/未启用收尾）。
     */
    private RecoveryGate recoveryGate;

    /**
     * AgentRun 生命周期记录器（可选）。
     *
     * 由具体 Agent（MkManus）通过 setter 注入。
     * 为 null 时不做任何持久化（例如纯单元测试场景）。
     * 记录器内部已保证：数据库失败不中断任务主流程。
     */
    private AgentRunRecorder runRecorder;

    /**
     * 会话读写（可选）。
     *
     * <p>为 null 时不做会话落库——纯单元测试与老的独立任务入口都不受影响。
     * 只在终态持久化成功后调用一次，且 (run_id, role) 唯一，重复调用是更新。
     *
     * <p>为什么是 protected 而不是 private：子类（ToolCallAgent）要在工具执行后
     * 立刻把业务引用落一次库。**不要**在子类里再声明一个同名字段——
     * 那会遮蔽这个字段，setter 变成子类的 override，
     * 结果就是"注入进去了，但基类看到的一直是 null"（踩过）。
     */
    protected ConversationService conversationService;

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
         * 默认入口保持原有行为：每次 run 都创建新的 ctx。
         *
         * ctx 属于“本次任务”，不能放到成员变量中，
         * 否则多个用户并发请求会互相污染消息和状态。
         */
        return run(userPrompt, new AgentRunContext());
    }

    /**
     * 执行一次完整 Agent 任务（同步），并使用调用方传入的上下文。
     *
     * 与 {@link #run(String)} 的唯一区别是 ctx 由调用方创建：
     * 离线评测（AgentEvaluator）需要在 run 结束后读取
     * state / step / toolCallCount / token / toolTrace 等指标，
     * 因此不能使用 run 内部创建后即丢失的 ctx。
     *
     * 约束：
     * 1. 调用方必须传入一个新的、未被复用的 ctx；
     * 2. 该方法与 run(String) 共用同一套状态机、预算、
     *    并发闸门、持久化与清理逻辑，不改变既有行为；
     * 3. 同一个 ctx 不允许被并发执行。
     *
     * @param userPrompt 用户本次输入的任务
     * @param ctx        本次任务专属上下文（不能为 null）
     * @return 最终模型回答；若异常或达到预算，则返回执行摘要
     */
    public String run(String userPrompt, AgentRunContext ctx) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("用户任务不能为空");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("AgentRunContext 不能为空");
        }

        /*
         * 启动收尾门禁：必须在取并发许可之前判断。
         * 先取许可再拒绝会白占一个名额，且拒绝路径没有配对的释放点。
         */
        requireExecutionAllowed();

        /*
         * 全局并发控制：获取不到许可直接拒绝（429），
         * 不阻塞等待，避免请求在队列里堆积。
         * 许可在最外层 finally 释放，覆盖成功/失败/取消/超时所有场景。
         */
        acquireConcurrencyPermit();

        try {

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
             * 审批暂停：任务被挂起，不是结束。
             *
             * 不调用 finishIfStillRunning（它只处理 RUNNING），
             * 不写 final_answer，也不写 finished_at——
             * 暂停行已经由 ApprovalPauseService 在同一事务里落库。
             */
            if (ctx.getState() == AgentState.WAITING_APPROVAL) {
                return "任务已暂停，等待人工确认。";
            }

            /*
             * 循环退出时如果仍是 RUNNING，
             * 根据具体停止原因判定终态（超时 / 成功 / 达到预算）。
             */
            finishIfStillRunning(ctx);

            /*
             * 预算耗尽且没有最终回答时，给子类一次"不带工具再问一次"的机会。
             * 必须在 finishIfStillRunning 之后调用：终态已经确定，
             * 收尾只补 finalAnswer，不改状态。
             */
            applyBudgetFallback(ctx);

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
             * 同步任务终态后释放并发许可（release-once），
             * 再放执行权交接锁：顺序不能反——
             * 先放锁会让批准请求在许可还没释放时就进来。
             */
            releaseConcurrencyPermit(ctx);
            releaseHandoffLock(ctx);
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
        return runStream(userPrompt, null);
    }

    /**
     * 带会话上下文执行一次任务。
     *
     * <p>与无参版本的区别只有两处：
     * 1. 先注入最近几轮的**纯文本**历史（用户/助手），再追加本轮用户输入；
     * 2. 本轮用户输入前面附一段"会话已知对象"（来自真实工具结果的 id/名称）。
     *
     * <p>不注入历史工具调用：回放旧 tool_call 会让模型把上一轮的写指令
     * 当成本轮指令重新执行一遍。
     *
     * @param seed 会话上下文；为 null 时行为与单参版本完全一致
     */
    public SseEmitter runStream(String userPrompt, ConversationSeed seed) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("用户任务不能为空");
        }

        /*
         * 启动收尾门禁：在建流、取许可、写 agent_run 之前判断。
         * 否则会先落一条 RUNNING 记录，再被拒绝——那正好是扫描器要收的东西。
         */
        requireExecutionAllowed();

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
         * SSE HTTP 连接最长存活时间 = Agent 超时 + 缓冲。
         *
         * 不能让两者相等：Agent 超时后还要发 TIMED_OUT 状态事件与 DONE，
         * 容器异步超时先到就会把这几条事件吞掉（详见 sseBuffer 的注释）。
         * Agent 内部仍按 timeout 判定 TIMED_OUT，这里只放宽连接存活时间。
         */
        long emitterTimeout = resolveEmitterTimeout();
        log.info(
                "SSE 连接超时设置：agentTimeout={}ms, buffer={}ms, emitterTimeout={}ms",
                timeout.toMillis(),
                sseBuffer.toMillis(),
                emitterTimeout
        );

        SseEmitter emitter = new SseEmitter(emitterTimeout);

        /*
         * 每次 Agent 请求拥有独立上下文。
         * 不要将 ctx 放到 BaseAgent 成员变量。
         *
         * runId 可以由入口预先给出（会话需要在启动前就落一条用户消息），
         * 没有给就照旧自己生成。
         */
        AgentRunContext ctx = (seed != null && seed.runId() != null)
                ? new AgentRunContext(seed.runId())
                : new AgentRunContext();

        /*
         * 固化用户身份与 Agent 类型：
         * runStream 同步段仍在 Web 线程，能读到用户上下文 ThreadLocal。
         */
        ctx.setUserId(UserContextHolder.getOrDefault());
        ctx.setAgentType(getAgentType());

        if (seed != null && seed.hasConversation()) {
            ctx.setConversationId(seed.conversationId());
        }

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

        /*
         * 会话历史：只放文本轮次，且必须在本轮用户输入之前。
         * 顺序错了模型会把历史当成"还没发生的事"。
         */
        if (seed != null && seed.history() != null && !seed.history().isEmpty()) {
            ctx.getMessages().addAll(seed.history());
        }

        ctx.getMessages().add(new UserMessage(withRefs(userPrompt, seed)));

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

        /*
         * 注册表句柄单独持有引用：
         * SSE 回调要按"对象身份"确认自己是否仍是当前执行，
         * 只拿 future 无法区分新老代次。
         */
        RunningAgentTask runningTask = new RunningAgentTask(
                ctx, task, ctx.getExecutionEpoch());

        if (taskRegistry != null) {
            taskRegistry.register(runId, runningTask);
        }

        submitExecution(ctx, emitter, runningTask, startMillis, cleaned, false);

        return emitter;

        } catch (RuntimeException e) {
            /*
             * 同步段在异步任务提交成功前失败（例如线程池拒绝）：
             * 此时异步 finally 不会执行，必须由本处释放许可。
             */
            releasePermitOnSubmitFailure();
            throw e;
        }
    }

    /**
     * 判断当前任务是否允许继续进入下一轮 Agent Loop。
     */
    private boolean canContinue(AgentRunContext ctx) {
        /*
         * 超时按"累计执行时间"算，不是"从 startedAt 到现在的墙钟时间"。
         *
         * 审批暂停可能持续几小时，若用墙钟时间，
         * 一恢复就会立刻被判超时——用户明明批准了，任务却什么都没做就失败。
         * 恢复时的 timeoutBudget 取检查点里的任务预算快照，
         * 因此"恢复后剩余额度"= 快照 - 已累计执行时间，不会被重置。
         */
        return ctx.getState() == AgentState.RUNNING
                && ctx.getCurrentStep() < maxSteps
                && ctx.getToolCallCount() < maxToolCalls
                && ctx.elapsed().compareTo(ctx.effectiveTimeout(timeout)) < 0;
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

        boolean timedOut = ctx.elapsed()
                .compareTo(ctx.effectiveTimeout(timeout)) >= 0;

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
     * 预算耗尽后的收尾钩子：由子类决定要不要"再问模型一次"。
     *
     * 触发条件（严格）：
     * 1. 终态是 {@link AgentState#MAX_STEPS_REACHED}——步数或工具次数耗尽；
     * 2. 到这时仍然没有最终回答（模型一直在调工具，从没写过结论）。
     *
     * 为什么要有它：
     * 预算耗尽时用户拿到的是"安全停止：达到最大步骤数……"这类过程摘要，
     * 而模型在上下文里其实已经积累了搜索/抓取结果——
     * 再给一次**不带工具**的调用，让它把已有信息总结成回答，
     * 用户至少能得到"尽力而为的答案"，而不是一句系统提示。
     *
     * 默认空实现：BaseAgent 不持有 ChatModel，
     * 由 ToolCallAgent 覆盖实现真正的收尾调用。
     *
     * 实现约束：
     * - **不得修改终态**（必须保持 MAX_STEPS_REACHED，这是成本事实）；
     * - 失败时保持 ctx.finalAnswer 为空，让调用方回退到步骤摘要。
     */
    protected void onBudgetExhausted(AgentRunContext ctx) {
    }

    /**
     * 终态判定之后调用：预算耗尽且无最终回答时，给子类一次强制收尾的机会。
     */
    private void applyBudgetFallback(AgentRunContext ctx) {
        if (ctx.getState() != AgentState.MAX_STEPS_REACHED) {
            return;
        }
        if (ctx.getFinalAnswer() != null && !ctx.getFinalAnswer().isBlank()) {
            return;
        }
        onBudgetExhausted(ctx);
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
     *
     * 子类（ToolCallAgent 的预算收尾调用）也要用同一套判定：
     * 取消/超时不能被当成普通异常吞掉，否则会被包装成"预算耗尽"的正常回答。
     */
    protected boolean causedByInterruption(Throwable e) {
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
     * 从注册表移除**自己这一次执行**的任务。
     *
     * 必须带代次：审批恢复会给同一个 runId 注册新句柄，
     * 旧线程按 runId 无条件删除会把新句柄删掉。
     */
    private void unregisterTask(AgentRunContext ctx) {
        if (taskRegistry != null) {
            taskRegistry.remove(ctx.getRunId().toString(), ctx.getExecutionEpoch());
        }
    }

    /**
     * 注册表里的句柄是否仍是自己这一次执行。
     *
     * 注册表未注入（纯单测）时视为"是"，保持既有行为。
     */
    private boolean isCurrentExecution(String runId, RunningAgentTask task) {
        return taskRegistry == null || taskRegistry.isCurrent(runId, task);
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
     * 释放全局并发许可（release-once）。
     *
     * 暂停路径与 finally 都可能走到这里；
     * 用 ctx 上的标记保证一次执行只释放一次，
     * 否则许可数会被放大，并发上限形同虚设。
     */
    private void releaseConcurrencyPermit(AgentRunContext ctx) {
        if (concurrencyGuard == null) {
            return;
        }
        if (ctx == null || ctx.markPermitReleased()) {
            concurrencyGuard.release();
        }
    }

    /**
     * 释放执行权交接锁（只有真正持有时才放）。
     *
     * 暂停时由 ToolCallAgent 获取并置标记；
     * 这里在"许可已释放、注册表已注销"之后释放，
     * 保证批准请求看到的永远是一个已经收尾干净的暂停态。
     */
    protected void releaseHandoffLock(AgentRunContext ctx) {
        if (handoffLocks == null || !ctx.isHandoffLockHeld()) {
            return;
        }
        ctx.setHandoffLockHeld(false);
        handoffLocks.release(ctx.getRunId().toString());
    }

    /**
     * 提交异步任务失败时的释放（此时任务从未启动，ctx 的 finally 不会执行）。
     *
     * 这条路径与 {@link #releaseConcurrencyPermit(AgentRunContext)} 互斥：
     * runAsync 抛异常意味着异步体一行都没跑。
     */
    private void releasePermitOnSubmitFailure() {
        if (concurrencyGuard != null) {
            concurrencyGuard.release();
        }
    }

    /**
     * 收尾本次恢复对应的审批（FINISHED）。
     *
     * 只在"这次执行真的结束了"时调用；再次暂停时跳过，
     * 由暂停事务把旧审批与新审批一起处理。
     */
    private void finishResumeApproval(AgentRunContext ctx) {
        if (approvalClaimService == null || ctx.getResumeApprovalId() == null) {
            return;
        }
        if (ctx.getState() == AgentState.WAITING_APPROVAL) {
            return;
        }
        try {
            approvalClaimService.finish(ctx.getResumeApprovalId());
        } catch (RuntimeException e) {
            // 收尾失败不影响任务主流程，但必须留痕（扫描器/人工可查）
            log.warn("审批收尾失败：approvalId={}, runId={}",
                    ctx.getResumeApprovalId(), ctx.getRunId(), e);
        }
    }

    /**
     * 恢复执行的启动门槛：CLAIMED → RUNNING。
     *
     * 由任务体第一行调用，条件带 approvalId 与本次代次：
     * 0 行说明这次领取已失效（被别人补偿或接管），本次执行必须一行都不跑。
     *
     * 为什么不在提交执行之前标记：
     * 提交成功只代表"进了线程池队列"，不代表"开始执行"。
     * 排队期间保持 CLAIMED，才能让"已提交但尚未执行"和"正在执行"可区分，
     * 也让超期未启动的任务能被明确识别，而不是伪装成 RUNNING。
     *
     * @return true 表示本次执行持有执行权
     */
    private boolean beginResumedExecution(AgentRunContext ctx) {
        if (approvalClaimService == null || ctx.getResumeApprovalId() == null) {
            return true;
        }

        String approvalId = ctx.getResumeApprovalId();

        try {
            if (approvalClaimService.markStarted(approvalId, ctx.getExecutionEpoch())) {
                log.info("审批执行已启动：approvalId={}, runId={}, epoch={}",
                        approvalId, ctx.getRunId(), ctx.getExecutionEpoch());
                return true;
            }

            /*
             * 0 行：领取已不属于本次代次。
             * 绝不能写终态——那一行现在归别人（或已回退为等待审批）。
             */
            ctx.setErrorCode("APPROVAL_CLAIM_LOST");
            log.warn("审批启动标记 0 行，本次执行放弃：approvalId={}, runId={}, epoch={}",
                    approvalId, ctx.getRunId(), ctx.getExecutionEpoch());
            return false;

        } catch (RuntimeException e) {
            /*
             * 标记写入异常：**不能**断言"库里一定还是 CLAIMED"。
             *
             * 异常只说明应用没拿到这次写入的确认：可能根本没提交，
             * 也可能已经提交成 RUNNING 而确认丢了。两种情况从异常本身分辨不出来。
             *
             * 因此这里只做两件事：
             * 1. 本次执行到此为止——不调用模型、不执行工具；
             * 2. 尝试补偿：补偿的 CAS 只吃 CLAIMED + 本次代次，
             *    若实际已是 RUNNING，影响 0 行并整体放弃，绝不回退已开始的执行。
             *
             * 状态不确定的记录不写终态、不伪造完成，留给后续恢复机制按实际状态收尾。
             */
            ctx.setErrorCode("APPROVAL_START_FAILED");
            log.error("审批启动标记失败，补偿为可重试：approvalId={}, runId={}",
                    approvalId, ctx.getRunId(), e);

            try {
                approvalClaimService.compensate(
                        approvalId, ctx.getRunId().toString(), ctx.getExecutionEpoch());
            } catch (RuntimeException compensationError) {
                log.error("审批启动失败后的补偿也失败：approvalId={}",
                        approvalId, compensationError);
            }
            return false;
        }
    }

    /**
     * 写终态：恢复执行时把 run 终态与审批收尾放进同一事务。
     *
     * 顺序不可交换：
     * - 先写 run 终态，再写 approval FINISHED；
     * - 两条写入同事务提交，因此不存在"审批已 FINISHED、run 还没终态"的中间态；
     * - 若 run 的行数不为 1（旧代次迟到写入），审批收尾一并跳过；
     * - 若审批收尾未命中且不是重复收尾，整个事务回滚，run 不留下终态。
     *
     * 返回值只表示"这次调用是否把终态安全处理完"：
     * true  = 已提交，或按规则本来就不该写（旧代次 / 仍在等待审批）；
     * false = 本次没有提交任何终态（写入失败，或这次代次已不拥有该行）。
     * 调用方据此决定能不能发"执行成功"的事件。
     *
     * 这里不承诺 emitter.complete() 会保证数据库提交：
     * 提交发生在 complete 之前的这次调用里，complete 只负责关闭连接。
     */
    private boolean persistTerminal(
            AgentRunContext ctx,
            String errorMessage,
            long startMillis,
            AtomicBoolean persisted
    ) {
        if (!persisted.compareAndSet(false, true)) {
            // 成功/失败路径已写过，finally 这次只是兜底：不算失败
            return true;
        }

        try {
            long costMillis = System.currentTimeMillis() - startMillis;

            if (approvalCompletionService != null && ctx.getResumeApprovalId() != null) {
                /*
                 * 收尾服务的 false 必须原样传出去（批次 4B，G7 修正）。
                 *
                 * false 的确切含义是"run 终态没有命中本次代次"——要么这条记录已经被
                 * 启动收尾判成中断，要么已经被新代次接管。两种情况都说明
                 * **本次执行不再是这条 run 的结论来源**；此时若仍然发 final_answer / done，
                 * 客户端会收到一个库里并不存在的成功结果。
                 *
                 * 注意 false 不是"再试一次就好"：它是条件更新的确定性结果，
                 * 所以这里不重置 persisted，finally 不会迟到补出一个假完成。
                 */
                boolean completed =
                        approvalCompletionService.complete(ctx, errorMessage, costMillis);

                if (!completed) {
                    log.warn("终态未命中本次代次，不发送成功事件：runId={}, epoch={}, state={}",
                            ctx.getRunId(), ctx.getExecutionEpoch(), ctx.getState());
                    return false;
                }

                /*
                 * 会话落库只在"终态确实归本次代次"之后做：
                 * 命中不了说明这次执行不再是结论来源，此时写助手回答
                 * 等于给会话塞一句库里并不承认的结论。
                 */
                finishConversationTurn(ctx);
                return true;
            }

            /*
             * 非恢复执行（或未装配原子收尾服务）：单条 UPDATE 自带提交。
             * 用严格版拿到行数，才能区分"写失败"与"本来就不该写"：
             * 0 行是旧代次/暂停中，此时**不能**再去收尾审批——
             * 那条审批已经归新代次或仍待执行。
             */
            int rows = runRecorder == null
                    ? 0
                    : runRecorder.recordFinishStrict(ctx, errorMessage, costMillis);

            if (rows == 1) {
                finishResumeApproval(ctx);
                finishConversationTurn(ctx);
            } else {
                log.warn("终态写入未命中（旧代次或暂停中）：runId={}, epoch={}, state={}, rows={}",
                        ctx.getRunId(), ctx.getExecutionEpoch(), ctx.getState(), rows);
            }
            return true;
        } catch (RuntimeException e) {
            // 允许 finally 再兜一次；失败时保持"run 仍 RUNNING"，由扫描器按启动期限收尾
            persisted.set(false);
            log.error("终态持久化失败：runId={}, state={}",
                    ctx.getRunId(), ctx.getState(), e);
            return false;
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

    /**
     * 对外暴露的 Agent 类型（批次 2：按审批记录里的 agentType 找回 Agent 实例）。
     *
     * 恢复入口只拿到一行审批记录，必须按类型路由到正确的 Agent；
     * 直接暴露 getter 而不是让调用方反射或猜 Bean 名，
     * 保证"记录里的类型"与"实际执行者"来自同一处定义。
     */
    public AgentType agentType() {
        return getAgentType();
    }

    protected void recordRunStart(AgentRunContext ctx, String userPrompt) {
        if (runRecorder != null) {
            runRecorder.recordStart(ctx, userPrompt);
        }
    }

    /**
     * 把"会话已知对象"拼到本轮用户输入前面。
     *
     * <p>为什么拼进用户消息而不是另加一条 SystemMessage：
     * 部分 OpenAI 兼容实现只接受开头的 system 消息，中途插入 system
     * 会被拒绝或忽略；拼在用户消息里则任何实现都能看到。
     *
     * <p>库里存的仍是用户原文（recordRunStart 与用户消息表都用原文），
     * 这段前缀只出现在送给模型的内容里。
     */
    private String withRefs(String userPrompt, ConversationSeed seed) {
        if (seed == null || seed.refsBlock() == null || seed.refsBlock().isBlank()) {
            return userPrompt;
        }
        return seed.refsBlock() + "\n\n[用户本轮输入]\n" + userPrompt;
    }

    /**
     * 把本轮写进会话（助手回答 + 业务引用）。
     *
     * <p>内部吞异常：run 的终态已经落库，会话写失败只是少了聊天记录，
     * 不能让一次成功任务因为"记不上聊天"而返回失败。
     */
    private void finishConversationTurn(AgentRunContext ctx) {
        if (conversationService == null) {
            return;
        }
        try {
            conversationService.finishTurn(ctx);
        } catch (RuntimeException e) {
            log.warn("会话轮次落库失败（不影响任务结论）：runId={}", ctx.getRunId(), e);
        }
    }

    private void recordRunProgress(AgentRunContext ctx) {
        if (runRecorder != null) {
            runRecorder.recordProgress(ctx);
        }
    }

    /**
     * 同步入口（无 SSE）的终态写入：尽力而为，失败只记日志。
     *
     * 与流式入口的区别：这里没有"客户端看到成功事件"的问题，
     * 因此沿用记录器的吞异常版本，不让写库失败影响同步调用的返回。
     * 流式入口必须走 {@link #persistTerminal}（严格版 + 原子收尾）。
     */
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
     * 在异常链中查找模型调用失败异常。
     *
     * step 层会把异常包装成 RuntimeException("Agent 第 X 步执行失败", e)，
     * 因此必须沿 cause 链查找。
     */
    private ModelCallException findModelCallException(Throwable e) {
        Throwable current = e;

        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof ModelCallException modelCallException) {
                return modelCallException;
            }

            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }

        return null;
    }

    /**
     * 构造脱敏错误信息：只保留异常类型与简短消息并截断，
     * 不包含堆栈，避免敏感信息落库。
     *
     * step 层会把真实异常包装成 RuntimeException（“Agent 第 X 步执行失败”），
     * 因此这里递归解包到根因，保证库中能看到真实失败类型。
     *
     * 模型调用失败额外加上统一前缀，方便按前缀统计"配置/额度类故障"。
     */
    private String buildErrorMessage(Exception e) {
        ModelCallException modelCallException = findModelCallException(e);

        if (modelCallException != null) {
            Throwable root = modelCallException;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }

            String detail = root.getClass().getSimpleName()
                    + (root.getMessage() == null ? "" : "：" + root.getMessage());
            String modelError = "模型服务调用失败（" + detail + "）";
            return modelError.length() <= 500 ? modelError : modelError.substring(0, 500);
        }

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

    public Duration getSseBuffer() {
        return sseBuffer;
    }

    /**
     * 设置 SSE 缓冲（通用能力，MkManus 通过 @Value 方法注入）。
     *
     * 越界（含 null、负数、小于 1 秒、大于 5 分钟）一律 WARN 并回退默认值：
     * 这个值的意义是"保证超时事件能发出去"，
     * 配置笔误（例如把 30s 写成 0s 或 30000）不该让线上静默失去保护。
     */
    public void setSseBuffer(Duration sseBuffer) {
        if (sseBuffer == null
                || sseBuffer.compareTo(MIN_SSE_BUFFER) < 0
                || sseBuffer.compareTo(MAX_SSE_BUFFER) > 0) {
            log.warn(
                    "sse-buffer={} 非法（要求 {}~{}），回退默认值 {}",
                    sseBuffer, MIN_SSE_BUFFER, MAX_SSE_BUFFER, DEFAULT_SSE_BUFFER
            );
            this.sseBuffer = DEFAULT_SSE_BUFFER;
            return;
        }
        this.sseBuffer = sseBuffer;
    }

    /**
     * 计算 SseEmitter 的超时时间（毫秒）= Agent 超时 + 缓冲。
     *
     * 抽成独立方法是为了能**离线**断言这个数：
     * SseEmitter 不提供 timeout 的读取接口，
     * 不抽出来就只能靠"跑一遍真实超时"来间接验证，慢且不稳定。
     *
     * 溢出保护：timeout 本身没有上限（setTimeout 不做校验），
     * 极端值相加会溢出成负数，而负数在 SseEmitter 里表示"永不过期"——
     * 那会把一次配置笔误变成连接永不释放。
     */
    protected long resolveEmitterTimeout() {
        long agentMillis = timeout == null ? 0L : timeout.toMillis();
        long bufferMillis = sseBuffer == null ? 0L : sseBuffer.toMillis();

        long total = agentMillis + bufferMillis;
        if (total < 0) {
            log.warn(
                    "SSE 超时计算溢出（agentTimeout={}ms, buffer={}ms），退化为不过期上限",
                    agentMillis, bufferMillis
            );
            return Long.MAX_VALUE;
        }
        return total;
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

    public RunHandoffLocks getHandoffLocks() {
        return handoffLocks;
    }

    /**
     * 注入执行权交接锁（M3）。为 null 时暂停仍可用，只是不做交接保护。
     */
    public void setHandoffLocks(RunHandoffLocks handoffLocks) {
        this.handoffLocks = handoffLocks;
    }

    public void setApprovalClaimService(ApprovalClaimService approvalClaimService) {
        this.approvalClaimService = approvalClaimService;
    }

    public void setApprovalCompletionService(
            ApprovalExecutionCompletionService approvalCompletionService) {
        this.approvalCompletionService = approvalCompletionService;
    }

    /**
     * 注入启动收尾门禁（批次 4B）。为 null 时不做入口限制。
     *
     * 用 setter 而不是构造参数：{@link MkManus} 等子类的构造签名已固定，
     * 而且纯单测会直接 new 出 Agent，不该被迫传一个门禁。
     * Spring 环境下由容器自动调用（{@code @Autowired(required = false)}）。
     */
    @Autowired(required = false)
    public void setRecoveryGate(RecoveryGate recoveryGate) {
        this.recoveryGate = recoveryGate;
    }

    /**
     * 启动收尾期间的入口门禁：关闭时抛 503，任务一行都不会创建。
     */
    private void requireExecutionAllowed() {
        if (recoveryGate != null) {
            recoveryGate.requireOpen();
        }
    }

    /**
     * 恢复入口（M3 批次 2）。
     *
     * 前置条件由调用方（ApprovalResumeService）保证：
     * 已领取（approval CLAIMED、run RUNNING、epoch 已 +1）、已拿到并发许可。
     *
     * 本方法只做两件事：登记句柄、提交执行。
     *
     * **启动标记不在这里**：提交成功只代表"排队了"，不代表"开始执行"。
     * CLAIMED → RUNNING 由任务体第一条语句完成（见 beginResumedExecution），
     * 因此排队期间库里始终是 CLAIMED——这正是"已提交但没跑"的正确表达。
     *
     * 这里抛出异常只有一种含义：异步体一行都没跑（登记失败/线程池拒绝），
     * 调用方据此做数据库补偿是安全的。
     */
    public SseEmitter resume(AgentRunContext ctx, SseEmitter emitter) {
        String runId = ctx.getRunId().toString();

        RunningAgentTask runningTask = new RunningAgentTask(
                ctx, new CompletableFuture<>(), ctx.getExecutionEpoch());

        if (taskRegistry != null && !taskRegistry.register(runId, runningTask)) {
            releaseConcurrencyPermit(ctx);
            throw new IllegalStateException(
                    "审批恢复登记失败：同代次已有执行者，runId=" + runId
                            + ", epoch=" + ctx.getExecutionEpoch());
        }

        ctx.setEmitter(emitter);

        AtomicBoolean cleaned = new AtomicBoolean(false);

        try {
            submitExecution(ctx, emitter, runningTask,
                    System.currentTimeMillis(), cleaned, true);
        } catch (RuntimeException e) {
            /*
             * 提交失败（线程池拒绝等）：异步体一行都没跑，
             * 这里负责注销句柄与释放许可，再抛给调用方做数据库补偿。
             */
            if (taskRegistry != null) {
                taskRegistry.remove(runId, ctx.getExecutionEpoch());
            }
            releaseConcurrencyPermit(ctx);
            throw e;
        }

        return emitter;
    }

    /**
     * 恢复钩子：进入循环之前补做"暂停那一轮"。
     *
     * 由 ToolCallAgent 实现（执行批准的工具，或回填拒绝回执）。
     * 默认空实现，保证其它 Agent 即使被恢复也不会出错。
     */
    protected void afterResume(AgentRunContext ctx) {
    }

    /**
     * 恢复流的 SSE 超时：剩余执行时间 + buffer（不是原始总超时）。
     */
    public SseEmitter createResumeEmitter(AgentRunContext ctx) {
        long remaining = ctx.effectiveTimeout(timeout).minus(ctx.elapsed()).toMillis();
        long buffer = sseBuffer == null ? 0L : sseBuffer.toMillis();

        long total = Math.max(1000L, remaining) + buffer;
        if (total < 0) {
            total = Long.MAX_VALUE;
        }
        return new SseEmitter(total);
    }

    /**
     * 注入 AgentRun 生命周期记录器（由具体 Agent 的构造器转交）。
     */
    public void setRunRecorder(AgentRunRecorder runRecorder) {
        this.runRecorder = runRecorder;
    }

    /**
     * 注入会话服务（由具体 Agent 的构造器转交）；为 null 表示不记录会话。
     */
    public void setConversationService(ConversationService conversationService) {
        this.conversationService = conversationService;
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

    /**
     * 提交并运行 Agent Loop（普通入口与审批恢复入口共用同一份实现）。
     *
     * 共用而不是各写一份：暂停出口、终态判定、预算兜底、finally 清理
     * 一旦分叉，恢复路径就会悄悄丢掉其中一条（例如恢复后暂停不关流）。
     *
     * @param resumed true 表示这是审批恢复，进入循环前先补做暂停轮
     */
    private void submitExecution(
            AgentRunContext ctx,
            SseEmitter emitter,
            RunningAgentTask runningTask,
            long startMillis,
            AtomicBoolean cleaned,
            boolean resumed
    ) {
        String runId = ctx.getRunId().toString();
        CompletableFuture<Void> task = runningTask.future();

        CompletableFuture.runAsync(() -> {
            /*
             * 记录执行线程：取消/超时需要显式中断该线程。
             */
            ctx.setExecutingThread(Thread.currentThread());

            /*
             * 失败分支的脱敏错误信息，finally 中随终态写入数据库。
             */
            String errorMessage = null;

            /*
             * executionEntered：本次执行是否真的拿到了执行权。
             * 普通入口天然为 true；恢复入口要等启动标记成功后才置 true。
             * 为 false 时 finally 不得写终态——那一行已经不属于本次代次。
             */
            AtomicBoolean executionEntered = new AtomicBoolean(!resumed);

            /*
             * terminalPersisted：终态只写一次。
             * 成功/失败路径会在关闭连接之前写（让客户端拿到流结束时库里已是终态），
             * finally 再兜一次；用标记保证不会重复写。
             */
            AtomicBoolean terminalPersisted = new AtomicBoolean(false);

            try {
                /*
                 * 启动门槛（批次 2 修正）：执行器真正开始运行之后，
                 * 才把 CLAIMED 改成 RUNNING，条件带 approvalId + 本次代次。
                 *
                 * 放在这里而不是提交之前：提交成功但排队时仍是 CLAIMED。
                 * 标记之前没有任何模型/工具调用，所以标记失败时
                 * 本次执行可以安全地不写任何终态、不做业务变更。
                 */
                if (resumed && !beginResumedExecution(ctx)) {
                    sendEvent(
                            emitter,
                            AgentEvent.of(
                                    runId,
                                    AgentEventType.ERROR,
                                    "本次恢复的领取已失效，未执行任何操作。",
                                    ctx.getCurrentStep()
                            )
                    );
                    emitter.complete();
                    return;
                }
                executionEntered.set(true);

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

                /*
                 * 恢复入口：先补做"暂停那一轮"（执行或回填拒绝），
                 * 再进入常规循环。必须放在循环之前——
                 * currentStep==maxSteps 时该轮仍要完成，不能被循环条件挡掉。
                 */
                if (resumed) {
                    afterResume(ctx);
                }

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
                 * 审批暂停：本段流到此结束，但任务**没有**终态。
                 *
                 * 事件顺序：status → approval_required → done。
                 * 这三个事件都在**暂停事务提交之后**才发：
                 * 暂停写入发生在 ToolCallAgent.pauseForApproval 的
                 * approvalPauseGateway.persist 里，那是跨 Bean 的 @Transactional
                 * 调用，方法返回即已提交；本分支的代码在那之后才执行。
                 * 提交失败会在那边抛异常，根本走不到这里——
                 * 不会出现"前端看到待确认、库里却没有审批"的假暂停。
                 * 这里的 done 只表示"本段连接结束"，不表示任务完成——
                 * 终态一律以 agent_run.state 为准（客户端应查询或等待批准后续流）。
                 *
                 * 不发 final_answer（还没有答案），不发 error（不是错误）。
                 */
                if (ctx.getState() == AgentState.WAITING_APPROVAL) {
                    sendEvent(
                            ctx,
                            AgentEventType.STATUS,
                            "等待人工确认，本段连接结束。"
                    );
                    sendEvent(
                            emitter,
                            AgentEvent.of(
                                    runId,
                                    AgentEventType.APPROVAL_REQUIRED,
                                    ctx.getPendingApprovalJson(),
                                    ctx.getCurrentStep()
                            )
                    );
                    sendEvent(
                            emitter,
                            AgentEvent.of(
                                    runId,
                                    AgentEventType.DONE,
                                    "[DONE]",
                                    ctx.getCurrentStep()
                            )
                    );
                    emitter.complete();
                    return;
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
                 * 预算耗尽且无最终回答：给子类一次不带工具的收尾调用。
                 * 放在状态事件之后、final_answer 事件之前，
                 * 保证前端先看到"为什么停止"，再看到"尽力而为的答案"。
                 */
                applyBudgetFallback(ctx);

                /*
                 * 终态先落库，再发"成功"事件。顺序不能反。
                 *
                 * 客户端看到 final_answer / done 就会认为这次执行已经完成；
                 * 如果先发事件、后写库，事务失败时用户手里就是一个
                 * 库里并不存在的成功结果。
                 *
                 * 所以：提交事务（恢复入口还会把审批 FINISHED 并进同一事务）
                 * → 发 final_answer → 发 done → 关闭连接。
                 * 提交失败时只发脱敏 error 并结束，绝不发成功事件。
                 *
                 * 注意：这只是"提交发生在关闭连接之前"，
                 * 不是 emitter.complete() 保证提交——它只负责关闭连接。
                 */
                if (!persistTerminal(ctx, null, startMillis, terminalPersisted)) {
                    sendEvent(
                            emitter,
                            AgentEvent.of(
                                    runId,
                                    AgentEventType.ERROR,
                                    "执行结果未能保存，请稍后查询任务状态。",
                                    ctx.getCurrentStep()
                            )
                    );
                    emitter.complete();
                    return;
                }

                /*
                 * 推送 final_answer 事件。
                 *
                 * 除了 SUCCEEDED，MAX_STEPS_REACHED 也要推：
                 * 强制收尾会在预算耗尽时补一个 finalAnswer，
                 * 不推的话用户只能看到"安全停止"，答案永远到不了前端。
                 * 失败/取消/超时不推（这些情况下没有可信答案）。
                 */
                if ((ctx.getState() == AgentState.SUCCEEDED
                        || ctx.getState() == AgentState.MAX_STEPS_REACHED)
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
                 *
                 * 模型调用失败时，ToolCallAgent.think() 已经在失败现场
                 * 推送过一条更精确的 ERROR 事件（配置/额度问题），
                 * 这里跳过，避免前端收到两条 error。
                 */
                if (findModelCallException(e) == null) {
                    sendEvent(
                            emitter,
                            AgentEvent.of(
                                    runId,
                                    AgentEventType.ERROR,
                                    "智能体执行失败，请稍后重试。",
                                    ctx.getCurrentStep()
                            )
                    );
                }

                /*
                 * 同成功路径：终态与审批收尾同一事务，且先于关闭连接。
                 * 这里已经发过脱敏 error，不存在"成功事件早于提交"的问题；
                 * 写入失败由 finally 再兜一次，仍失败则保持 RUNNING 等扫描器收尾。
                 */
                persistTerminal(ctx, errorMessage, startMillis, terminalPersisted);

                emitter.complete();

            } finally {
                /*
                 * 终态单点写库：成功/失败/取消/超时/达到预算都在这里落盘。
                 * 取消接口与 onTimeout 不单独写库，避免并发写冲突。
                 */
                if (executionEntered.get()) {
                    persistTerminal(ctx, errorMessage, startMillis, terminalPersisted);
                }

                logTaskSummary(ctx, startMillis);
                cleanupOnce(ctx, cleaned);
                unregisterTask(ctx);

                /*
                 * 任务结束（含暂停）后释放并发许可。
                 *
                 * release-once：暂停路径与 finally 都可能走到这里，
                 * 由 ctx 的标记保证一次执行只释放一次；
                 * 每个执行代次有独立 ctx，旧代次也无法释放新代次的许可。
                 */
                releaseConcurrencyPermit(ctx);
                releaseHandoffLock(ctx);

                /*
                 * 这里不再单独收尾审批：
                 * 终态与 FINISHED 已在 persistTerminal 里同事务提交。
                 *
                 * 单独再写一次会破坏原子性——若上面的事务提交失败，
                 * 这一步会留下"审批 FINISHED、run 仍 RUNNING"的假完成。
                 * 再次暂停时更不该写：旧审批由暂停事务收尾。
                 */
            }

        }, agentExecutor).whenComplete((result, error) -> {
            /*
             * 异步任务真正执行完后，把结果回写到预先注册的句柄。
             * 若句柄已被取消接口 cancel，这里的 complete 会静默失败，无副作用。
             */
            if (error != null) {
                runningTask.future().completeExceptionally(error);
            } else {
                runningTask.future().complete(null);
            }
        });

        emitter.onTimeout(() -> {
            /*
             * 只处理"自己这一次执行"的回调。
             *
             * 审批恢复会给同一个 runId 注册新句柄；
             * 旧连接的 onTimeout 如果迟到一步，绝不能去中断新线程、
             * 删新任务或释放新许可。按句柄对象身份比较，
             * 比比较 ctx.epoch（旧对象自比较，永远相等）可靠。
             */
            if (!isCurrentExecution(runId, runningTask)) {
                log.info("忽略过期的 SSE 超时回调：runId={}, epoch={}",
                        runId, ctx.getExecutionEpoch());
                return;
            }

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
            runningTask.future().cancel(true);
            ctx.interruptExecutingThread();

            cleanupOnce(ctx, cleaned);
            unregisterTask(ctx);
        });

        emitter.onCompletion(() -> {
            /*
             * 同样只处理自己这一次执行。
             */
            if (!isCurrentExecution(runId, runningTask)) {
                log.info("忽略过期的 SSE 完成回调：runId={}, epoch={}",
                        runId, ctx.getExecutionEpoch());
                return;
            }

            /*
             * 审批恢复的续流：客户端断开**不取消**任务，后台继续执行。
             *
             * 与普通入口的区别来自语义：普通 runStream 的断连等于"用户不要这个结果了"，
             * 恢复流的断连只说明"这条 SSE 连接没了"，用户已经付过一次人工确认，
             * 此时取消等于把确认结果丢掉，且没有任何途径重来。
             *
             * 不在这里注销句柄、不释放许可：任务还在跑，取消接口与
             * 并发计数都必须继续看得见它；收尾统一由任务线程的 finally 完成。
             */
            if (resumed) {
                log.info("审批恢复流已断开，任务转入后台继续：runId={}, state={}, step={}",
                        runId, ctx.getState(), ctx.getCurrentStep());
                return;
            }

            /*
             * 完成时仍是 RUNNING，说明任务没有正常走到终态，
             * 通常是客户端提前断开，视为取消。
             *
             * 正常完成时，任务线程已在 emitter.complete() 之前
             * 设置 SUCCEEDED（或其他终态），不会进入本分支，
             * 这是避免把正常完成误判为 CANCELLED 的关键。
             *
             * WAITING_APPROVAL 也不进入本分支：暂停时连接是"正常关闭"，
             * 不是客户端掉线，把挂起任务判成 CANCELLED 会让批准无从谈起。
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

                runningTask.future().cancel(true);
                ctx.interruptExecutingThread();
            } else {
                log.info(
                        "SSE 连接完成：runId={}, state={}",
                        runId,
                        ctx.getState()
                );
            }

            cleanupOnce(ctx, cleaned);
            unregisterTask(ctx);
        });


    }
}
