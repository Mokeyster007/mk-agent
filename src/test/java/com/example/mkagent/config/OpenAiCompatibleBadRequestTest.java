package com.example.mkagent.config;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * OpenAI 兼容客户端的"400 不重试"验证。
 *
 * 与 DashScope 那条链路同样的要求：参数错误必须秒级失败，
 * 不能被退避重试拖住——否则用户看到的是"报错了但请求一直不返回"。
 *
 * 全程不触网：MockRestServiceServer 绑住 RestClient.Builder，
 * /chat/completions 直接返回 400。
 */
@Timeout(60)
class OpenAiCompatibleBadRequestTest {

    @Test
    void badRequestIsSentOnlyOnceAndFailsFast() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

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
                .withBean(RestClient.Builder.class, () -> builder)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .withPropertyValues(
                        "mkagent.model.base-url=http://localhost:9999/v1",
                        "mkagent.model.api-key=test-api-key",
                        "mkagent.model.agent.name=agent-model"
                )
                .run(context -> {
                    server.expect(ExpectedCount.once(),
                                    requestTo(Matchers.any(String.class)))
                            .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body("{\"error\":{\"message\":\"bad request\"}}"));

                    ChatModel model =
                            context.getBean("dashscopeChatModel", ChatModel.class);

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
                });
    }

    @Configuration
    @EnableConfigurationProperties(ModelRetryProperties.class)
    static class ModelRetryPropertiesConfig {
    }
}
