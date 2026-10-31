package com.example.mkagent.agent;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.exception.ModelCallException;
import com.example.mkagent.model.AgentEventType;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.resilience.ToolTargetExhaustionTracker;
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
import java.util.List;
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
     * 预算耗尽后强制收尾回答的前缀。
     *
     * 必须让用户一眼看出这是"尽力而为"，而不是模型正常完成任务给出的结论。
     */
    public static final String FORCED_FINAL_ANSWER_PREFIX =
            "(因步数预算耗尽，以下为基于已获取信息的尽力回答)\n";

    /**
     * 预算耗尽后强制收尾时追加给模型的指令。
     *
     * 明确要求"不调用工具"：这次调用本身就没给工具，
     * 但模型仍可能在文本里请求工具，写清楚可以少一次无效轮次。
     */
    static final String FORCED_FINAL_ANSWER_INSTRUCTION =
            "步数预算已经耗尽，无法再调用任何工具。"
                    + "请只依据上面的对话历史和已经拿到的工具结果，"
                    + "直接给出一个尽力而为的最终回答："
                    + "用自然语言写结论；"
                    + "没有获取到的信息要明确说明，不要编造；"
                    + "不要再请求调用工具。";

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
     * 三条硬约束：
     * 1. **不改终态**：仍然是 MAX_STEPS_REACHED（这是成本事实，不能伪装成 SUCCEEDED）；
     * 2. **不带工具**：options 里没有 toolCallbacks，模型没有工具可用；
     * 3. **失败必须降级**：任何异常都只记 WARN 并保持 finalAnswer 为空，
     *    由 BaseAgent 回退到原有的步骤摘要。
     */
    @Override
    protected void onBudgetExhausted(AgentRunContext ctx) {
        if (!forceFinalAnswer) {
            log.info(
                    "预算耗尽且无最终回答，force-final-answer=false，保持旧行为（步骤摘要）：runId={}",
                    ctx.getRunId()
            );
            return;
        }

        String answer = requestForcedFinalAnswer(ctx);
        if (answer == null || answer.isBlank()) {
            log.warn(
                    "预算耗尽后的收尾调用没有拿到有效回答，回退到步骤摘要：runId={}",
                    ctx.getRunId()
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
     * 执行一次"不带工具"的模型调用，用于预算耗尽后的收尾。
     *
     * 该调用的 Token 通过 {@link #recordUsage} 计入本任务 Usage——
     * 它确实花了钱，必须出现在成本统计里，不能当成免费操作。
     *
     * @return 模型给出的文本；失败或空文本时返回 null
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
         * 关键：不传 toolCallbacks。
         * 模型因此无法再请求工具，只能输出文本。
         */
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        long modelCallStart = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            long modelCallCost = System.currentTimeMillis() - modelCallStart;

            // 计入 Usage：Token 与耗时都要算进这次任务。
            recordUsage(ctx, response, modelCallCost);

            if (response == null
                    || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return null;
            }
            return response.getResult().getOutput().getText();
        } catch (RuntimeException e) {
            /*
             * 收尾失败不是任务失败：任务早就因为预算耗尽结束了，
             * 这里只影响"有没有答案"，所以只记 WARN 并降级。
             */
            log.warn(
                    "预算耗尽后的收尾模型调用失败，回退到步骤摘要：runId={}, reason={}",
                    ctx.getRunId(), e.getMessage()
            );
            return null;
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
     * Act：执行模型请求的工具。
     */
    @Override
    protected String act(AgentRunContext ctx) {
        ChatResponse response = ctx.getPendingResponse();

        if (response == null || !response.hasToolCalls()) {
            return "本轮没有需要执行的工具。";
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

        ToolExecutionResult executionResult;
        try {
            executionResult = toolCallingManager.executeToolCalls(
                    prompt,
                    response
            );
        } finally {
            // 恢复而不是无脑清除：嵌套执行时不能把外层绑定弄丢。
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
}