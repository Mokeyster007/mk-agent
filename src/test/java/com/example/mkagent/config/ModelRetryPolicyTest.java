package com.example.mkagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用重试策略单元测试（不联网、不启动 Spring）。
 *
 * 覆盖三类失败语义：
 * 1. NonTransientAiException（400/401/403/欠费）→ 一次都不重试；
 * 2. TransientAiException（5xx/429）→ 重试到上限（3 次）；
 * 3. 网络超时（ResourceAccessException）→ 重试到上限，可配置关闭。
 *
 * 另外断言退避策略是"指数 + 封顶"，不是日志里那种固定 15 秒。
 */
class ModelRetryPolicyTest {

    /**
     * 测试用极小退避，保证用例毫秒级完成。
     */
    private static ModelRetryProperties fastProperties() {
        ModelRetryProperties properties = new ModelRetryProperties();
        properties.setMaxAttempts(3);
        properties.setInitialBackoff(Duration.ofMillis(10));
        properties.setMultiplier(2.0);
        properties.setMaxBackoff(Duration.ofMillis(20));
        properties.setRetryOnTimeout(true);
        return properties;
    }

    @Test
    @DisplayName("400 参数错误（NonTransientAiException）只调用 1 次，不重试")
    void nonTransientIsNotRetried() {
        RetryTemplate retryTemplate = ModelRetryConfig.build(fastProperties());
        AtomicInteger attempts = new AtomicInteger();

        RuntimeException error = assertThrows(
                NonTransientAiException.class,
                () -> retryTemplate.execute(context -> {
                    attempts.incrementAndGet();
                    throw new NonTransientAiException("HTTP 400 - InvalidParameter");
                })
        );

        assertEquals(1, attempts.get(), "4xx 属于不可重试错误，不应再次请求");
        assertTrue(error.getMessage().contains("400"));
    }

    @Test
    @DisplayName("5xx/429（TransientAiException）重试到上限后抛出真实异常")
    void transientIsRetriedToLimit() {
        RetryTemplate retryTemplate = ModelRetryConfig.build(fastProperties());
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(
                TransientAiException.class,
                () -> retryTemplate.execute(context -> {
                    attempts.incrementAndGet();
                    throw new TransientAiException("HTTP 503 - Service Unavailable");
                })
        );

        assertEquals(3, attempts.get(), "可重试错误应尝试 max-attempts 次");
    }

    @Test
    @DisplayName("网络超时（ResourceAccessException）同样有限重试")
    void timeoutIsRetried() {
        RetryTemplate retryTemplate = ModelRetryConfig.build(fastProperties());
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(
                ResourceAccessException.class,
                () -> retryTemplate.execute(context -> {
                    attempts.incrementAndGet();
                    throw new ResourceAccessException("Read timed out");
                })
        );

        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("retry-on-timeout=false 时超时不再重试")
    void timeoutRetryCanBeDisabled() {
        ModelRetryProperties properties = fastProperties();
        properties.setRetryOnTimeout(false);

        RetryTemplate retryTemplate = ModelRetryConfig.build(properties);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(
                ResourceAccessException.class,
                () -> retryTemplate.execute(context -> {
                    attempts.incrementAndGet();
                    throw new ResourceAccessException("Read timed out");
                })
        );

        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("重试成功后正常返回，且只调用到成功那次")
    void recoversAfterTransientFailure() {
        RetryTemplate retryTemplate = ModelRetryConfig.build(fastProperties());
        AtomicInteger attempts = new AtomicInteger();

        String result = retryTemplate.execute(context -> {
            if (attempts.incrementAndGet() < 2) {
                throw new TransientAiException("HTTP 502");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("退避策略是指数退避且封顶，不是固定 15 秒")
    void backoffIsExponentialAndBounded() {
        ModelRetryProperties properties = new ModelRetryProperties();

        ExponentialBackOffPolicy policy = assertInstanceOf(
                ExponentialBackOffPolicy.class,
                ModelRetryConfig.backOffPolicy(properties)
        );

        assertEquals(1_000L, policy.getInitialInterval(), "默认首次退避 1 秒");
        assertEquals(2.0d, policy.getMultiplier(), "指数倍数 2");
        assertEquals(2_000L, policy.getMaxInterval(), "单次退避封顶 2 秒");
    }

    @Test
    @DisplayName("默认配置对交互式 Agent 友好：3 次尝试、总等待不超过数秒")
    void defaultsAreAgentFriendly() {
        ModelRetryProperties properties = new ModelRetryProperties();

        assertEquals(3, properties.getMaxAttempts());
        assertEquals(Duration.ofSeconds(1), properties.getInitialBackoff());
        assertEquals(Duration.ofSeconds(2), properties.getMaxBackoff());
        assertTrue(properties.isRetryOnTimeout());

        /*
         * 最坏情况总等待 = 1s + 2s = 3s，
         * 远小于 Agent 的 2 分钟总超时，也远小于旧策略的 10 次指数退避。
         */
        Duration worstCase = properties.getInitialBackoff()
                .plus(properties.getMaxBackoff());
        assertTrue(worstCase.compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    @DisplayName("三次尝试的总耗时有界（毫秒级配置下远小于固定 15 秒）")
    void retryDurationIsBounded() {
        ModelRetryProperties properties = fastProperties();
        RetryTemplate retryTemplate = ModelRetryConfig.build(properties);
        AtomicInteger attempts = new AtomicInteger();

        long start = System.currentTimeMillis();
        assertThrows(
                TransientAiException.class,
                () -> retryTemplate.execute(context -> {
                    attempts.incrementAndGet();
                    throw new TransientAiException("HTTP 500");
                })
        );
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(3, attempts.get());
        assertTrue(elapsed < 1_000, "退避应由配置决定，不能出现秒级固定等待，实际=" + elapsed + "ms");
    }

    @Test
    @DisplayName("max-attempts 配成 1 时不重试")
    void singleAttemptConfiguration() {
        ModelRetryProperties properties = fastProperties();
        properties.setMaxAttempts(1);

        RetryTemplate retryTemplate = ModelRetryConfig.build(properties);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(
                TransientAiException.class,
                () -> retryTemplate.execute(context -> {
                    attempts.incrementAndGet();
                    throw new TransientAiException("HTTP 500");
                })
        );

        assertEquals(1, attempts.get());
    }
}
