package com.example.mkagent.agent;

import com.example.mkagent.approval.AgentConfigFingerprint;
import com.example.mkagent.approval.ApprovalCheckpoint;
import com.example.mkagent.approval.ApprovalPauseGateway;
import com.example.mkagent.approval.ApprovalSummary;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalRecord;
import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.approval.CheckpointCodec;
import com.example.mkagent.approval.PauseLimitReachedException;
import com.example.mkagent.approval.PauseRejectedException;
import com.example.mkagent.approval.PauseResult;
import com.example.mkagent.approval.RunHandoffLocks;
import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.exception.ModelCallException;
import com.example.mkagent.model.AgentEventType;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.resilience.ToolTargetExhaustionTracker;
import com.example.mkagent.tools.IdempotentToolCallback;
import com.example.mkagent.tools.ToolCallPlan;
import com.example.mkagent.tools.ToolCallPlanHolder;
import com.example.mkagent.tools.ToolEventMessageProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * ToolCallAgent 负责实现工具调用能力。
 *
 * think(ctx)：
 * 1. 调用模型
 * 2. 获得 ToolCall 或普通文本回答
 *
 * act(ctx)：
 * 1. 执行模型请求的工具
 * 2. 将 ToolResponseMessage 回填到 ctx.messages
 * 3. 下一轮模型可读取工具结果
 */
public class ToolCallAgent extends ReActAgent {

    private static final Logger log =
            LoggerFactory.getLogger(ToolCallAgent.class);

    /**
     * 模型服务"配置类"失败（400 参数错误 / 401、403 鉴权 / 欠费）的脱敏提示。
     *
     * 这类错误重试没有意义，直接结束任务并提示用户检查配置或额度。
     */
    public static final String MODEL_CONFIG_FAILED_MESSAGE =
            "模型服务调用失败，请检查配置或额度";

    /**
     * 模型服务"临时性"失败（5xx / 429 / 网络超时）重试耗尽后的脱敏提示。
     */
    public static final String MODEL_UNAVAILABLE_MESSAGE =
            "模型服务暂时不可用，请稍后重试";

    /**
     * 终止工具名。
     *
     * 必须与 {@code TerminateTool} 的 @Tool(name = ...) 完全一致，
     * 否则终止分支永远不会命中。
     */
    public static final String TERMINATE_TOOL_NAME = "terminate_task";

    /**
     * 连配置都解析不到模型名时的兜底值。
     *
     * 用 "unknown" 而不是空串：空串在日志里看不出来，
     * 也无法在 agent_run.model 上做 GROUP BY。
     */
    public static final String UNKNOWN_MODEL_NAME = "unknown";

    /**
     * 解析 terminate_task 参数用的 JSON 解析器。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * final_answer 的候选字段名。
     *
     * 工具 schema 由 Java 参数名生成，因此是 finalAnswer；
     * 但模型经常按直觉写 final_answer，两种都接受，
     * 否则一次命名差异就等于把用户的答案丢掉。
     */
    private static final List<String> FINAL_ANSWER_KEYS =
            List.of("final_answer", "finalAnswer");

    /**
     * INFO 级别下工具结果的截断长度。
     *
     * 200 字符足以看出"这次调用成没成、大致返回了什么"；
     * 需要完整内容时开 DEBUG。
     */
    private static final int LOG_RESULT_MAX_LENGTH = 200;

    /**
     * 剩余步数不足时追加给模型的系统提示。
     *
     * 只追加到**发送给模型的副本**上，不写进 ctx.messages：
     * 这条提示是"本轮上下文策略"，不是任务事实；
     * 写进历史会污染后续所有轮次，也会进入评测的对话记录。
     */
    public static final String BUDGET_WARNING_MESSAGE =
            "剩余步数不足，请基于已有信息给出最终回答并调用 terminate_task，"
                    + "不要再搜索新资料";

    /**
     * 预算耗尽且收尾调用没有拿到文本时的确定性兜底回答前缀。
     *
     * 用"执行预算"而不是"步数预算"：MAX_STEPS_REACHED 也可能由
     * maxToolCalls（工具调用次数上限）触发，写死"步数"会给出错误的归因。
     */
    public static final String BUDGET_FALLBACK_PREFIX =
            "因执行预算耗尽，本次任务已停止，未能生成完整回答。\n";

    /**
     * 预算耗尽后强制收尾回答的前缀。
     *
     * 必须让用户一眼看出这是"尽力而为"，而不是模型正常完成任务给出的结论。
     *
     * 与 {@link #BUDGET_FALLBACK_PREFIX} 用同一个口径（"执行预算"）：
     * 两个前缀面向同一种终态（MAX_STEPS_REACHED），
     * 一个说"步数预算"、另一个说"执行预算"会让同一次失败出现两种归因。
     */
    public static final String FORCED_FINAL_ANSWER_PREFIX =
            "(因执行预算耗尽，以下为基于已获取信息的尽力回答)\n";

    /**
     * 预算耗尽后强制收尾时追加给模型的指令。
     *
     * 冲突必须**显式**消解，不能依赖"最后一条 SystemMessage 会覆盖前面的规则"：
     * 系统提示词（MkManus / MusicAgent）与 budget warning 都写了
     * "必须调用 terminate_task"，而本轮没有任何工具。两条规则同时存在时，
     * 谁生效没有任何协议保证——模型完全可能按先出现且重复出现过的那条行事，
     * 于是返回一个 tool_call、content 为空，收尾就拿不到文本（真实冒烟里就是这个现象）。
     *
     * 所以这里不靠顺序，直接点名"terminate_task 本轮不存在、该规则不适用"。
     * 常规工具循环的提示词一个字都不改：那条规则在还有工具的轮次里是对的。
     */
    static final String FORCED_FINAL_ANSWER_INSTRUCTION =
            "执行预算已经耗尽，本轮没有提供任何工具，也无法再执行工具。"
                    + "前面所有要求\"必须调用 terminate_task\"或\"继续调用工具\"的规则，"
                    + "在本轮一律不适用：terminate_task 本轮不存在，"
                    + "不要调用它，也不要假装调用它。"
                    + "本轮唯一的任务是输出纯文本总结："
                    + "只依据上面的对话历史和已经拿到的工具结果，"
                    + "直接写出尽力而为的最终回答；"
                    + "没有获取到的信息要明确说明，不要编造；"
                    + "不要请求调用任何工具。";

    /**
     * 剩余步数 ≤ 该值时追加收尾提示。
     *
     * 配置项 mkagent.agent.budget-warning-steps，默认 2；
     * 小于等于 0 表示关闭该提示。
     */
    private int budgetWarningSteps = 2;

    /**
     * 预算耗尽后是否强制收尾（不带工具再问模型一次）。
     *
     * 配置项 mkagent.agent.force-final-answer，默认 true。
     */
    private boolean forceFinalAnswer = true;

    /**
     * 当前使用的大模型。
     *
     * 直接调用 ChatModel 时：
     * 模型能返回 ToolCall，
     * 但不会自动执行工具。
     */
    private final ChatModel chatModel;

    /**
     * 当前 Agent 可以调用的所有工具。
     */
    private final ToolCallback[] availableTools;

    /**
     * Spring AI 的工具执行管理器。
     *
     * 负责：
     * 1. 读取模型返回的工具名与 JSON 参数
     * 2. 找到对应 ToolCallback
     * 3. 调用真实 Java 方法
     * 4. 将结果转成 ToolResponseMessage
     * 5. 返回完整 conversationHistory
     */
    private final ToolCallingManager toolCallingManager;

    /**
     * 工具事件（tool_start / tool_result）消息提供者。
     *
     * 负责把工具名称和原始结果转换成
     * 用户可理解、经过脱敏的 SSE 事件文本。
     */
    private final ToolEventMessageProvider toolEventMessageProvider;

    /**
     * 哪些工具需要人工确认（M3）。为 null 时不做审批拦截。
     */
    private ApprovalToolPolicy approvalToolPolicy;

