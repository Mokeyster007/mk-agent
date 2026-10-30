package com.example.mkagent.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.lang.reflect.Method;

/**
 * 从 ChatModel Bean 反查"当前配置的模型名"。
 *
 * 为什么需要它：
 * 真实运行时（model=qwen-plus）日志里 `model=` 一直是空的，
 * 落库的 agent_run.model 也是空。原因是模型响应的
 * ChatResponseMetadata#getModel() 在部分实现/部分场景下返回 null 或空串，
 * 而 recordUsage 直接把它透传下去了。
 *
 * 为什么用反射：
 * Spring AI 1.0.0 的 ChatModel 接口**没有** getDefaultOptions()，
 * 但 DashScopeChatModel、OpenAiChatModel 以及本项目的
 * LowTemperatureChatModel 包装器都各自公开了这个方法。
 * 没有统一接口可依赖，只能按方法名反射；找不到就返回 null，
 * 由调用方退到兜底值，绝不影响主流程。
 *
 * 为什么不从配置项读：
 * 本项目有两条模型配置路径（spring.ai.dashscope.chat.options.model
 * 与 mkagent.model.agent.name），写死任何一条都会在切换后失效；
 * 从**实际持有的 Bean** 反查，天然跟随当前生效的配置。
 */
public final class ConfiguredModelNameResolver {

    /**
     * 各类 ChatModel 实现读取默认选项的方法名。
     */
    private static final String DEFAULT_OPTIONS_GETTER = "getDefaultOptions";

    private ConfiguredModelNameResolver() {
    }

    /**
     * 解析 ChatModel 上配置的模型名。
     *
     * @param chatModel 目标模型，可为 null
     * @return 模型名；无法解析时返回 null
     */
    public static String resolve(ChatModel chatModel) {
        if (chatModel == null) {
            return null;
        }

        try {
            Method getter = chatModel.getClass()
                    .getMethod(DEFAULT_OPTIONS_GETTER);

            Object options = getter.invoke(chatModel);

            if (options instanceof ChatOptions chatOptions) {
                String model = chatOptions.getModel();
                if (model != null && !model.isBlank()) {
                    return model.trim();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            /*
             * 没有 getDefaultOptions、方法调用抛异常、
             * 甚至 getClass() 被安全策略拦下——一律视为"解析不到"。
             * 这只是观测数据的兜底，绝不能让它影响模型调用。
             */
        }

        return null;
    }
}
