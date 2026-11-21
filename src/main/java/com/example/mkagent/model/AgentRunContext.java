package com.example.mkagent.model;

import com.example.mkagent.approval.ApprovalCheckpoint;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.conversation.ConversationRefs;
import com.example.mkagent.resilience.ToolTargetExhaustionTracker;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 Agent 任务运行期间的上下文。
 *
 * 生命周期：
 * BaseAgent.run() 开始时创建；
 * 当前 Agent 任务结束后失效。
 *
 * 注意：
 * 该类不是 Spring Bean。
 * 每次 run 都应该 new 一个新的 AgentRunContext，
 * 避免多个用户或多个任务共享消息和状态。
 */
public class AgentRunContext {

    /**
     * 新建一次任务。
     */
    public AgentRunContext() {
        this(UUID.randomUUID(), Instant.now());
    }

    /**
     * 按指定 runId 新建任务（测试用）。
     */
    public AgentRunContext(UUID runId) {
        this(runId, Instant.now());
    }

    private AgentRunContext(UUID runId, Instant startedAt) {
        this.runId = runId;
        this.startedAt = startedAt;
        this.segmentStartedAt = startedAt;
    }

    /**
     * 从检查点恢复一次任务（M3，批次 2 使用）。
     *
     * 与新建的区别：
     * 1. runId / 原始开始时间 / 累计执行时间 / 预算快照都来自检查点；
     * 2. 状态从 IDLE 开始，由调用方 transitionTo(RUNNING)；
     * 3. 新执行段从"现在"开始计时，审批等待不计入。
     *
     * @param runId           原任务的 runId（绝不能重新生成）
     * @param originalStartedAt 首次开始时间（用于展示，不参与超时计算）
     * @param activeElapsed   已累计的执行时间（不含审批等待）
     * @param timeoutBudget   任务预算快照中的超时；null 表示用 Agent 当前配置
     * @param executionEpoch  本次执行的代次（每次恢复 +1）
     */
    public static AgentRunContext restore(
            UUID runId,
            Instant originalStartedAt,
            Duration activeElapsed,
            Duration timeoutBudget,
            int executionEpoch
    ) {
        AgentRunContext ctx = new AgentRunContext(runId, originalStartedAt);
        ctx.activeElapsed = activeElapsed == null ? Duration.ZERO : activeElapsed;
        ctx.timeoutBudget = timeoutBudget;
        ctx.executionEpoch = executionEpoch;
        ctx.segmentStartedAt = Instant.now();
        return ctx;
    }

    /**
     * 用检查点恢复进度类字段（批次 2）。
     *
     * 只由 {@code CheckpointRestorer} 调用：
     * 恢复不是"重新开始"，步数、工具次数、消息历史与用量都必须原样接上，
     * 否则会出现"恢复后步数归零、预算翻倍"或"历史丢失、toolCallId 断裂"。
     *
     * @param currentStep      已计步的轮数（暂停那一轮已计入，恢复不再 +1）
     * @param toolCallCount    已执行的工具次数
     * @param history          本轮之前的历史（不含 pendingAssistant）
     */
    public void restoreProgress(
            int currentStep,
            int toolCallCount,
            List<Message> history,
            String model,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            int modelCallCount,
            long totalModelCallMillis
    ) {
        this.currentStep = currentStep;
        this.toolCallCount = toolCallCount;
        this.messages.clear();
        if (history != null) {
            this.messages.addAll(history);
        }
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.modelCallCount = modelCallCount;
        this.totalModelCallMillis = totalModelCallMillis;
    }

    /**
     * 当前任务的唯一标识。
     *
     * 用于日志追踪、数据库持久化、SSE 推送事件等。
     * 审批恢复时由检查点传入，**不能**重新生成——
     * 重新生成会让幂等键、审批记录、运行记录三者的关联断裂。
     */
    private final UUID runId;

    /**
     * 当前任务的消息历史。
     *
     * 可能包含：
     * 1. SystemMessage：系统规则
     * 2. UserMessage：用户任务
     * 3. AssistantMessage：模型文本或工具调用请求
     * 4. ToolResponseMessage：工具执行结果
     */
    private final List<Message> messages = new ArrayList<>();

