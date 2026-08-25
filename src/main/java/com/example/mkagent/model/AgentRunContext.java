package com.example.mkagent.model;

import com.example.mkagent.context.UserContextHolder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
     * 当前任务的唯一标识。
     *
     * 用于日志追踪、未来数据库持久化、SSE 推送事件等。
     */
    private final UUID runId = UUID.randomUUID();

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
     * 执行本任务的 Agent 类型（持久化到 agent_run.agent_type），
     * 由具体 Agent 通过 BaseAgent.getAgentType() 提供。
     */
    private AgentType agentType;

    /**
     * 当前任务开始时间。
     *
     * BaseAgent 会用它和 Instant.now() 计算任务是否超时。
     */
    private final Instant startedAt = Instant.now();

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
        this.state = next;
        return true;
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
}