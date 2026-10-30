package com.example.mkagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeImageApi;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageModel;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageOptions;
import io.micrometer.observation.ObservationRegistry;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI 兼容客户端的装配与隔离性测试。
 *
 * 这里刻意**不**只断言配置值，而是断言**实例**：
 * 1. 三个用途的模型各自持有的 RetryTemplate 是三个不同对象；
 * 2. 没有一个模型持有容器里的 RetryTemplate Bean；
 * 3. 即使容器模板被别人原地改写（复刻 DashScopeImageModel 的行为），
 *    三个模型的策略也纹丝不动。
 *
 * 第 3 条是这次事故最直接的教训：配置打印正确 ≠ 运行时对象正确。
 * 一个可变的共享对象 + 第三方库"借用即改写"，能让启动日志完全失真。
 */
@Timeout(60)
class OpenAiChatModelWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
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
                    .withPropertyValues(
                            "mkagent.model.base-url=http://localhost:9999/v1",
                            "mkagent.model.api-key=test-api-key",
                            "mkagent.model.agent.name=agent-model",
                            "mkagent.model.agent.temperature=0.7",
                            "mkagent.model.auxiliary.name=auxiliary-model",
                            "mkagent.model.vision.name=vision-model"
                    );

    // ==================== 1. 三个模型 = 三份私有模板 ====================

    @Test
    void threeModelsHoldDistinctPrivateRetryTemplates() {
        contextRunner.run(context -> {
            ChatModel agent = context.getBean("dashscopeChatModel", ChatModel.class);
            ChatModel auxiliary = context.getBean("auxiliaryChatModel", ChatModel.class);
            ChatModel vision = context.getBean("visionChatModel", ChatModel.class);

            assertThat(agent).isInstanceOf(OpenAiChatModel.class);
            assertThat(auxiliary).isInstanceOf(OpenAiChatModel.class);
            assertThat(vision).isInstanceOf(OpenAiChatModel.class);

            RetryTemplate agentTemplate = readRetryTemplate(agent);
            RetryTemplate auxiliaryTemplate = readRetryTemplate(auxiliary);
            RetryTemplate visionTemplate = readRetryTemplate(vision);

            assertThat(agentTemplate).isNotNull();
            assertThat(auxiliaryTemplate).isNotNull();
            assertThat(visionTemplate).isNotNull();

            // 三个不同对象，而不是同一个对象的三个引用。
            assertThat(agentTemplate)
                    .as("agent 与 auxiliary 不能共享同一个 RetryTemplate 实例")
                    .isNotSameAs(auxiliaryTemplate);
            assertThat(agentTemplate)
                    .as("agent 与 vision 不能共享同一个 RetryTemplate 实例")
                    .isNotSameAs(visionTemplate);
            assertThat(auxiliaryTemplate)
                    .as("auxiliary 与 vision 不能共享同一个 RetryTemplate 实例")
                    .isNotSameAs(visionTemplate);

            // 策略仍然一致：都来自 mkagent.model-retry.*
            for (Map.Entry<String, RetryTemplate> entry : Map.of(
                    "agent", agentTemplate,
                    "auxiliary", auxiliaryTemplate,
                    "vision", visionTemplate
            ).entrySet()) {
                RetryTemplateSnapshot snapshot = RetryTemplateSnapshot.of(entry.getValue());
                assertThat(snapshot.getMaxAttempts())
                        .as("%s 的重试次数", entry.getKey())
                        .isEqualTo(3);
                assertThat(snapshot.isFixedBackOff())
                        .as("%s 不能用 FixedBackOffPolicy：%s", entry.getKey(), snapshot)
                        .isFalse();
                assertThat(snapshot.getRetryOn())
                        .as("%s 只应重试可自愈失败：%s", entry.getKey(), snapshot)
                        .contains("TransientAiException")
                        .doesNotContain("NonTransientAiException");
            }
        });
    }

    // ==================== 2. 谁都没用容器里的 Bean ====================

    @Test
    void noModelSharesTheContainerRetryTemplate() {
        contextRunner.run(context -> {
            Map<String, RetryTemplate> beans =
                    context.getBeansOfType(RetryTemplate.class);
            assertThat(beans).hasSize(1);

            RetryTemplate containerTemplate =
                    beans.values().iterator().next();

            for (String name : new String[]{
                    "dashscopeChatModel", "auxiliaryChatModel", "visionChatModel"}) {
                ChatModel model = context.getBean(name, ChatModel.class);
                assertThat(readRetryTemplate(model))
                        .as("%s 必须持有私有实例，而不是容器 Bean", name)
                        .isNotSameAs(containerTemplate);
            }
        });
    }

    // ==================== 3. 容器模板被改写也不影响模型 ====================

    @Test
    void poisoningContainerTemplateDoesNotAffectModels() {
        contextRunner.run(context -> {
            RetryTemplate containerTemplate =
                    context.getBean(RetryTemplate.class);

            ChatModel agent = context.getBean("dashscopeChatModel", ChatModel.class);
            ChatModel auxiliary = context.getBean("auxiliaryChatModel", ChatModel.class);
            ChatModel vision = context.getBean("visionChatModel", ChatModel.class);

            RetryTemplate agentTemplate = readRetryTemplate(agent);
            RetryTemplate auxiliaryTemplate = readRetryTemplate(auxiliary);
            RetryTemplate visionTemplate = readRetryTemplate(vision);

            // 复刻 DashScopeImageModel 的构造行为：拿到模板就地改写。
            poison(containerTemplate);

            assertThat(RetryTemplateSnapshot.of(containerTemplate).isFixedBackOff())
                    .as("前提：容器模板确实被改写了")
                    .isTrue();

            for (Map.Entry<String, RetryTemplate> entry : Map.of(
                    "agent", agentTemplate,
                    "auxiliary", auxiliaryTemplate,
                    "vision", visionTemplate
            ).entrySet()) {
                RetryTemplateSnapshot snapshot = RetryTemplateSnapshot.of(entry.getValue());
                assertThat(snapshot.isFixedBackOff())
                        .as("%s 的策略被连带改写了：%s", entry.getKey(), snapshot)
                        .isFalse();
                assertThat(snapshot.getMaxAttempts())
                        .as("%s 的重试次数被连带改写了：%s", entry.getKey(), snapshot)
                        .isEqualTo(3);
            }
        });
    }

    // ==================== 辅助方法 ====================

    /**
     * 用 DashScopeImageModel 的真实构造器改写模板——不是模拟，
     * 就是把库里那段 setRetryPolicy/setBackOffPolicy 跑一遍。
     */
    private static void poison(RetryTemplate template) {
        new DashScopeImageModel(
                new DashScopeImageApi("test-api-key"),
                DashScopeImageOptions.builder()
                        .withModel(DashScopeImageApi.DEFAULT_IMAGE_MODEL)
                        .build(),
                template,
                ObservationRegistry.NOOP
        );
    }

    /**
     * `OpenAiChatModel.retryTemplate` 是 private final，
     * 必须 getDeclaredField + setAccessible（DashScope 那个是 public final）。
     */
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