    /**
     * 当前任务状态。
     */
    private AgentState state = AgentState.IDLE;

    /**
     * 当前 Agent Loop 已执行的轮数。
     */
    private int currentStep = 0;

    /**
     * 当前任务累计执行的工具调用次数。
     */
    private int toolCallCount = 0;

    /**
     * 实际执行过的工具名序列（按执行顺序）。
     *
     * 该列表只记录 toolName，不记录工具参数原文，
     * 避免搜索词、文件路径等敏感信息进入评测报告或日志。
     * 主要供离线评测（AgentEvaluator）判断工具选择是否正确。
     */
    private final List<String> toolTrace = new ArrayList<>();

    // ===== 模型 Usage 累计（跨多轮 think 累加） =====

    /**
     * 本次任务实际使用的模型名称（取最后一轮响应的 metadata）。
     */
    private String model;

    /**
     * 累计输入 Token 数。
     */
    private long promptTokens;

    /**
     * 累计输出 Token 数。
     */
    private long completionTokens;

    /**
     * 累计总 Token 数。
     */
    private long totalTokens;

    /**
     * 模型调用轮数（每次 think 一次计一次）。
     */
    private int modelCallCount;

    /**
     * 模型调用累计耗时（毫秒），用于成本与性能分析。
     */
    private long totalModelCallMillis;

    /**
     * 任务归属用户。
     *
     * BaseAgent 在创建任务时（仍在 Web 线程）从用户上下文固化进来，
     * 避免 agent-* 后台线程读不到 Web 线程的 ThreadLocal。
     * 非 HTTP 入口（单测直接 new）时为 anonymous 兑底。
     */
    private String userId = UserContextHolder.ANONYMOUS_USER_ID;

    /**
     * 所属会话（同一窗口连续聊天）。
     *
     * 为空表示"独立任务"：老的 /ai/manus 与 /ai/music/chat 不带会话时保持这个状态，
     * 历史记录不需要迁移。**注意它不是 runId**：
     * conversationId 跨多轮不变，runId 每轮都新建。
     */
    private String conversationId;

    /**
     * 本轮从真实工具结果里抽出的业务引用（最近搜索到的歌、歌单）。
     *
     * 由 BusinessRefCollector 在工具执行后累积，终态时写回会话；
     * 下一轮构造上下文时再读出来，供"第一首/刚才那个歌单"这类指代使用。
     */
    private ConversationRefs conversationRefs = ConversationRefs.EMPTY;

    /**
     * 执行本任务的 Agent 类型（持久化到 agent_run.agent_type），
     * 由具体 Agent 通过 BaseAgent.getAgentType() 提供。
     */
    private AgentType agentType;

    /**
     * 当前任务内"同一工具 + 同一目标主机"的重试耗尽计数。
     *
     * 放在 ctx 而不是工具回调里：工具回调是 Spring 单例，
     * 实例字段会被并发任务共享。
     * ToolCallAgent.act() 在工具执行前通过
     * ToolTargetExhaustionTracker.bind(...) 绑定到当前线程的 ThreadLocal，
     * 工具执行完在 finally 中 unbind() 清除；
     * 计数器本身任务结束时随 ctx 一起丢弃，不需要显式清理。
     */
    private final ToolTargetExhaustionTracker toolTargetExhaustionTracker =
            new ToolTargetExhaustionTracker();

    /**
     * 当前任务首次开始时间（展示用，不参与超时计算）。
     */
    private final Instant startedAt;

    /**
     * 累计**执行**时间（不含审批等待）。
     *
     * 暂停时把当前执行段并入这里；恢复后重新开一段。
     * 这样"等待人工确认 3 天"不会把一个 2 分钟预算的任务判成超时，
     * 但恢复后也拿不回被等待消耗掉的额度——用的是任务预算快照。
     */
    private Duration activeElapsed = Duration.ZERO;

    /**
     * 当前执行段的起点；暂停/结束时为 null。
     */
    private Instant segmentStartedAt;

    /**
     * 任务预算快照中的超时时间（仅恢复时有值）。
     *
     * 为 null 时用 Agent 当前配置的 timeout。
     * 恢复必须用快照，不能换成"当前 Bean 配置"——
     * 否则运维改一次配置，已暂停任务的剩余额度就被悄悄改了。
     */
    private Duration timeoutBudget;