    /**
     * 暂停持久化入口。为 null 时命中写工具会失败关闭。
     */
    private ApprovalPauseGateway approvalPauseGateway;

    /**
     * 审批摘要构造器。
     */
    private ApprovalSummaryBuilder approvalSummaryBuilder;

    public ToolCallAgent(
            ChatModel chatModel,
            ToolCallback[] availableTools,
            Executor agentExecutor,
            ToolEventMessageProvider toolEventMessageProvider
    ) {
        super(agentExecutor);
        this.chatModel = chatModel;
        this.availableTools = availableTools;
        this.toolEventMessageProvider = toolEventMessageProvider;
        this.toolCallingManager = ToolCallingManager.builder()
                .build();

    }

    /**
     * 当前 Agent 实际持有的工具白名单（主要用于测试断言）。
     */
    public ToolCallback[] getAvailableTools() {
        return availableTools;
    }

    /**
     * 当前 Agent 实际可执行的工具名集合。
     *
     * 供审批元数据校验使用：这是"真正能被执行的工具"，
     * 与"扫到了哪些 @Tool 方法"必须一一对上。
     */
    private Set<String> exposedToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolCallback callback : availableTools) {
            if (callback != null && callback.getToolDefinition() != null) {
                names.add(callback.getToolDefinition().name());
            }
        }
        return names;
    }

    public int getBudgetWarningSteps() {
        return budgetWarningSteps;
    }

    /**
     * 配置剩余步数提示阈值（≤0 关闭）。
     */
    public void setBudgetWarningSteps(int budgetWarningSteps) {
        this.budgetWarningSteps = budgetWarningSteps;
    }

    public boolean isForceFinalAnswer() {
        return forceFinalAnswer;
    }

    /**
     * 配置是否在预算耗尽后强制收尾。
     */
    public void setForceFinalAnswer(boolean forceFinalAnswer) {
        this.forceFinalAnswer = forceFinalAnswer;
    }

    /**
     * 注入审批策略（M3，批次 1）。为 null 表示该 Agent 不支持审批。
     */
    public void setApprovalToolPolicy(ApprovalToolPolicy approvalToolPolicy) {
        this.approvalToolPolicy = approvalToolPolicy;
    }

    /**
     * 注入暂停持久化入口（M3，批次 1）。
     */
    public void setApprovalPauseGateway(ApprovalPauseGateway approvalPauseGateway) {
        this.approvalPauseGateway = approvalPauseGateway;
    }

    /**
     * 注入审批摘要构造器（M3，批次 1）。
     */
    public void setApprovalSummaryBuilder(ApprovalSummaryBuilder approvalSummaryBuilder) {
        this.approvalSummaryBuilder = approvalSummaryBuilder;
    }

    /**
     * 本轮是否需要追加"剩余步数不足"提示。
     *
     * 剩余步数 = maxSteps - 当前步 + 1（**含当前这一步**）。
     * 以 maxSteps=8、阈值=2 为例：
     * 第 7 步剩余 2 → 提示，第 8 步剩余 1 → 提示，
     * 正好覆盖最后两步；第 6 步剩余 3 → 不提示。
     */
    boolean needsBudgetWarning(AgentRunContext ctx) {
        if (budgetWarningSteps <= 0) {
            return false;
        }
        int remaining = getMaxSteps() - ctx.getCurrentStep() + 1;
        return remaining <= budgetWarningSteps;
    }

    /**
     * 构造本轮发送给模型的消息列表。
     *
     * 需要提示时返回**副本 + 一条 SystemMessage**，
     * 绝不修改 ctx.getMessages()：
     * 1. 提示是"本轮策略"，不是任务事实，写进历史会污染后续所有轮次；
     * 2. 评测会读取 ctx 的消息做断言，历史必须保持干净；
     * 3. 模型看到的历史与实际发生过的对话不一致时，很难排查问题。
     */
    private List<Message> messagesForModel(AgentRunContext ctx) {
        if (!needsBudgetWarning(ctx)) {
            return ctx.getMessages();
        }

        int remaining = getMaxSteps() - ctx.getCurrentStep() + 1;

        log.info(
                "剩余步数不足，已向模型追加收尾提示：runId={}, step={}/{}, remaining={}",
                ctx.getRunId(), ctx.getCurrentStep(), getMaxSteps(), remaining
        );

        List<Message> messages = new ArrayList<>(ctx.getMessages());
        messages.add(new SystemMessage(BUDGET_WARNING_MESSAGE));
        return messages;
    }

    /**
     * 工具名以逗号拼成一行，用于第一步的 INFO 摘要。
     */
    String toolNamesForLog() {
        return Arrays.stream(availableTools)
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.joining(", "));
    }

    /**
     * 日志用结果摘要：截断到 {@link #LOG_RESULT_MAX_LENGTH} 字符。
     *
     * 截断而不是换行折叠：原始结果是 JSON 字符串字面量，
     * 换行本身已是转义的 \n，天然单行，不需要再处理。
     */
    String abbreviateForLog(String result) {
        if (result == null) {
            return "null";
        }
        if (result.length() <= LOG_RESULT_MAX_LENGTH) {
            return result;
        }
        return result.substring(0, LOG_RESULT_MAX_LENGTH)
                + "…(共 " + result.length() + " 字符)";
    }

    /**
     * 构造本轮模型调用使用的工具选项。
     *
     * think() 与 act() 必须使用完全一致的选项，
     * 因此统一收敛到这一个私有方法，避免两处各写一份后出现不一致。
     *
     * internalToolExecutionEnabled(false) 的语义：
     * 明确告诉模型实现层“不要在 ChatModel 内部自动执行工具”，
     * 工具只由 act(ctx) 中的 ToolCallingManager 手动执行一次。
     * 这样可以杜绝“框架内部执行 + 我们手动执行”造成的重复调用。
     *
     * 这里刻意**不设置** toolContext：
     * toolContext 是 Prompt 级的，一旦非空，框架会对所有工具回调调用
     * call(String, ToolContext)，而该方法的接口默认实现是"非空就抛
     * UnsupportedOperationException（Tool context is not supported!）"，
     * 会打断只实现 call(String) 的回调。
     * 站点级熔断所需的每任务状态改用 act() 内绑定线程的
     * ToolTargetExhaustionTracker，见 act(ctx)。
     */
    private ToolCallingChatOptions buildToolCallingOptions() {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(availableTools)
                .internalToolExecutionEnabled(false)
                .build();
    }

    /**
     * Think：调用模型，让模型决定下一步。
     */
    @Override
    protected boolean think(AgentRunContext ctx) {
        /*
         * 本轮模型可用工具。
         *
         * 手动调用 ChatModel 时，工具要通过
         * ToolCallingChatOptions 传入。
         *
         * 选项统一由 buildToolCallingOptions() 构造，
         * 其中显式关闭了“模型内部自动执行工具”。
         */
        ToolCallingChatOptions options = buildToolCallingOptions();

        /*
         * Prompt 包含：
         * 1. 消息历史（系统提示、用户消息、历史 ToolCall、工具结果）；
         *    剩余步数不足时，这里是**副本 + 一条收尾提示**，
         *    ctx.messages 本身不被修改；
         * 2. options：当前可用工具定义
         */
        Prompt prompt = new Prompt(
                messagesForModel(ctx),
                options
        );


        /*
         * 打印所有可用工具。
         *
         * 日志治理：工具清单在**整个任务期间不会变化**，
         * 每个 step 都按 INFO 打一遍纯属刷屏（8 步 × 3 工具 = 24 行噪声，
         * 还会把真正有用的工具执行结果挤出视野）。
         * 因此只在第一步打一次 INFO 摘要，其余 step 降到 DEBUG；
         * 每个工具的完整定义（description）始终留在 DEBUG，
         * 需要排查"模型为什么没选某个工具"时把日志级别调到 DEBUG 即可。
         */
        if (ctx.getCurrentStep() == 1) {
            log.info(
                    "当前提供给模型的工具（共 {} 个）：{}",
                    availableTools.length,
                    toolNamesForLog()
            );
        }

        for (ToolCallback tool : availableTools) {
            log.debug(
                    "工具定义：name={}, description={}",
                    tool.getToolDefinition().name(),
                    tool.getToolDefinition().description()
            );
        }

        /*
         * 只调用模型。
         *
         * 注意：
         * 直接使用 ChatModel 不会自动执行工具。
         *
         * 失败快速通道：
         * 模型层的重试策略（ModelRetryConfig）已经只对 5xx/429/超时做有限重试，
         * 走到这里的 AiException 说明"重试没有意义或已经耗尽"。
         * 此时立刻把任务置为 FAILED，而不是让它一直挂到 Agent 总超时（TIMED_OUT），
         * 前端能马上收到一条脱敏的 ERROR 事件。
         */
        long modelCallStart = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException e) {
            ModelCallException modelCallException = toModelCallException(e);
            if (modelCallException != null) {
                /*
                 * 立即置 FAILED 并推送一条脱敏 ERROR 事件：
                 * 前端不必等到 Agent 总超时才知道"模型调不通"。
                 * BaseAgent 会识别本异常并跳过重复的通用错误事件。
                 */
                ctx.transitionTo(AgentState.FAILED);
                sendEvent(
                        ctx,
                        AgentEventType.ERROR,
                        modelCallException.getUserMessage()
                );
                throw modelCallException;
            }
            throw e;
        }
        long modelCallCost = System.currentTimeMillis() - modelCallStart;

        /*
         * 记录本轮模型 Usage（Token 消耗 + 耗时），
         * 累加到任务级上下文，终态时随 agent_run 一起落库。
         */
        recordUsage(ctx, response, modelCallCost);

        /*
         * 保存本轮模型响应。
         *
         * 不要放到 ToolCallAgent 的成员变量中，
         * 否则 Spring 单例并发请求时会串任务。
         */
        ctx.setPendingResponse(response);

        AssistantMessage assistantMessage =
                response.getResult().getOutput();

        boolean hasToolCalls = response.hasToolCalls();

        if (hasToolCalls) {
            assistantMessage.getToolCalls().forEach(toolCall ->
                    log.info(
                            "模型请求工具：name={}, arguments={}",
                            toolCall.name(),
                            toolCall.arguments()
                    )
            );
        }

        log.info(
                "Agent 思考完成：runId={}, step={}, hasToolCalls={}, text={}",
                ctx.getRunId(),
                ctx.getCurrentStep(),
                hasToolCalls,
                assistantMessage.getText()
        );

        /*
         * 没有工具调用，通常表示模型已给出最终回答。
         */
        if (!hasToolCalls) {
            /*
             * 此时 ToolCallingManager 不会参与，
             * 因此普通 AssistantMessage 要自己写入历史。
             */
            ctx.getMessages().add(assistantMessage);

            String finalAnswer = assistantMessage.getText();

            if (finalAnswer == null || finalAnswer.isBlank()) {
                finalAnswer = "任务结束：模型没有返回有效文本，也没有请求工具。";
            }

            ctx.setFinalAnswer(finalAnswer);

            /*
             * 模型给出有效最终回答，任务成功，结束 Agent Loop。
             */
            ctx.transitionTo(AgentState.SUCCEEDED);

            return false;
        }

        /*
         * 有 ToolCall：
         * 不在这里手动加入 assistantMessage。
         *
         * 因为 act(ctx) 中的 ToolCallingManager 会统一生成：
         * 旧 history
         * + AssistantMessage（含 ToolCall）
         * + ToolResponseMessage
         */
        return true;
    }

    /**
     * 把模型调用异常翻译成"可立即结束任务"的 {@link ModelCallException}。
     *
     * 判定规则（沿 cause 链查找，兼容 RestClient 的包装）：
     * 1. NonTransientAiException（400/401/403/欠费）→ 配置类错误，一次都不重试；
     * 2. TransientAiException（5xx/429）→ 到这里说明重试已耗尽，不再额外等待；
     * 3. 未经过 Spring AI 异常映射的 HTTP 错误（HttpClientErrorException /
     *    HttpServerErrorException）按状态码区分：429/5xx 视为暂时不可用，其余 4xx 视为配置错误；
     * 4. 网络超时（ResourceAccessException）→ 暂时不可用；
     * 5. 其它异常（编程错误、空指针等）→ 返回 null，保持原有异常传播行为。
     *
     * 为什么 3 也要覆盖：
     * 不同版本的 DashScope 客户端可能绕过 Spring AI 的 ResponseErrorHandler，
     * 直接抛 RestClient 的 HTTP 异常。若只认 AiException，
     * "模型地址/参数配错"就会退化成通用报错，用户看不出是配置问题。
     *
     * @return 可立即失败的业务异常；不属于模型调用失败时返回 null
     */
    private ModelCallException toModelCallException(Throwable error) {
        Throwable current = error;

        // 限制深度，避免异常链自引用导致死循环。
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof NonTransientAiException) {
                return new ModelCallException(MODEL_CONFIG_FAILED_MESSAGE, error);
            }
            if (current instanceof TransientAiException) {
                return new ModelCallException(MODEL_UNAVAILABLE_MESSAGE, error);
            }
            if (current instanceof HttpClientErrorException clientError) {
                boolean throttled = clientError.getStatusCode().value() == 429;
                return new ModelCallException(
                        throttled ? MODEL_UNAVAILABLE_MESSAGE : MODEL_CONFIG_FAILED_MESSAGE,
                        error
                );
            }
            if (current instanceof HttpServerErrorException
                    || current instanceof ResourceAccessException) {
                return new ModelCallException(MODEL_UNAVAILABLE_MESSAGE, error);
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
     * 从 ChatResponse 提取 Usage 并累加到任务上下文。
     *
     * Spring AI 1.0.0 下：
     * 1. 非流式调用（本项目 Agent 路径）的 DashScope 响应
     *    在 ChatResponseMetadata 中稳定携带 Usage 与模型名；
     * 2. 部分实现（测试桩/旧模型）可能返回空 Usage（全 0）
     *    或 null，全部做了空安全处理，绝不影响主流程。
     *
     * 流式说明：stream() 分片默认不携带 usage（DashScope 需
     * stream_options 且只在最后一片返回），因此流式聊天路径
     * 不统计 Usage，避免为拿 usage 而破坏 SSE 输出。
     */
    private void recordUsage(
            AgentRunContext ctx,
            ChatResponse response,
            long modelCallCost
    ) {
        try {
            ChatResponseMetadata metadata = response.getMetadata();
            String model = resolveModelName(metadata.getModel());

            Usage usage = metadata.getUsage();
            long promptTokens = safeTokens(usage == null ? null : usage.getPromptTokens());
            long completionTokens =
                    safeTokens(usage == null ? null : usage.getCompletionTokens());
            long totalTokens = promptTokens + completionTokens;

            ctx.addModelUsage(
                    model,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    modelCallCost
            );

            log.info(
                    "模型 Usage 记录：runId={}, model={}, promptTokens={}, completionTokens={}, totalTokens={}, cost={}ms",
                    ctx.getRunId(),
                    model,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    modelCallCost
            );
        } catch (Exception e) {
            // Usage 属于观测数据，提取失败只记日志，不影响任务。
            log.warn("模型 Usage 提取失败（不影响任务）：runId={}",
                    ctx.getRunId(), e);
        }
    }

    /**
     * 解析本轮响应的模型名，空则回退到"当前配置的模型名"。
     *
     * 三级兜底：
     * 1. 响应 metadata 里的 model（最准确，就是本次实际服务的模型）；
     * 2. ChatModel Bean 上配置的模型名（metadata 为空时的兜底）；
     * 3. "unknown"（连配置都读不到时的最后兜底）。
     *
     * 为什么不能留空：日志里 `model=` 与 agent_run.model 为空
     * 会让成本归因、按模型分组的统计全部失真。
     */
    String resolveModelName(String metadataModel) {
        if (metadataModel != null && !metadataModel.isBlank()) {
            return metadataModel;
        }

        String configured = ConfiguredModelNameResolver.resolve(chatModel);
        if (configured != null) {
            /*
             * 走兜底时记 DEBUG 即可：每个 step 都会命中，
             * 打 INFO 会把日志刷满。
             */
            log.debug(
                    "响应未携带模型名，回退到配置值：model={}",
                    configured
            );
            return configured;
        }

        return UNKNOWN_MODEL_NAME;
    }

    /**
     * 预算耗尽后的强制收尾：不带工具再问模型一次，把已有信息总结成回答。
     *
     * 为什么值得多花一次调用：
     * 预算耗尽时模型手里通常已经有搜索结果/网页正文，
     * 只是没来得及写结论。旧行为把"安全停止：达到最大步骤数……"
     * 当成最终回答返回，用户等于什么答案都没拿到。
     *
     * 四条硬约束：
     * 1. **不改终态**：仍然是 MAX_STEPS_REACHED（这是成本事实，不能伪装成 SUCCEEDED）；
     * 2. **不带工具**：options 里没有 toolCallbacks，且模型默认选项已被配置层清空工具
     *    （见 DashScopeChatModelConfig#stripDefaultTools——只靠 options 为空**不够**，
     *    DashScopeChatModel 会用 mergeToolCallbacks 把 defaultOptions 里的工具合并回来）；
     * 3. **失败必须降级**：拿不到文本时写确定性兜底回答，绝不留下空 finalAnswer；
     * 4. **取消/超时优先**：终态一旦不是 MAX_STEPS_REACHED，或线程被中断，
     *    一律不写兜底——不能把取消伪装成"预算耗尽"。
     */
    @Override
    protected void onBudgetExhausted(AgentRunContext ctx) {
        if (!forceFinalAnswer) {
            /*
             * 开关契约：关闭后不新增模型调用，也不写本次的确定性兜底。
             * 这两件事是同一个承诺——"不要在预算耗尽后再产出任何东西"，
             * 拆开做会让开关的含义变得没法一句话说清。
             */
            log.info(
                    "预算耗尽且无最终回答，force-final-answer=false，保持既有行为"
                            + "（不新增调用、不写兜底）：runId={}",
                    ctx.getRunId()
            );
            return;
        }

        String answer = requestForcedFinalAnswer(ctx);

        /*
         * 收尾调用期间可能被取消/超时：此时不能补"预算耗尽"的文案，
         * 否则用户看到的是"正常收尾"，而实际是取消。
         * 判定收敛在 canWriteBudgetFallback 里，便于单独测试这条契约。
         */
        if (!canWriteBudgetFallback(ctx)) {
            log.info(
                    "收尾期间任务已不是预算终态或线程已中断，不写兜底回答："
                            + "runId={}, state={}, interrupted={}",
                    ctx.getRunId(),
                    ctx.getState(),
                    Thread.currentThread().isInterrupted()
            );
            return;
        }

        if (answer == null || answer.isBlank()) {
            /*
             * 确定性兜底：不花第二次调用，也不拼任何工具原文/URL/参数/异常。
             * 为什么必须有它：收尾失败时若 finalAnswer 为空，
             * SSE 不发 final_answer、数据库该列保持 NULL，用户只看到
             * "安全停止"——这正是真实冒烟里暴露的问题。
             */
            ctx.setFinalAnswer(buildBudgetFallbackAnswer(ctx));
            log.warn(
                    "预算耗尽后的收尾调用没有拿到有效文本，改用确定性兜底回答：runId={}, chars={}",
                    ctx.getRunId(), ctx.getFinalAnswer().length()
            );
            return;
        }

        ctx.setFinalAnswer(
                truncateFinalAnswer(FORCED_FINAL_ANSWER_PREFIX + answer.trim())
        );

        log.info(
                "预算耗尽，已生成尽力而为的最终回答：runId={}, chars={}, totalTokens={}",
                ctx.getRunId(),
                ctx.getFinalAnswer().length(),
                ctx.getTotalTokens()
        );
    }

    /**
     * 是否允许写"预算耗尽"的兜底回答。
     *
     * 两个条件缺一不可：
     * 1. 终态仍是 MAX_STEPS_REACHED——收尾期间被取消/超时（终态已变）时不能写，
     *    否则取消会被伪装成一次正常的预算收尾；
     * 2. 当前线程未被中断——取消接口会 interrupt 任务线程，
     *    这时模型调用即使返回了文本也不可信（可能只是半截）。
     *
     * 抽成独立方法而不是内联：这两个条件无法在完整 run() 流程里稳定构造
     * （终态是终态，外部改不动），只能直接对谓词做契约测试。
     */
    static boolean canWriteBudgetFallback(AgentRunContext ctx) {
        return ctx.getState() == AgentState.MAX_STEPS_REACHED
                && !Thread.currentThread().isInterrupted();
    }

    /**
     * 预算耗尽且收尾调用没有拿到文本时的确定性兜底回答。
     *
     * 三条约束：
     * 1. 只含步数与工具调用次数（非敏感计数），
     *    不拼工具原文、URL、参数、异常或模型内部细节；
     * 2. 纯函数：不读时钟、不读模型、不读数据库，便于单测；
     * 3. 不改变终态，调用方仍保持 MAX_STEPS_REACHED。
     */
    static String buildBudgetFallbackAnswer(AgentRunContext ctx) {
        return BUDGET_FALLBACK_PREFIX
                + "本次已执行 " + ctx.getCurrentStep() + " 步、调用工具 "
                + ctx.getToolCallCount() + " 次。\n"
                + "请缩小任务范围后重试。";
    }

    /**
     * 执行一次"不带工具"的模型调用，用于预算耗尽后的收尾。
     *
     * ================== 关于"一次调用"的口径 ==================
     *
     * 这里只有**一次业务级收尾调用**：不重试、不做多轮总结。
     * 但底层 ChatModel 自带 RetryTemplate（mkagent.model-retry.*，
     * 只对 5xx/429/超时生效），所以一次业务调用可能对应**多个 HTTP 请求**。
     * 文档与注释都不要写成"只会发生一个 HTTP 请求"。
     *
     * 该调用的 Token 通过 {@link #recordUsage} 计入本任务 Usage——
     * 它确实花了钱，必须出现在成本统计里，不能当成免费操作。
     *
     * @return 模型给出的文本；没有可用文本时返回 null（由调用方写确定性兜底）
     */
    String requestForcedFinalAnswer(AgentRunContext ctx) {
        // 取消/超时已中断线程时不再发起新的模型调用。
        if (Thread.currentThread().isInterrupted()) {
            log.info("线程已中断，跳过强制收尾调用：runId={}", ctx.getRunId());
            return null;
        }

        /*
         * 副本 + 一条指令：同样不修改 ctx.messages。
         * 这次调用是"一次性的收尾请求"，不属于任务对话的一部分。
         */
        List<Message> messages = new ArrayList<>(ctx.getMessages());
        messages.add(new SystemMessage(FORCED_FINAL_ANSWER_INSTRUCTION));

        /*
         * 不传 toolCallbacks / toolNames。
         *
         * 注意这不是"无工具"的全部保证：DashScopeChatModel#buildRequestPrompt
         * 会用 mergeToolCallbacks(runtime, defaultOptions) 合并默认选项，
         * 而该方法的语义是"runtime 为空就返回 defaults"。
         * 真正的保证来自配置层把 defaultOptions 的工具清空
         * （DashScopeChatModelConfig#stripDefaultTools），见 ForcedFinalAnswerRequestShapeTest。
         */
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        long modelCallStart = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(messages, options));
        } catch (RuntimeException e) {
            /*
             * 取消/超时：异常链里带 InterruptedException，或线程已被中断。
             * 必须原样抛出，交给 BaseAgent 判 CANCELLED——
             * 如果在这里吞掉，取消就会变成一条"预算耗尽"的正常回答。
             */
            if (Thread.currentThread().isInterrupted()
                    || causedByInterruption(e)) {
                log.info(
                        "收尾调用被中断（取消/超时），按取消语义向上抛出：runId={}",
                        ctx.getRunId()
                );
                throw e;
            }

            /*
             * 其余异常不是任务失败：任务早就因为预算耗尽结束了，
             * 这里只影响"有没有答案"，所以只记 WARN 并降级到确定性兜底。
             */
            log.warn(
                    "收尾模型调用失败：reason=exception, type={}, message={}, runId={}",
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    ctx.getRunId()
            );
            return null;
        }
        long modelCallCost = System.currentTimeMillis() - modelCallStart;

        // 计入 Usage：Token 与耗时都要算进这次任务（失败/空文本也算，钱已经花了）。
        recordUsage(ctx, response, modelCallCost);

        return extractForcedAnswerText(ctx, response);
    }

    /**
     * 从收尾响应里取文本，并把"为什么没取到"拆成可检索的原因。
     *
     * 只记录形状（是否存在、数量、长度、finishReason），
     * 绝不记录正文、工具名、参数或完整响应。
     *
     * 文本与 ToolCall 同时存在时：**采用文本**（模型确实写了结论），
     * 但照样告警说明工具调用被忽略——收尾阶段永远不执行任何工具。
     */
    private String extractForcedAnswerText(
            AgentRunContext ctx,
            ChatResponse response
    ) {
        if (response == null) {
            log.warn("收尾响应形状：reason=null-response, runId={}", ctx.getRunId());
            return null;
        }

        Generation generation = response.getResult();
        if (generation == null) {
            log.warn("收尾响应形状：reason=no-generation, runId={}", ctx.getRunId());
            return null;
        }

        AssistantMessage output = generation.getOutput();
        if (output == null) {
            log.warn("收尾响应形状：reason=no-output, runId={}", ctx.getRunId());
            return null;
        }

        boolean hasToolCalls = response.hasToolCalls();
        int toolCallCount = output.getToolCalls() == null
                ? 0
                : output.getToolCalls().size();
        String text = output.getText();
        int textLength = text == null ? 0 : text.length();

        log.info(
                "收尾响应形状：runId={}, hasToolCalls={}, toolCallCount={}, textLength={}, finishReason={}",
                ctx.getRunId(),
                hasToolCalls,
                toolCallCount,
                textLength,
                resolveFinishReason(generation)
        );

        if (hasToolCalls) {
            log.warn(
                    "收尾响应包含工具调用，按约定不执行：runId={}, toolCallCount={}",
                    ctx.getRunId(), toolCallCount
            );
        }

        if (text == null || text.isBlank()) {
            log.warn(
                    "收尾响应没有可用文本：reason={}, runId={}",
                    hasToolCalls ? "tool-call-only" : "blank-text",
                    ctx.getRunId()
            );
            return null;
        }

        return text;
    }

    /**
     * finishReason 的空安全读取：取不到时明确记为 unknown，不抛异常。
     */
    private String resolveFinishReason(Generation generation) {
        try {
            if (generation.getMetadata() == null
                    || generation.getMetadata().getFinishReason() == null
                    || generation.getMetadata().getFinishReason().isBlank()) {
                return "unknown";
            }
            return generation.getMetadata().getFinishReason();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * 终止任务时的最终回答：四级优先。
     *
     * 1. terminate_task 的 final_answer 参数 —— 模型明确写给用户的答案，最可靠；
     * 2. 同一轮 assistant 文本 —— 模型在调用工具的那条消息里附带说明文字时使用；
     * 3. ctx 上已有的 finalAnswer —— 之前的步骤已经确定过答案时不要覆盖；
     * 4. 工具执行摘要 —— 前几项都没有时的兜底（就是旧行为）。
     *
     * 为什么必须这样排：
     * 真实运行里模型在同一轮既写说明又调 terminate_task，
     * 旧逻辑只看工具结果，最终回答变成"任务结束：任务结束已确认"，
     * 用户什么答案都拿不到。越靠前越接近"模型专门为最终回答写的内容"。
     */
    String resolveTerminateFinalAnswer(
            AgentRunContext ctx,
            ChatResponse response,
            ToolResponseMessage toolResponseMessage
    ) {
        String fromArgument = extractTerminateFinalAnswerArgument(response);
        if (hasText(fromArgument)) {
            log.info(
                    "终止任务的最终回答取自 final_answer 参数：runId={}, chars={}",
                    ctx.getRunId(), fromArgument.length()
            );
            return truncateFinalAnswer(fromArgument);
        }

        String assistantText = response.getResult() == null
                ? null
                : response.getResult().getOutput().getText();
        if (hasText(assistantText)) {
            log.info(
                    "终止任务的最终回答取自同轮 assistant 文本：runId={}, chars={}",
                    ctx.getRunId(), assistantText.length()
            );
            return truncateFinalAnswer(assistantText);
        }

        /*
         * 已有答案优先于工具摘要：把"之前算出来的答案"换成
         * "任务结束：任务结束已确认"是纯粹的信息丢失。
         */
        if (hasText(ctx.getFinalAnswer())) {
            log.info(
                    "终止任务的最终回答沿用 ctx 上已有的答案：runId={}",
                    ctx.getRunId()
            );
            return ctx.getFinalAnswer();
        }

        log.warn(
                "终止任务时没有任何可用答案，回退到工具摘要：runId={}",
                ctx.getRunId()
        );
        return summarizeToolResponses(toolResponseMessage);
    }

    /**
     * 从 terminate_task 的工具调用参数里取 final_answer。
     *
     * 参数是模型输出的 JSON 字符串，解析失败只记 WARN 并继续走下一级兜底，
     * 绝不因为一个格式问题让用户拿不到答案。
     */
    String extractTerminateFinalAnswerArgument(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }

        List<AssistantMessage.ToolCall> toolCalls =
                response.getResult().getOutput().getToolCalls();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            if (!TERMINATE_TOOL_NAME.equals(toolCall.name())) {
                continue;
            }

            String arguments = toolCall.arguments();
            if (arguments == null || arguments.isBlank()) {
                continue;
            }

            try {
                JsonNode root = OBJECT_MAPPER.readTree(arguments);
                for (String key : FINAL_ANSWER_KEYS) {
                    JsonNode value = root.get(key);
                    if (value != null && !value.isNull()) {
                        String text = value.asText();
                        if (hasText(text)) {
                            return text;
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn(
                        "terminate_task 参数不是合法 JSON，跳过 final_answer：reason={}",
                        e.getOriginalMessage()
                );
            }
        }

        return null;
    }

    /**
     * 旧行为的兜底：把工具结果转成"任务结束：xxx"。
     */
    private String summarizeToolResponses(ToolResponseMessage toolResponseMessage) {
        return toolResponseMessage.getResponses()
                .stream()
                .map(responseItem -> String.format(
                        "任务结束：%s",
                        toolEventMessageProvider.resultMessage(
                                responseItem.name(),
                                responseItem.responseData()
                        )
                ))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 与 {@link com.example.mkagent.tools.TerminateTool} 使用同一个上限，
     * 保证"参数路径"和"文本路径"截断口径一致。
     */
    private String truncateFinalAnswer(String text) {
        int max = com.example.mkagent.tools.TerminateTool.MAX_FINAL_ANSWER_LENGTH;
        if (text.length() <= max) {
            return text;
        }
        String suffix = "…（回答过长已截断）";
        return text.substring(0, max - suffix.length()) + suffix;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Usage 的 token 字段可能为 null（部分实现），空安全转 0。
     */
    private long safeTokens(Integer tokens) {
        return tokens == null ? 0L : tokens.longValue();
    }

    /**
     * 命中写工具：整轮暂停，**一个工具都不执行**。
     *
     * 顺序（每一步失败都有明确后果）：
     * 1. 先算配置指纹与检查点：算不出来就别暂停，避免留下半份数据；
     * 2. 拿执行权交接锁：保证"暂停提交 → 释放许可 → 注销"与批准不重叠；
     * 3. 切状态：切不动说明任务已被取消/终态，直接放弃；
     * 4. 事务落库：agent_run 暂停 + 检查点 + 审批记录；
     * 5. 提交后发现状态又变了（并发取消）：撤销这条审批，不留垃圾。
     *
     * @return null：不产生 STEP 事件（暂停不是"完成了一轮"）
     */
    private String pauseForApproval(
            AgentRunContext ctx,
            ChatResponse response,
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        if (approvalPauseGateway == null || approvalSummaryBuilder == null) {
            ctx.setErrorCode("APPROVAL_DISABLED");
            throw new IllegalStateException(
                    "审批组件未装配，拒绝执行需要人工确认的写操作");
        }

        AssistantMessage assistantMessage = response.getResult().getOutput();

        String configVersion = AgentConfigFingerprint.compute(
                getAgentType(),
                getSystemPrompt(),
                availableTools,
                approvalToolPolicy.requiredToolNames()
        );
        ctx.setConfigVersion(configVersion);

        /*
         * 结束当前执行段：审批等待不计入任务预算。
         * 恢复时会重新 openExecutionSegment()。
         */
        ctx.closeExecutionSegment();

        ApprovalCheckpoint checkpoint =
                buildCheckpoint(ctx, assistantMessage, configVersion);

        ApprovalSummary summary = approvalSummaryBuilder.build(toolCalls, ctx.getUserId());

        String runId = ctx.getRunId().toString();
        RunHandoffLocks locks = getHandoffLocks();
        if (locks != null) {
            locks.acquire(runId);
            /*
             * 锁要一直持有到"并发许可释放 + 注册表注销"之后，
             * 由 BaseAgent 的 finally 统一放锁——
             * 在这里放锁会重新打开"批准抢在释放之前"的窗口。
             */
            ctx.setHandoffLockHeld(true);
        }

        if (!ctx.transitionTo(AgentState.WAITING_APPROVAL)) {
            log.info("放弃暂停：任务已进入终态 runId={}, state={}",
                    runId, ctx.getState());
            return null;
        }

        PauseResult result;
        try {
            /*
             * 事务边界就在这里：persist 是跨 Bean 的 @Transactional 调用，
             * 抛出时事务已经回滚完毕，数据库里没有半截暂停。
             * 因此"把异常转成脱敏错误"只能发生在这一层之后，
             * 不能在事务内部 catch 后继续提交。
             */
            result = approvalPauseGateway.persist(ctx, checkpoint, summary);
        } catch (PauseLimitReachedException e) {
            /*
             * 审批次数触顶：这次暂停没有成立（事务已回滚，approval_count 复原），
             * 任务必须终止而不是继续等人工确认——否则模型可以靠反复触发写工具
             * 把一次任务无限拖长。
             *
             * 不执行任何写工具，也不创建下一条审批。
             */
            ctx.transitionTo(AgentState.RUNNING);
            ctx.setErrorCode("APPROVAL_LIMIT_REACHED");
            throw new IllegalStateException("审批次数已达上限，任务终止", e);
        } catch (PauseRejectedException e) {
            /*
             * CAS 未命中（被取消/代次已变）：暂停根本没成立，
             * 由上层按取消或失败收尾，这里不覆盖状态。
             */
            throw e;
        } catch (RuntimeException e) {
            /*
             * 落库失败（检查点/审批写入异常）：内存状态必须跟着回退，
             * 否则会留下"ctx 说在等审批、库里还是 RUNNING"的假暂停。
             */
            ctx.transitionTo(AgentState.RUNNING);
            ctx.setErrorCode("APPROVAL_PERSIST_FAILED");
            throw new IllegalStateException("审批暂停写入失败，已回滚", e);
        }

        ctx.setApprovalId(result.approvalId());
        ctx.setPendingApprovalJson(result.eventJson());

        /*
         * 提交暂停后状态可能已被取消（并发取消窗口）。
         * 此时审批记录已经写进去了，必须撤销，
         * 否则用户会看到一条"批准了也没人执行"的僵尸记录。
         */
        if (ctx.getState() != AgentState.WAITING_APPROVAL) {
            approvalPauseGateway.abandon(result.approvalId(), "state=" + ctx.getState());
            log.warn("暂停后状态已变化，已撤销审批：runId={}, state={}",
                    runId, ctx.getState());
            return null;
        }

        log.info(
                "已暂停等待审批：runId={}, approvalId={}, step={}, toolCount={}, configVersion={}",
                runId, result.approvalId(), ctx.getCurrentStep(),
                toolCalls.size(), configVersion
        );

        return null;
    }

    /**
     * 组装检查点。
     *
     * 关键点：`messages` 只存本轮**之前**的历史，
     * 本轮的 Assistant(toolCalls) 单独放 `pendingAssistant`。
     *
     * 恢复（批次 2）时：用 `messages` 构造 Prompt，把 `pendingAssistant`
     * 包装成 ChatResponse 交给 `executeToolCalls`，由框架追加一次；
     * **不能**先 add 进 messages，否则 assistant 会出现两次、配对错位。
     * 拒绝路径不执行工具，才手动追加 assistant + 拒绝回执。
     */
    private ApprovalCheckpoint buildCheckpoint(
            AgentRunContext ctx,
            AssistantMessage assistantMessage,
            String configVersion
    ) {
        ApprovalCheckpoint.CheckpointMessage assistant =
                CheckpointCodec.fromAssistant(assistantMessage);

        return new ApprovalCheckpoint(
                ApprovalCheckpoint.CURRENT_SCHEMA_VERSION,
                configVersion,
                ctx.getRunId().toString(),
                ctx.getUserId(),
                getAgentType() == null ? "UNKNOWN" : getAgentType().name(),
                ctx.getCurrentStep(),
                ctx.getToolCallCount(),
                CheckpointCodec.toCheckpointMessages(ctx.getMessages()),
                assistant,
                assistant.toolCalls(),
                new ApprovalCheckpoint.BudgetSnapshot(
                        getMaxSteps(),
                        getMaxToolCalls(),
                        getTimeout().toSeconds()
                ),
                new ApprovalCheckpoint.UsageSnapshot(
                        ctx.getModel(),
                        ctx.getPromptTokens(),
                        ctx.getCompletionTokens(),
                        ctx.getTotalTokens(),
                        ctx.getModelCallCount(),
                        ctx.getTotalModelCallMillis()
                ),
                new ApprovalCheckpoint.TimingSnapshot(
                        ctx.getStartedAt().toString(),
                        ctx.getActiveElapsedMillis()
                ),
                AgentConfigFingerprint.whitelistHash(availableTools)
        );
    }

    /**
     * Act：执行模型请求的工具。
     */
    @Override
    protected String act(AgentRunContext ctx) {
        ChatResponse response = ctx.getPendingResponse();

        if (response == null || !response.hasToolCalls()) {
            return "本轮没有需要执行的工具。";
        }

        /*
         * 审批拦截：必须在 executeToolCalls 之前。
         *
         * 判定粒度是**整轮**：只要本轮有一个工具需要人工确认，
         * 整轮都不执行（包括只读工具）。
         * 否则会出现"读执行了、写暂停了"的半轮，
         * 恢复时要么重复读，要么模型基于半截结果继续推理。
         */
        if (approvalToolPolicy != null) {
            /*
             * 先确认"这个 Agent 真正能执行的每个工具，审批元数据都是确定的"。
             *
             * 扫描漏掉一个工具时，它的 requiresApproval 会是 false，
             * 于是被当成普通工具执行——这是把审批承诺悄悄作废。
             * 这里失败关闭：抛异常让任务失败，绝不放行。
             */
            try {
                approvalToolPolicy.verifyExposedTools(
                        getAgentType() + "/" + getName(), exposedToolNames());
            } catch (IllegalStateException e) {
                ctx.setErrorCode("APPROVAL_POLICY_INCOMPLETE");
                throw e;
            }

            List<AssistantMessage.ToolCall> requested =
                    response.getResult().getOutput().getToolCalls();

            if (approvalToolPolicy.anyRequiresApproval(requested)) {
                return pauseForApproval(ctx, response, requested);
            }

            /*
             * 审批被关闭但模型仍然调到了写工具：
             * 失败关闭，绝不"因为审批没开就直接执行"。
             * 这条路径在批次 1 不会触发（写工具不在白名单），
             * 但配置一改就可能打开，必须提前挡住。
             */
            if (approvalToolPolicy.hitsApprovalToolWhenDisabled(requested)) {
                ctx.setErrorCode("APPROVAL_DISABLED");
                throw new IllegalStateException(
                        "审批功能未启用，拒绝执行需要人工确认的写操作");
            }
        }

        /*
         * 与 think() 使用同一份选项构造逻辑，
         * 保证两处永远一致（包括关闭内部工具执行开关）。
         */
        ToolCallingChatOptions options = buildToolCallingOptions();



        /*
         * 此时 ctx.messages 中仍是执行工具前的历史。
         *
         * ToolCallingManager 会将本轮的 Assistant ToolCall Message
         * 和 ToolResponseMessage 加入新的 conversationHistory。
         */
        Prompt prompt = new Prompt(
                ctx.getMessages(),
                options
        );

        /*
         * tool_start：工具调用前发送。
         * 只携带脱敏后的用户可读消息，
         * 不发送工具参数，不发送原始结果。
         */
        response.getResult().getOutput().getToolCalls().forEach(toolCall ->
                sendEvent(
                        ctx,
                        AgentEventType.TOOL_START,
                        toolEventMessageProvider.startMessage(toolCall.name())
                )
        );

        /*
         * 真正执行模型请求的 Java 工具。
         *
         * 执行前把**本次任务**的上下文与站点级耗尽计数器绑定到当前线程，
         * 执行后立刻恢复：
         * 1. 工具回调（RetryableToolCallback / MusicQueryTool）是 Spring 单例，
         *    不能持有任务状态，只能通过线程绑定拿到当前任务的 ctx；
         *    - 站点熔断读 ToolTargetExhaustionTracker；
         *    - 音乐类工具读 AgentRunContextHolder 取 userId
         *      （userId 绝不作为工具参数，见 docs/music-agent.md）；
         * 2. 只在 executeToolCalls 期间绑定——工具执行是同步的，
         *    不存在跨线程读不到的问题；
         * 3. finally 恢复是硬要求：agent 线程来自线程池，
         *    不恢复会让下一个任务继承上一个任务的上下文（越权风险）。
         */
        AgentRunContext previousRunContext = AgentRunContextHolder.bind(ctx);
        ToolTargetExhaustionTracker previousTracker =
                ToolTargetExhaustionTracker.current();
        ToolTargetExhaustionTracker.bind(ctx.getToolTargetExhaustionTracker());

        /*
         * 幂等身份计划：ToolCallback 拿不到 toolCallId，
         * 只能在这里把整轮 ToolCall（含 id）登记下来，回调执行时按顺序认领。
         * 写工具身份不可判定时计划被标记为拒绝，该轮写工具不会执行。
         */
        ToolCallPlan previousPlan = ToolCallPlanHolder.bind(
                ToolCallPlan.build(
                        response.getResult().getOutput().getToolCalls(),
                        idempotentToolNames()
                )
        );

        ToolExecutionResult executionResult;
        try {
            executionResult = toolCallingManager.executeToolCalls(
                    prompt,
                    response
            );
        } finally {
            // 恢复而不是无脑清除：嵌套执行时不能把外层绑定弄丢。
            ToolCallPlanHolder.restore(previousPlan);
            ToolTargetExhaustionTracker.bind(previousTracker);
            AgentRunContextHolder.restore(previousRunContext);
        }

        /*
         * 使用框架整理好的完整历史覆盖旧历史。
         *
         * 不能直接 addAll，
         * 否则旧消息会重复。
         */
        ctx.getMessages().clear();

        ctx.getMessages().addAll(
                executionResult.conversationHistory()
        );



        /*
         * 工具执行后，最后一条消息通常是 ToolResponseMessage。
         */
        Message lastMessage = ctx.getMessages().get(
                ctx.getMessages().size() - 1
        );


        if (!(lastMessage instanceof ToolResponseMessage toolResponseMessage)) {
            throw new IllegalStateException(
                    "工具执行完成后，最后一条消息不是 ToolResponseMessage"
            );
        }

        /*
         * tool_result：工具调用完成后发送。
         * 消息由 ToolEventMessageProvider 基于原始结果推导（如条数），
         * 绝不把完整的工具原始结果推送给前端。
         */
        toolResponseMessage.getResponses().forEach(responseItem ->
                sendEvent(
                        ctx,
                        AgentEventType.TOOL_RESULT,
                        toolEventMessageProvider.resultMessage(
                                responseItem.name(),
                                responseItem.responseData()
                        )
                )
        );

        /*
         * 增加本轮实际执行的工具数量。
         */
        ctx.addToolCallCount(
                toolResponseMessage.getResponses().size()
        );

        toolResponseMessage.getResponses().forEach(responseItem -> {
            /*
             * 只记录工具名，按实际执行顺序追加；
             * 不记录 toolCall.arguments，避免参数原文进入评测数据。
             */
            ctx.recordToolName(responseItem.name());

            log.info(
                    "工具实际执行完成：runId={}, toolName={}, result={}",
                    ctx.getRunId(),
                    responseItem.name(),
                    abbreviateForLog(responseItem.responseData())
            );

            /*
             * 完整结果只在 DEBUG 输出。
             * 工具结果动辄上千字符（网页正文、搜索结果列表），
             * 按 INFO 全量打印会让日志体积按 token 量级膨胀。
             */
            log.debug(
                    "工具实际执行完成（完整结果）：runId={}, toolName={}, result={}",
                    ctx.getRunId(),
                    responseItem.name(),
                    responseItem.responseData()
            );
        });

        /*
         * 判断模型是否调用了终止工具。
         *
         * terminate_task 必须与你 TerminateTool 的 @Tool(name = ...)
         * 完全一致。
         */
        boolean terminated = toolResponseMessage.getResponses()
                .stream()
                .anyMatch(responseItem ->
                        TERMINATE_TOOL_NAME.equals(responseItem.name())
                );

        if (terminated) {
            ctx.transitionTo(AgentState.SUCCEEDED);

            /*
             * 终止时的最终回答：三级优先，见 resolveTerminateFinalAnswer。
             *
             * 这里是**唯一**的写入点：SSE 的 final_answer 事件、
             * agent_run.final_answer 字段、run() 返回值都读 ctx.getFinalAnswer()，
             * 所以只要这里对，三处天然一致。
             */
            ctx.setFinalAnswer(
                    resolveTerminateFinalAnswer(ctx, response, toolResponseMessage)
            );
        }

        /*
         * 当前这一轮 ToolCall 已处理完成。
         */
        ctx.setPendingResponse(null);

        /*
         * 返回本轮工具执行摘要（脱敏文案）。
         *
         * 该摘要会进入 step 事件的 message，
         * 因此不包含完整原始结果、路径等敏感信息。
         */
        return toolResponseMessage.getResponses()
                .stream()
                .map(responseItem -> toolEventMessageProvider.resultMessage(
                        responseItem.name(),
                        responseItem.responseData()
                ))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 拒绝回填的工具结果文本。
     *
     * 只说明"这一步没做"，不解释原因、不带参数、不带内部信息：
     * 模型据此改口即可，用户看到的是它随后的自然语言回答。
     */
    static final String REJECTION_RESULT_TEXT = "用户拒绝执行该操作，未做任何修改。";

    // ===== 审批恢复（M3 批次 2） =====

    /**
     * 当前 Agent 的配置指纹（恢复前与检查点比对）。
     *
     * 与暂停时用的是同一个算法，否则"自己跟自己比"必然不相等。
     */
    public String currentConfigVersion() {
        return AgentConfigFingerprint.compute(
                getAgentType(),
                getSystemPrompt(),
                availableTools,
                approvalToolPolicy == null ? Set.of() : approvalToolPolicy.requiredToolNames()
        );
    }

    /**
     * 当前 Agent 的白名单哈希（恢复前与检查点比对）。
     */
    public String currentWhitelistHash() {
        return AgentConfigFingerprint.whitelistHash(availableTools);
    }

    /**
     * 恢复钩子：进入循环前补做"暂停那一轮"。
     *
     * 批准 → 执行这一轮工具；拒绝 → 回填拒绝回执。
     * 两条路径都**不重复 nextStep**：暂停那一轮已经计过步。
     */
    @Override
    protected void afterResume(AgentRunContext ctx) {
        ApprovalCheckpoint checkpoint = ctx.getCheckpoint();
        if (checkpoint == null) {
            return;
        }

        if (ApprovalRecord.STATUS_REJECTED.equals(ctx.getResumeMode())) {
            backfillRejection(ctx, checkpoint);
            return;
        }

        executeApprovedRound(ctx, checkpoint);
    }

    /**
     * 批准：执行暂停时被冻结的那一整轮工具。
     *
     * 执行前检查两件事（缺一不可）：
     * 1. 剩余执行时间 > 0 —— 否则恢复即超时，发模型调用没有意义；
     * 2. 剩余工具额度 ≥ 本轮调用数 —— 不足就整轮不执行，绝不"执行一半"。
     */
    private void executeApprovedRound(AgentRunContext ctx, ApprovalCheckpoint checkpoint) {
        List<AssistantMessage.ToolCall> calls = toToolCalls(checkpoint);

        if (ctx.elapsed().compareTo(ctx.effectiveTimeout(getTimeout())) >= 0) {
            ctx.setErrorCode("BUDGET_EXHAUSTED_ON_RESUME");
            ctx.transitionTo(AgentState.TIMED_OUT);
            log.warn("恢复即超时，跳过暂停轮：runId={}, elapsed={}ms",
                    ctx.getRunId(), ctx.getActiveElapsedMillis());
            return;
        }

        int remainingTools = getMaxToolCalls() - ctx.getToolCallCount();
        if (remainingTools < calls.size()) {
            /*
             * 额度不足时整轮放弃：执行一半会让模型看到"部分工具成功"的
             * 假象，而另一半永远没执行，且没有任何记录说明为什么。
             */
            ctx.setErrorCode("TOOL_BUDGET_INSUFFICIENT");
            ctx.transitionTo(AgentState.MAX_STEPS_REACHED);
            log.warn("恢复时工具额度不足，整轮不执行：runId={}, remaining={}, need={}",
                    ctx.getRunId(), remainingTools, calls.size());
            return;
        }

        AssistantMessage pendingAssistant = (AssistantMessage) CheckpointCodec.toMessage(
                checkpoint.pendingAssistant());

        ChatResponse response = new ChatResponse(
                List.of(new Generation(pendingAssistant)));

        Prompt prompt = new Prompt(ctx.getMessages(), buildToolCallingOptions());

        calls.forEach(call -> sendEvent(
                ctx,
                AgentEventType.TOOL_START,
                toolEventMessageProvider.startMessage(call.name())
        ));

        ToolExecutionResult executionResult;
        AgentRunContext previousRunContext = AgentRunContextHolder.bind(ctx);
        ToolTargetExhaustionTracker previousTracker = ToolTargetExhaustionTracker.current();
        ToolTargetExhaustionTracker.bind(ctx.getToolTargetExhaustionTracker());

        /*
         * 恢复路径同样要绑定身份计划：检查点里的 toolCallId 原样重建，
         * 因此同一逻辑调用在恢复前后算出同一个幂等键。
         */
        ToolCallPlan previousPlan = ToolCallPlanHolder.bind(
                ToolCallPlan.build(calls, idempotentToolNames())
        );

        try {
            /*
             * 关键：pendingAssistant 只通过 ChatResponse 交给框架追加一次。
             * 预先 add 进 messages 会让 assistant 出现两次、toolCallId 对不上。
             */
            executionResult = toolCallingManager.executeToolCalls(prompt, response);
        } finally {
            ToolCallPlanHolder.restore(previousPlan);
            ToolTargetExhaustionTracker.bind(previousTracker);
            AgentRunContextHolder.restore(previousRunContext);
        }

        ctx.getMessages().clear();
        ctx.getMessages().addAll(executionResult.conversationHistory());

        Message lastMessage = ctx.getMessages().get(ctx.getMessages().size() - 1);
        if (!(lastMessage instanceof ToolResponseMessage toolResponseMessage)) {
            throw new IllegalStateException(
                    "恢复执行后，最后一条消息不是 ToolResponseMessage");
        }

        toolResponseMessage.getResponses().forEach(item -> {
            sendEvent(ctx, AgentEventType.TOOL_RESULT,
                    toolEventMessageProvider.resultMessage(item.name(), item.responseData()));
            ctx.recordToolName(item.name());
            log.info("恢复轮工具执行完成：runId={}, toolName={}, result={}",
                    ctx.getRunId(), item.name(), abbreviateForLog(item.responseData()));
        });

        ctx.addToolCallCount(toolResponseMessage.getResponses().size());

        log.info("暂停轮已执行完成：runId={}, tools={}, step={}",
                ctx.getRunId(), toolResponseMessage.getResponses().size(),
                ctx.getCurrentStep());
    }

    /**
     * 拒绝：为每个 pending toolCallId 回填一条匹配的拒绝结果。
     *
     * 不执行任何工具、不增加 toolCallCount、不要求工具额度——
     * 拒绝路径只是把"这一步没做"如实告诉模型，让它改口。
     *
     * 配对规则与执行路径一致：assistant(toolCalls) 一次，
     * 每个 id 一条 ToolResponse，数量与顺序完全对应。
     */
    private void backfillRejection(AgentRunContext ctx, ApprovalCheckpoint checkpoint) {
        AssistantMessage pendingAssistant = (AssistantMessage) CheckpointCodec.toMessage(
                checkpoint.pendingAssistant());

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : pendingAssistant.getToolCalls()) {
            responses.add(new ToolResponseMessage.ToolResponse(
                    call.id(), call.name(), REJECTION_RESULT_TEXT));
        }

        ctx.getMessages().add(pendingAssistant);
        ctx.getMessages().add(new ToolResponseMessage(responses));

        log.info("拒绝回执已回填：runId={}, step={}, calls={}",
                ctx.getRunId(), ctx.getCurrentStep(), responses.size());
    }

    /**
     * 需要幂等身份的工具名：被 {@link IdempotentToolCallback} 装饰过的那些。
     *
     * 从实际装配的工具里推导，而不是维护一份名单——
     * 名单会和装配分家，分家后要么漏保护、要么把只读工具也拖进身份校验。
     */
    private Set<String> idempotentToolNames() {
        if (availableTools == null || availableTools.length == 0) {
            return Set.of();
        }

        Set<String> names = new HashSet<>();
        for (ToolCallback callback : availableTools) {
            if (callback instanceof IdempotentToolCallback) {
                names.add(callback.getToolDefinition().name());
            }
        }
        return names;
    }

    private List<AssistantMessage.ToolCall> toToolCalls(ApprovalCheckpoint checkpoint) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        checkpoint.pendingToolCalls().forEach(call -> calls.add(
                new AssistantMessage.ToolCall(
                        call.id(), call.type(), call.name(), call.arguments())));
        return calls;
    }

}
