package com.example.mkagent.agent;

import com.example.mkagent.exception.ModelCallException;
import com.example.mkagent.model.AgentEventType;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
         * 1. ctx.messages：系统提示、用户消息、历史 ToolCall、工具结果
         * 2. options：当前可用工具定义
         */
        Prompt prompt = new Prompt(
                ctx.getMessages(),
                options
        );


        /**
         * 打印所有可用工具。
         */
        for (ToolCallback tool : availableTools) {
            log.info(
                    "当前提供给模型的工具：name={}, description={}",
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
            String model = metadata.getModel();

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
         */
        ToolExecutionResult executionResult =
                toolCallingManager.executeToolCalls(
                        prompt,
                        response
                );

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
                        "terminate_task".equals(responseItem.name())
                );

        if (terminated) {
            ctx.transitionTo(AgentState.SUCCEEDED);

            /*
             * 若终止工具结束任务，但模型没有提前写 finalAnswer，
             * 用工具执行摘要作为兜底最终结果。
             */
            if (ctx.getFinalAnswer() == null
                    || ctx.getFinalAnswer().isBlank()) {

                String endSummary = toolResponseMessage.getResponses()
                        .stream()
                        .map(responseItem -> String.format(
                                "任务结束：%s",
                                toolEventMessageProvider.resultMessage(
                                        responseItem.name(),
                                        responseItem.responseData()
                                )
                        ))
                        .collect(Collectors.joining("\n"));

                ctx.setFinalAnswer(endSummary);
            }
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