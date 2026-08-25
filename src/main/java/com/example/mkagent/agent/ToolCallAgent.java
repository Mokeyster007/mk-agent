package com.example.mkagent.agent;

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
import org.springframework.ai.tool.ToolCallback;

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
     * Think：调用模型，让模型决定下一步。
     */
    @Override
    protected boolean think(AgentRunContext ctx) {
        /*
         * 本轮模型可用工具。
         *
         * 手动调用 ChatModel 时，工具要通过
         * ToolCallingChatOptions 传入。
         */
        ToolCallingChatOptions options =
                ToolCallingChatOptions.builder()
                        .toolCallbacks(availableTools)
                        .build();

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
         */
        long modelCallStart = System.currentTimeMillis();
        ChatResponse response = chatModel.call(prompt);
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

        ToolCallingChatOptions options =
                ToolCallingChatOptions.builder()
                        .toolCallbacks(availableTools)
                        .build();



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

        toolResponseMessage.getResponses().forEach(responseItem ->
                log.info(
                        "工具实际执行完成：runId={}, toolName={}, result={}",
                        ctx.getRunId(),
                        responseItem.name(),
                        responseItem.responseData()
                )
        );

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