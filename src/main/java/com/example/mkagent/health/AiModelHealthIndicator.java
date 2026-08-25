package com.example.mkagent.health;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 大模型配置轻量级健康检查。
 *
 * 设计约束（重要）：
 * 健康检查绝不发起真实模型请求——
 * 一次真实调用会产生 Token 费用、依赖外网、
 * 还可能拖慢健康探针导致探针超时。
 *
 * 因此本指示器只做"配置可用性"静态检查：
 * 1. DashScope API Key 是否已配置（缺失 → DOWN）；
 * 2. ChatModel Bean 是否装配成功（缺失 → DOWN）；
 * 3. 配置的模型名称是否正常（缺失只告警，不 DOWN）。
 *
 * Key 脱敏：只回显前 6 位 + ***，完整 Key 永不出现在响应里。
 */
@Component("aiModel")
public class AiModelHealthIndicator implements HealthIndicator {

    private final String apiKey;

    private final String model;

    private final ObjectProvider<ChatModel> chatModelProvider;

    public AiModelHealthIndicator(
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${spring.ai.dashscope.chat.options.model:}") String model,
            ObjectProvider<ChatModel> chatModelProvider
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public Health health() {
        if (apiKey == null || apiKey.isBlank()) {
            return Health.down()
                    .withDetail("reason", "未配置 spring.ai.dashscope.api-key")
                    .build();
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return Health.down()
                    .withDetail("reason", "ChatModel Bean 未装配（自动配置被排除或初始化失败）")
                    .build();
        }

        Health.Builder builder = Health.up()
                .withDetail("apiKey", maskKey(apiKey))
                .withDetail("chatModel", chatModel.getClass().getSimpleName());

        if (model == null || model.isBlank()) {
            builder.withDetail("modelWarning", "未显式配置模型名称，将使用 SDK 默认值");
        } else {
            builder.withDetail("model", model);
        }

        return builder.build();
    }

    /**
     * Key 脱敏：只保留前 6 位，防止健康端点泄露凭证。
     */
    private String maskKey(String key) {
        if (key.length() <= 6) {
            return "***";
        }
        return key.substring(0, 6) + "***";
    }
}