    /**
     * 执行代次：首次执行 0，每次恢复 +1。
     *
     * 所有执行期写库都带这个条件，旧代次的迟到写入影响 0 行。
     */
    private int executionEpoch;

    /**
     * 本次执行的并发许可是否已释放（release-once 保护）。
     *
     * 暂停路径与 finally 都可能走到释放逻辑，
     * 用本标记保证一次执行只释放一次，避免许可数被放大。
     * 每个执行代次都有独立的 ctx，因此旧代次的标记不会影响新代次。
     */
    private final AtomicBoolean permitReleased = new AtomicBoolean(false);

    /**
     * 当前暂停对应的审批 ID（仅 WAITING_APPROVAL 时有值）。
     */
    private String approvalId;

    /**
     * 暂停时要下发的 approval_required 事件 data（已脱敏的 JSON 字符串）。
     *
     * 由暂停服务生成，BaseAgent 只负责发送，不再二次拼装，
     * 避免两处各拼一份、字段慢慢走偏。
     */
    private String pendingApprovalJson;

    /**
     * 稳定原因码（落库到 agent_run.error_code）。
     */
    private String errorCode;

    /**
     * 本次执行的 Agent 配置指纹（提示词/工具/参数 Schema/审批标记）。
     *
     * 暂停时写入检查点与 agent_run；恢复时必须与当前配置精确相等。
     */
    private String configVersion;

    /**
     * 本次执行是否持有"执行权交接锁"（M3）。
     *
     * 暂停时由 ToolCallAgent 获取，必须一直持有到
     * "并发许可释放 + 注册表注销"之后，由 BaseAgent 的 finally 释放。
     * 提前释放会让批准请求抢在收尾之前，造成两段执行重叠。
     */
    private boolean handoffLockHeld;

    /**
     * 本次恢复所依据的检查点（批次 2）。
     *
     * 只存在于内存：暂停轮执行完就没用了，不参与任何序列化。
     * 未恢复的普通任务为 null。
     */
    private ApprovalCheckpoint checkpoint;

    /**
     * 本次恢复来自哪条审批（批次 2）。
     *
     * 与 {@link #approvalId}（本次执行**新建**的审批）分开：
     * 恢复后可能再次暂停，那时会生成新的 approvalId，
     * 而这条旧审批必须在暂停事务里标成 FINISHED。
     */
    private String resumeApprovalId;

    /**
     * 恢复模式：APPROVED / REJECTED（批次 2）。
     *
     * 决定"恢复后先执行暂停轮，还是先回填拒绝回执"。
     */
    private String resumeMode;

    /**
     * 当前一轮 think() 调用模型后得到的响应。
     *
     * 若模型请求工具：
     * think(ctx) 保存 response；
     * act(ctx) 读取该 response 并执行其中的 ToolCall。
     */
    private ChatResponse pendingResponse;

    /**
     * 当前任务最终要返回给用户的自然语言回答。
     *
     * 当模型不再请求工具时，
     * ToolCallAgent.think(ctx) 会将模型文本保存到这里。
     */
    private String finalAnswer;

    /**
     * 本次任务的 SSE 连接（仅 runStream 流式执行时存在；同步 run() 为 null）。
     *
     * 放在 ctx 中而不是 BaseAgent 成员变量，
     * 避免并发请求时多个任务共用同一个 emitter。
     */
    private SseEmitter emitter;

    /**
     * 当前正在执行本任务的后台线程（仅 runStream 异步执行时存在）。
     *
     * 为什么需要它：
     * CompletableFuture.cancel(true) 不会中断正在执行的线程（官方文档：
     * mayInterruptIfRunning 参数对 CompletableFuture 无效），
     * 因此取消/超时需要通过这里拿到真实线程并显式 interrupt，
     * 才能让 Agent Loop 中的 Thread.currentThread().isInterrupted() 检测生效。
     */
    private volatile Thread executingThread;

    /**
     * 记录当前执行线程（任务开始时由后台线程自己调用）。
     */
    public void setExecutingThread(Thread executingThread) {
        this.executingThread = executingThread;
    }

