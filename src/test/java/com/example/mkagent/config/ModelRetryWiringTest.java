package com.example.mkagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试策略装配验证（不联网）。
 *
 * 这里刻意把 Spring AI 自己的 {@link SpringAiRetryAutoConfiguration} 一起加载，
 * 证明两件事：
 * 1. 项目自定义的 RetryTemplate 真的覆盖了 Spring AI 的默认 Bean
 *    （默认 maxAttempts=10，这里必须表现为 3 次）；
 * 2. 429 经 ResponseErrorHandler 被归类为 TransientAiException（可重试），
 *    400 仍为 NonTransientAiException（不重试）。
 *
 * 第 2 点依赖 application.yml 里的 spring.ai.retry.on-http-codes: 429，
 * 本测试用等价的属性值复现，避免启动完整应用（不连数据库、不连模型）。
 */
class ModelRetryWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelRetryConfig.class, ModelRetryProperties.class)
            .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
            .withPropertyValues(
                    "mkagent.model-retry.max-attempts=3",
                    "mkagent.model-retry.initial-backoff=1ms",
                    "mkagent.model-retry.multiplier=1.0",
                    "mkagent.model-retry.max-backoff=1ms",
                    "spring.ai.retry.on-http-codes=429"
            );

    @Test
    @DisplayName("项目自定义 RetryTemplate 覆盖 Spring AI 默认 Bean：Transient 只重试到 3 次")
    void projectRetryTemplateOverridesSpringAiDefault() {
        runner.run(context -> {
            assertTrue(context.getStartupFailure() == null, "上下文应能正常启动");

            RetryTemplate retryTemplate = context.getBean(RetryTemplate.class);
            AtomicInteger attempts = new AtomicInteger();

            long start = System.currentTimeMillis();
            assertThrows(
                    TransientAiException.class,
                    () -> retryTemplate.execute(callback -> {
                        attempts.incrementAndGet();
                        throw new TransientAiException("HTTP 503");
                    })
            );
            long elapsed = System.currentTimeMillis() - start;

            assertEquals(
                    3,
                    attempts.get(),
                    "Spring AI 默认是 10 次；这里必须是项目配置的 3 次"
            );
            assertTrue(elapsed < 1_000, "退避应来自 mkagent.model-retry，实际=" + elapsed + "ms");
        });
    }

    @Test
    @DisplayName("429 归类为可重试（TransientAiException），400 归类为不可重试")
    void httpStatusMapping() {
        runner.run(context -> {
            ResponseErrorHandler errorHandler = context.getBean(ResponseErrorHandler.class);

            MockClientHttpResponse tooManyRequests = new MockClientHttpResponse(
                    "{\"code\":\"Throttling\"}".getBytes(StandardCharsets.UTF_8),
                    HttpStatus.TOO_MANY_REQUESTS
            );
            assertThrows(
                    TransientAiException.class,
                    () -> errorHandler.handleError(tooManyRequests),
                    "429 是限流，应可重试"
            );

            MockClientHttpResponse badRequest = new MockClientHttpResponse(
                    "{\"code\":\"InvalidParameter\"}".getBytes(StandardCharsets.UTF_8),
                    HttpStatus.BAD_REQUEST
            );
            assertThrows(
                    org.springframework.ai.retry.NonTransientAiException.class,
                    () -> errorHandler.handleError(badRequest),
                    "400 是参数错误，不应重试"
            );

            MockClientHttpResponse unauthorized = new MockClientHttpResponse(
                    "{\"code\":\"InvalidApiKey\"}".getBytes(StandardCharsets.UTF_8),
                    HttpStatus.UNAUTHORIZED
            );
            assertThrows(
                    org.springframework.ai.retry.NonTransientAiException.class,
                    () -> errorHandler.handleError(unauthorized),
                    "401 鉴权失败不应重试"
            );
        });
    }
}
