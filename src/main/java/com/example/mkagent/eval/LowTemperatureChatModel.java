package com.example.mkagent.eval;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 评测评委专用的低温度 ChatModel 包装。
 *
 * 为什么需要单独包装：
 * 1. LLM-as-judge 必须与业务 Agent 解耦，避免共用带工具、
 *    带业务系统提示词的调用链；
 * 2. 评委需要低温度（默认 0.0）以保证同一份轨迹的判定尽量稳定；
 * 3. 这里只覆盖 temperature，其余模型配置沿用底层 ChatModel。
 *
 * 注意：该类不是 Spring Bean，由 AgentEvaluator 内部创建，
 * 避免在容器中新增第二个 ChatModel 导致现有按类型注入产生歧义。
 */
public class LowTemperatureChatModel implements ChatModel {

    private final ChatModel delegate;

    private final double temperature;

    public LowTemperatureChatModel(ChatModel delegate, double temperature) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate ChatModel 不能为空");
        }
        this.delegate = delegate;
        this.temperature = temperature;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatOptions options = ChatOptions.builder()
                .temperature(temperature)
                .build();

        /*
         * 只替换本次调用的 options：
         * 不携带工具，不继承 Agent 的 ToolCallingChatOptions。
         */
        return delegate.call(
                new Prompt(prompt.getInstructions(), options)
        );
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
