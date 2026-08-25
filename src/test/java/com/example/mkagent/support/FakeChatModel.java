package com.example.mkagent.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 测试专用可控 ChatModel（Fake / Stub）。
 *
 * 替代真实 DashScope 模型，保证测试完全稳定，不依赖真实大模型：
 *
 * 第一轮（上下文中没有 ToolResponseMessage）：
 *   返回一个工具调用请求：demo_inventory_check(sku=MK-2026-001)。
 *
 * 第二轮（工具结果已由 ToolCallingManager 写回上下文）：
 *   读取真实的 ToolResponseMessage 内容，只有当工具确实返回
 *   “库存数量：17 / 可发货”时才输出固定最终回答；
 *   否则输出“工具结果异常”，用于快速暴露链路问题。
 *
 * 只允许每个会话被调用 2 轮，超过即抛异常，防止 Agent 循环失控后测试无限等待。
 *
 * 会话边界识别：
 * 上下文中没有 ToolResponseMessage 即为一次新会话的第一轮，
 * 此时重置轮数计数。这样同一个 FakeChatModel 实例
 * 可以支撑同一测试上下文内的多次独立请求（多个测试方法）。
 */
public class FakeChatModel implements ChatModel {

    /** 期望的最终回答（必须包含 SKU、17、可发货）。 */
    public static final String FINAL_ANSWER =
            "SKU MK-2026-001 当前库存数量为 17，状态为可发货。";

    private final String toolName;

    private final String sku;

    /** 模型被调用的次数（应恰好为 2：工具调用轮 + 最终回答轮）。 */
    private final AtomicInteger callCount = new AtomicInteger(0);

    /** 第二轮是否真的读取到了工具结果（证明结果写回上下文）。 */
    private final AtomicBoolean sawToolResult = new AtomicBoolean(false);

    /** 每次模型调用所在的线程名，用于验证后台线程执行。 */
    private final List<String> threadNames = new CopyOnWriteArrayList<>();

    /**
     * 第二轮（最终回答轮）开始前的等待闸门（可选）。
     *
     * 设置后：工具已执行完毕、任务仍处 RUNNING，
     * 模型在返回最终回答前阻塞等待 countDown，
     * 供持久化/取消类测试拿到稳定的"运行中"窗口。
     */
    private volatile CountDownLatch pauseBeforeFinalAnswer;

    /**
     * 失败模式（可选）：第一轮直接抛异常，
     * 用于验证 FAILED 终态持久化。
     */
    private volatile boolean failMode;

    public FakeChatModel() {
        this("demo_inventory_check", "MK-2026-001");
    }

    public FakeChatModel(String toolName, String sku) {
        this.toolName = toolName;
        this.sku = sku;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        threadNames.add(Thread.currentThread().getName());

        if (failMode) {
            throw new IllegalStateException("FakeChatModel 模拟模型调用失败");
        }

        List<Message> messages = prompt.getInstructions();
        boolean hasToolResult = messages.stream()
                .anyMatch(message -> message instanceof ToolResponseMessage);

        /*
         * 会话边界：第一轮（无 ToolResponseMessage）重置轮数计数，
         * 支持同一实例被多个测试方法 / 多次请求复用。
         */
        if (!hasToolResult) {
            callCount.set(0);
        }

        int round = callCount.incrementAndGet();
        if (round > 2) {
            throw new IllegalStateException(
                    "FakeChatModel 被意外调用超过 2 轮：round=" + round
                            + "，Agent 循环可能失控"
            );
        }

        if (!hasToolResult) {
            /*
             * 第一轮：模型决定调用库存查询工具。
             *
             * AssistantMessage.ToolCall(id, type, name, arguments)
             * 与 Spring AI 1.0.0 的 record 签名保持一致。
             */
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "call_1",
                    "function",
                    toolName,
                    "{\"sku\":\"" + sku + "\"}"
            );
            AssistantMessage assistantMessage = new AssistantMessage(
                    "",
                    Map.of(),
                    List.of(toolCall)
            );
            return buildResponse(assistantMessage);
        }

        /*
         * 第二轮：工具结果已经通过 ToolCallingManager 写回 ctx.messages。
         * 基于真实工具返回的数据生成最终回答，而不是凭空编造。
         */
        sawToolResult.set(true);

        /*
         * 可选闸门：返回最终回答前阻塞，让任务停留在 RUNNING，
         * 供测试断言运行中状态 / 执行取消。被中断时按取消语义抛出。
         */
        CountDownLatch latch = pauseBeforeFinalAnswer;
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        new InterruptedException("模型调用被取消中断")
                );
            }
        }

        String toolResultData = messages.stream()
                .filter(message -> message instanceof ToolResponseMessage)
                .map(message -> ((ToolResponseMessage) message).getResponses())
                .flatMap(List::stream)
                .map(ToolResponseMessage.ToolResponse::responseData)
                .collect(Collectors.joining(" | "));

        String answer;
        if (toolResultData.contains("库存数量：17")
                && toolResultData.contains("可发货")) {
            answer = FINAL_ANSWER;
        } else {
            answer = "工具结果异常，无法确认库存：" + toolResultData;
        }

        return buildResponse(new AssistantMessage(answer));
    }

    /**
     * 构造携带 Usage 元数据的响应，模拟真实模型行为：
     * 每轮固定消耗 promptTokens=10 / completionTokens=5，
     * 模型名 fake-model，供 Usage 采集与持久化测试断言。
     */
    private ChatResponse buildResponse(AssistantMessage assistantMessage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("fake-model")
                .usage(new FakeUsage(10, 5))
                .build();

        return new ChatResponse(
                List.of(new Generation(assistantMessage)),
                metadata
        );
    }

    /**
     * 最小 Usage 实现：适配 Spring AI 1.0.0 接口
     * （Integer 返回值 + getNativeUsage）。
     */
    private static final class FakeUsage implements Usage {

        private final Integer promptTokens;

        private final Integer completionTokens;

        private FakeUsage(int promptTokens, int completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            // 测试桩没有原生 usage 对象。
            return null;
        }
    }

    public int getCallCount() {
        return callCount.get();
    }

    public boolean isSawToolResult() {
        return sawToolResult.get();
    }

    public List<String> getThreadNames() {
        return List.copyOf(threadNames);
    }

    /**
     * 设置第二轮前的等待闸门；传 null 解除。
     */
    public void setPauseBeforeFinalAnswer(CountDownLatch latch) {
        this.pauseBeforeFinalAnswer = latch;
    }

    /**
     * 开关失败模式：第一轮直接抛异常。
     */
    public void setFailMode(boolean failMode) {
        this.failMode = failMode;
    }

    /**
     * 清理可控开关，避免测试方法之间互相影响。
     */
    public void resetControls() {
        this.pauseBeforeFinalAnswer = null;
        this.failMode = false;
    }
}