    /**
     * 中断正在执行本任务的线程。
     *
     * 若任务尚未开始或已退出，则为安全的空操作。
     * 注意：中断只能在线程处于可中断点（sleep / wait /
     * 响应中断的 IO）时立即生效；对不响应中断的阻塞调用，
     * 要等到下一个检测点（Agent Loop 循环头）才会退出。
     */
    public void interruptExecutingThread() {
        Thread thread = executingThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 线程安全的状态转换（状态机唯一推荐入口）。
     *
     * 规则：
     * 1. 当前已是终态（SUCCEEDED / FAILED / CANCELLED / TIMED_OUT /
     *    MAX_STEPS_REACHED）时，拒绝任何变更并返回 false，
     *    防止已结束的任务被重新标记为 RUNNING，
     *    也防止 SUCCEEDED 被 onCompletion 误判成 CANCELLED；
     * 2. 目标状态与当前状态相同时视为无效变更，返回 false；
     * 3. 其余情况（IDLE→RUNNING、RUNNING→任意终态）允许转换，返回 true。
     *
     * 多线程竞态下的语义：
     * 取消接口、任务线程、SSE 回调可能同时尝试改状态，
     * synchronized 保证只有一个线程能成功写入终态，
     * 先到达的终态被保留（通常是用户/超时意图优先）。
     *
     * @param next 目标状态
     * @return true 表示状态确实发生了变化；false 表示变更被拒绝或无意义
     */
    public synchronized boolean transitionTo(AgentState next) {
        if (state.isTerminal()) {
            return false;
        }
        if (state == next) {
            return false;
        }
        /*
         * WAITING_APPROVAL 的出口受控：
         * 只允许回到 RUNNING（批准/拒绝后恢复），
         * 或转 FAILED（过期/检查点不可恢复）、CANCELLED（用户取消）。
         *
         * 特别禁止 WAITING_APPROVAL → TIMED_OUT：
         * 暂停期间没有执行，SSE 也已关闭，
         * 若容器的 onTimeout 迟到一步把挂起任务判成超时，
         * 用户批准时会发现任务已经"失败"，且原因完全不对。
         */
        if (state == AgentState.WAITING_APPROVAL
                && next != AgentState.RUNNING
                && next != AgentState.FAILED
                && next != AgentState.CANCELLED) {
            return false;
        }
        this.state = next;
        return true;
    }

    /**
     * 累计执行时间：已并入的时长 + 当前执行段。
     *
     * 暂停后 segmentStartedAt 为 null，只返回已累计值，
     * 因此审批等待不会被算进任务预算。
     */
    public synchronized Duration elapsed() {
        Instant segmentStart = segmentStartedAt;
        if (segmentStart == null) {
            return activeElapsed;
        }
        return activeElapsed.plus(Duration.between(segmentStart, Instant.now()));
    }

    /**
     * 结束当前执行段（暂停/终态前调用）。
     */
    public synchronized void closeExecutionSegment() {
        if (segmentStartedAt != null) {
            activeElapsed = activeElapsed.plus(
                    Duration.between(segmentStartedAt, Instant.now()));
            segmentStartedAt = null;
        }
    }

    /**
     * 开启新的执行段（恢复时调用）。
     */
    public synchronized void openExecutionSegment() {
        if (segmentStartedAt == null) {
            segmentStartedAt = Instant.now();
        }
    }

    /**
     * 累计执行时间（毫秒），落库到 active_elapsed_millis。
     */
    public long getActiveElapsedMillis() {
        return elapsed().toMillis();
    }

    /**
     * 实际生效的超时：有预算快照用快照，否则用 Agent 当前配置。
     */
    public Duration effectiveTimeout(Duration fallback) {
        return timeoutBudget != null ? timeoutBudget : fallback;
    }

    /**
     * 标记许可已释放；返回 true 表示本次调用是"第一次"。
     *
     * 用于 release-once：只有返回 true 的调用方可以真正 release。
     */
    public boolean markPermitReleased() {
        return permitReleased.compareAndSet(false, true);
    }

    /**
     * 当前 Agent Loop 步数加一。
     */
    public void nextStep() {
        currentStep++;
    }

    /**
     * 增加当前任务累计调用的工具数量。
     *
     * @param count 本轮实际执行的工具数量
     */
    public void addToolCallCount(int count) {
        toolCallCount += count;
    }

    /**
     * 记录一次实际执行的工具名（按调用顺序追加）。
     *
     * 只允许记录 toolName，禁止把 ToolCall.arguments 传入，
     * 保证参数原文不会进入评测数据。
     *
     * @param toolName 实际执行完成的工具名
     */
    public void recordToolName(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            toolTrace.add(toolName);
        }
    }

