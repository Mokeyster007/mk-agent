package com.example.mkagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.agent.MkManus;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * 真实装配链路的重试策略测试。
 *
 * 与 ModelRetryWiringTest 的区别：
 * 后者只验证"RetryTemplate Bean 本身的策略"；
 * 本测试把 DashScope 的聊天自动配置（以及它 @Import 的 Spring AI 重试自动配置）
 * 一起加载，验证"模型最终拿到的是哪套策略"——
 * 这正是上次线上事故里"配置打印正确、实际却重试 8 次"的那个断点。
 *
 * 全程不触网：
 * - 用 MockRestServiceServer 绑住 RestClient.Builder，text-generation 端点返回 400；
 * - 断言 HTTP 请求只发生 1 次、模型抛 NonTransientAiException、
 *   ToolCallAgent 秒级进入 FAILED。
 */
@Timeout(60)
class DashScopeChatModelRetryWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            DashScopeChatModelConfig.class,
                            ModelRetryConfig.class,
                            ModelRetryPropertiesConfig.class
                    )
                    .withConfiguration(AutoConfigurations.of(
                            SpringAiRetryAutoConfiguration.class,
                            DashScopeChatAutoConfiguration.class
                    ))
                    .withBean(ToolCallingManager.class,
                            () -> ToolCallingManager.builder().build())
                    .withPropertyValues(
                            "spring.ai.dashscope.api-key=test-api-key",
                            "mkagent.model-retry.max-attempts=3",
                            "mkagent.model-retry.initial-backoff=1s",
                            "mkagent.model-retry.max-backoff=2s",
                            "mkagent.model-retry.multiplier=2.0"
                    );

    // ==================== 1. 容器里的 RetryTemplate ====================

    @Test
    void onlyOneRetryTemplateBeanAndItIsNotFixedBackOff() {
        withBoundServer((server, builder) -> contextRunner
                .withBean(RestClient.Builder.class, () -> builder)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .run(context -> {
                    Map<String, RetryTemplate> beans =
                            context.getBeansOfType(RetryTemplate.class);

                    // 自动配置的 retryTemplate 必须让位，容器里只留我们这一个。
                    assertThat(beans)
                            .as("容器中 RetryTemplate Bean 数量")
                            .hasSize(1);

                    RetryTemplateSnapshot snapshot = RetryTemplateSnapshot.of(
                            beans.values().iterator().next());

                    assertThat(snapshot.getMaxAttempts()).isEqualTo(3);
                    assertThat(snapshot.isFixedBackOff())
                            .as("绝不能用 FixedBackOffPolicy（每次固定等十几秒）")
                            .isFalse();
                    assertThat(snapshot.getBackOffDescription())
                            .startsWith("ExponentialBackOffPolicy");
                    assertThat(snapshot.getRetryOn())
                            .contains("TransientAiException")
                            .doesNotContain("NonTransientAiException");
                }));
    }

    // ==================== 2. 模型实际持有的模板 ====================

    @Test
    void chatModelHoldsConfiguredRetryTemplate() {
        withBoundServer((server, builder) -> contextRunner
                .withBean(RestClient.Builder.class, () -> builder)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .run(context -> {
                    DashScopeChatModel model =
                            context.getBean(DashScopeChatModel.class);

                    RetryTemplate held = readRetryTemplate(model);
                    assertThat(held)
                            .as("DashScopeChatModel.retryTemplate 必须可读")
                            .isNotNull();

                    RetryTemplateSnapshot snapshot =
                            RetryTemplateSnapshot.of(held);

                    assertThat(snapshot.getMaxAttempts()).isEqualTo(3);
                    assertThat(snapshot.isFixedBackOff()).isFalse();
                    assertThat(snapshot.getRetryOn())
                            .contains("TransientAiException");
                }));
    }

    // ==================== 3. 400 只发一次请求 ====================

    @Test
    void badRequestIsSentOnlyOnce() {
        withBoundServer((server, builder) -> contextRunner
                .withBean(RestClient.Builder.class, () -> builder)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .run(context -> {
                    expectBadRequest(server);

                    DashScopeChatModel model =
                            context.getBean(DashScopeChatModel.class);

                    long start = System.currentTimeMillis();
                    assertThatThrownBy(() -> model.call(new Prompt("ping")))
                            .as("400 应直接抛 NonTransientAiException")
                            .isInstanceOf(NonTransientAiException.class);

                    long elapsed = System.currentTimeMillis() - start;

                    // ExpectedCount.once() + verify()：请求只能发生一次。
                    server.verify();

                    assertThat(elapsed)
                            .as("400 必须秒级失败，不能被退避重试拖住")
                            .isLessThan(3000L);
                }));
    }

    // ==================== 4. ToolCallAgent 秒级 FAILED ====================

    @Test
    void toolCallAgentFailsFastOnBadRequest() {
        withBoundServer((server, builder) -> contextRunner
                .withBean(RestClient.Builder.class, () -> builder)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .run(context -> {
                    expectBadRequest(server);

                    DashScopeChatModel model =
                            context.getBean(DashScopeChatModel.class);

                    MkManus agent = new MkManus(
                            model,
                            new ToolCallback[0],
                            Runnable::run,
                            null,
                            new DefaultToolEventMessageProvider(),
                            null,
                            new AgentConcurrencyGuard(2)
                    );

                    AgentRunContext ctx = new AgentRunContext();
                    long start = System.currentTimeMillis();

                    try {
                        agent.run("这条请求会被模型以 400 拒绝", ctx);
                    } catch (RuntimeException ignored) {
                        // 快速失败允许向上抛出，下面只断言终态与耗时。
                    }

                    long elapsed = System.currentTimeMillis() - start;
                    server.verify();

                    assertThat(ctx.getState())
                            .as("400 应立刻把任务置为 FAILED，而不是等到超时")
                            .isEqualTo(AgentState.FAILED);
                    assertThat(elapsed)
                            .as("ToolCallAgent 必须在秒级内失败")
                            .isLessThan(5000L);
                }));
    }

    // ==================== 辅助方法 ====================

    private void expectBadRequest(MockRestServiceServer server) {
        server.expect(ExpectedCount.once(), requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"InvalidParameter\",\"message\":\"bad request\"}"));
    }

    /**
     * 绑定 MockRestServiceServer 后再交给上下文使用。
     *
     * 必须在 DashScopeApi 构建 RestClient 之前完成绑定，
     * 因此 builder 与 server 都在上下文启动前创建。
     */
    private void withBoundServer(ServerConsumer consumer) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        consumer.accept(server, builder);
    }

    private static RetryTemplate readRetryTemplate(DashScopeChatModel model)
            throws ReflectiveOperationException {
        return (RetryTemplate) DashScopeChatModel.class
                .getField("retryTemplate")
                .get(model);
    }

    @FunctionalInterface
    private interface ServerConsumer {
        void accept(MockRestServiceServer server, RestClient.Builder builder);
    }

    /**
     * 让 mkagent.model-retry.* 可绑定。
     */
    @Configuration
    @EnableConfigurationProperties(ModelRetryProperties.class)
    static class ModelRetryPropertiesConfig {
    }
}
