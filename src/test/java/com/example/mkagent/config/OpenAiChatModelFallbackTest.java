package com.example.mkagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回退语义：只回退"模型名"，不回退"实例"。
 *
 * auxiliary / vision 没配 name 时复用 agent 的模型名，
 * 但仍然是独立客户端、独立 RetryTemplate。
 * 如果实现成"直接复用 agent 那个对象"，一旦某个库原地改写模板，
 * 三个用途会一起被打穿——这正是要避免的。
 */
@Timeout(60)
class OpenAiChatModelFallbackTest {

    private ApplicationContextRunner runner(String... extraProperties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        OpenAiCompatibleChatModelConfig.class,
                        ModelRetryConfig.class,
                        ModelRetryPropertiesConfig.class
                )
                .withConfiguration(AutoConfigurations.of(
                        SpringAiRetryAutoConfiguration.class
                ))
                .withBean(ToolCallingManager.class,
                        () -> ToolCallingManager.builder().build())
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .withPropertyValues(extraProperties);
    }

    @Test
    void auxiliaryAndVisionFallBackToAgentNameButStayIndependent() {
        runner(
                "mkagent.model.base-url=http://localhost:9999/v1",
                "mkagent.model.api-key=test-api-key",
                "mkagent.model.agent.name=agent-model"
        ).run(context -> {
            ChatModel agent = context.getBean("dashscopeChatModel", ChatModel.class);
            ChatModel auxiliary = context.getBean("auxiliaryChatModel", ChatModel.class);
            ChatModel vision = context.getBean("visionChatModel", ChatModel.class);

            assertThat(modelName(agent))
                    .as("agent 使用自己配置的模型名")
                    .isEqualTo("agent-model");
            assertThat(modelName(auxiliary))
                    .as("auxiliary 未配置 name，应回退到 agent 的模型名")
                    .isEqualTo("agent-model");
            assertThat(modelName(vision))
                    .as("vision 未配置 name，应回退到 agent 的模型名")
                    .isEqualTo("agent-model");

            // 名字相同，但对象必须不同。
            assertThat(auxiliary).isNotSameAs(agent);
            assertThat(vision).isNotSameAs(agent);

            assertThat(readRetryTemplate(auxiliary))
                    .as("回退的 auxiliary 仍必须持有自己的 RetryTemplate")
                    .isNotSameAs(readRetryTemplate(agent));
            assertThat(readRetryTemplate(vision))
                    .as("回退的 vision 仍必须持有自己的 RetryTemplate")
                    .isNotSameAs(readRetryTemplate(agent));
        });
    }

    @Test
    void configuredNamesOverrideFallback() {
        runner(
                "mkagent.model.base-url=http://localhost:9999/v1",
                "mkagent.model.api-key=test-api-key",
                "mkagent.model.agent.name=agent-model",
                "mkagent.model.auxiliary.name=auxiliary-model",
                "mkagent.model.vision.name=vision-model"
        ).run(context -> {
            assertThat(modelName(context.getBean("auxiliaryChatModel", ChatModel.class)))
                    .isEqualTo("auxiliary-model");
            assertThat(modelName(context.getBean("visionChatModel", ChatModel.class)))
                    .isEqualTo("vision-model");
        });
    }

    @Test
    void missingAgentNameFailsFastWithClearMessage() {
        runner(
                "mkagent.model.base-url=http://localhost:9999/v1",
                "mkagent.model.api-key=test-api-key"
        ).run(context -> {
            assertThat(context)
                    .as("没有任何模型名时必须在启动阶段失败，而不是等到第一次调用")
                    .hasFailed();

            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("无法确定模型名");
        });
    }

    private static String modelName(ChatModel model) {
        assertThat(model).isInstanceOf(OpenAiChatModel.class);
        return ((OpenAiChatModel) model).getDefaultOptions().getModel();
    }

    private static RetryTemplate readRetryTemplate(ChatModel model) {
        Class<?> type = model.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("retryTemplate");
                field.setAccessible(true);
                Object value = field.get(model);
                return value instanceof RetryTemplate template ? template : null;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    @Configuration
    @EnableConfigurationProperties(ModelRetryProperties.class)
    static class ModelRetryPropertiesConfig {
    }
}