    /**
     * 获取实际执行过的工具名序列（只读快照）。
     */
    public List<String> getToolTrace() {
        return List.copyOf(toolTrace);
    }

    /**
     * 累加一轮模型调用的 Usage。
     *
     * 由 ToolCallAgent.think 在拿到 ChatResponse 后调用；
     * 参数允许为 0（部分模型/测试桩不返回 usage），此时只累计耗时。
     *
     * @param modelName          本轮响应的模型名（可为 null）
     * @param promptTokensDelta  本轮输入 Token 增量
     * @param completionDelta    本轮输出 Token 增量
     * @param totalDelta         本轮总 Token 增量
     * @param callCostMillis     本轮模型调用耗时（毫秒）
     */
    public void addModelUsage(
            String modelName,
            long promptTokensDelta,
            long completionDelta,
            long totalDelta,
            long callCostMillis
    ) {
        if (modelName != null && !modelName.isBlank()) {
            this.model = modelName;
        }
        this.promptTokens += promptTokensDelta;
        this.completionTokens += completionDelta;
        this.totalTokens += totalDelta;
        this.modelCallCount++;
        this.totalModelCallMillis += callCostMillis;
    }

    public UUID getRunId() {
        return runId;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public AgentState getState() {
        return state;
    }

    public void setState(AgentState state) {
        this.state = state;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 所属会话；null 表示独立任务。
     */
    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * 本轮累积的业务引用；永远不会是 null（无引用时是 EMPTY）。
     */
    public ConversationRefs getConversationRefs() {
        return conversationRefs;
    }

    public void setConversationRefs(ConversationRefs conversationRefs) {
        this.conversationRefs = conversationRefs == null
                ? ConversationRefs.EMPTY
                : conversationRefs;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public void setAgentType(AgentType agentType) {
        this.agentType = agentType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public ChatResponse getPendingResponse() {
        return pendingResponse;
    }

    /**
     * 本次任务的站点级耗尽计数器（每个任务独立）。
     */
    public ToolTargetExhaustionTracker getToolTargetExhaustionTracker() {
        return toolTargetExhaustionTracker;
    }

    public void setPendingResponse(ChatResponse pendingResponse) {
        this.pendingResponse = pendingResponse;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public SseEmitter getEmitter() {
        return emitter;
    }

    public void setEmitter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public String getModel() {
        return model;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public int getModelCallCount() {
        return modelCallCount;
    }

    public long getTotalModelCallMillis() {
        return totalModelCallMillis;
    }

    public int getExecutionEpoch() {
        return executionEpoch;
    }

    public void setExecutionEpoch(int executionEpoch) {
        this.executionEpoch = executionEpoch;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getPendingApprovalJson() {
        return pendingApprovalJson;
    }

    public void setPendingApprovalJson(String pendingApprovalJson) {
        this.pendingApprovalJson = pendingApprovalJson;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(String configVersion) {
        this.configVersion = configVersion;
    }

    public ApprovalCheckpoint getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(ApprovalCheckpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    public String getResumeApprovalId() {
        return resumeApprovalId;
    }

    public void setResumeApprovalId(String resumeApprovalId) {
        this.resumeApprovalId = resumeApprovalId;
    }

    public String getResumeMode() {
        return resumeMode;
    }

    public void setResumeMode(String resumeMode) {
        this.resumeMode = resumeMode;
    }

    public boolean isHandoffLockHeld() {
        return handoffLockHeld;
    }

    public void setHandoffLockHeld(boolean handoffLockHeld) {
        this.handoffLockHeld = handoffLockHeld;
    }
}