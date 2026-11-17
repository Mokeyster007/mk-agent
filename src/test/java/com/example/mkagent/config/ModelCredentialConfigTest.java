package com.example.mkagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 模型凭据的"配置 → 装配"链路（离线，不发起任何网络调用）。
 *
 * <p>守三件事：
 * <ol>
 *   <li>凭据解析顺序与 DashScope SDK 一致（chat 级优先，空串不算配置）；</li>
 *   <li>凭据缺失时**启动即失败**，且报错里有可执行的环境变量名——不是库自己那句
 *       "DashScope API key must be set"；</li>
 *   <li>配置模板里的嵌套占位符确实能把 SDK 原生变量
 *       {@code AI_DASHSCOPE_API_KEY} 兜住（否则文档里那句话就是假的）。</li>
 * </ol>
 *
 * <p>为什么要有第 2 条：库内部 {@code DashScopeConnectionUtils} 在 apiKey 为空时
 * 先抛自己的 Assert，且只有 apiKey == null 才回退环境变量。本项目的模板把属性
 * 解析成空串，所以校验必须放在 resolve 之前，否则用户永远看不到本项目的提示。
 */
class ModelCredentialConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DashScopeChatModelConfig.class, SupportBeans.class)
            .withPropertyValues(
                    "spring.ai.dashscope.chat.options.model=qwen-test",
                    "mkagent.model-retry.max-attempts=1"
            );

    // ==================== 1. 解析顺序 ====================

    @Test
    @DisplayName("chat 级 api-key 优先于连接级")
    void chatLevelKeyWins() {
        DashScopeChatProperties chat = new DashScopeChatProperties();
        chat.setApiKey("chat-key");
        DashScopeConnectionProperties connection = new DashScopeConnectionProperties();
        connection.setApiKey("connection-key");

        assertThat(DashScopeChatModelConfig.configuredApiKey(chat, connection))
                .isEqualTo("chat-key");
    }

    @Test
    @DisplayName("chat 级为空串/空白时回落到连接级（hasText 语义，不是 null 判断）")
    void blankChatLevelKeyFallsBackToConnectionLevel() {
        DashScopeConnectionProperties connection = new DashScopeConnectionProperties();
        connection.setApiKey("connection-key");

        DashScopeChatProperties blank = new DashScopeChatProperties();
        blank.setApiKey("");
        assertThat(DashScopeChatModelConfig.configuredApiKey(blank, connection))
                .isEqualTo("connection-key");

        DashScopeChatProperties whitespace = new DashScopeChatProperties();
        whitespace.setApiKey("   ");
        assertThat(DashScopeChatModelConfig.configuredApiKey(whitespace, connection))
                .isEqualTo("connection-key");
    }

    // ==================== 2. 缺失即失败，错误可执行 ====================

    @Test
    @DisplayName("模型凭据为空：上下文启动失败，报错点名 MKAGENT_MODEL_API_KEY")
    void missingCredentialFailsFastWithActionableMessage() {
        runner.withPropertyValues(
                        "spring.ai.dashscope.api-key=",
                        "spring.ai.dashscope.chat.api-key="
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = deepestMessage(context.getStartupFailure());
                    assertThat(message)
                            .contains("未配置模型凭据")
                            .contains("MKAGENT_MODEL_API_KEY")
                            .contains("config/application-local.yml")
                            // 不能把密钥本身写进报错
                            .doesNotContain("sk-");
                    // 失败发生在装配阶段（创建 dashscopeChatModel 时），不是启动后才炸
                    assertThat(String.valueOf(context.getStartupFailure().getMessage()))
                            .contains("dashscopeChatModel");
                });
    }

    @Test
    @DisplayName("提供了凭据则正常装配（校验不会误伤正常启动）")
    void credentialPresentStartsNormally() {
        runner.withPropertyValues("spring.ai.dashscope.api-key=test-key-not-real")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DashScopeChatModel.class);
                });
    }

    // ==================== 3. 模板占位符语义 ====================

    @Test
    @DisplayName("模板占位符：优先 MKAGENT_*，缺失时退回 AI_DASHSCOPE_API_KEY")
    void templatePlaceholderFallsBackToSdkEnvironmentVariable() {
        String template = "${MKAGENT_MODEL_API_KEY:${AI_DASHSCOPE_API_KEY:}}";

        // 两个都没配 → 空串（交给启动校验报错，而不是 null 触发库的隐式回退）
        assertThat(environmentWith(Map.of()).resolvePlaceholders(template)).isEmpty();

        // 只有 SDK 原生变量 → 用它
        assertThat(environmentWith(Map.of("AI_DASHSCOPE_API_KEY", "sdk-key"))
                .resolvePlaceholders(template)).isEqualTo("sdk-key");

        // 两个都有 → MKAGENT_* 优先
        assertThat(environmentWith(Map.of(
                "MKAGENT_MODEL_API_KEY", "project-key",
                "AI_DASHSCOPE_API_KEY", "sdk-key"
        )).resolvePlaceholders(template)).isEqualTo("project-key");
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static String deepestMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    /**
     * 生产环境里由 Spring Boot 自动配置提供的协作 Bean。
     * 这里显式给出，避免把"缺 Bean"当成"缺凭据"来测。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModelRetryProperties.class)
    static class SupportBeans {

        @Bean
        ToolCallingManager toolCallingManager() {
            return mock(ToolCallingManager.class);
        }

        @Bean
        ResponseErrorHandler responseErrorHandler() {
            return new DefaultResponseErrorHandler();
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder();
        }
    }
}
