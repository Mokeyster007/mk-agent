package com.example.mkagent.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试日志降噪单元测试。
 *
 * 断言两件事：
 * 1. 摘要只有一行（换行被折叠），不会把几百行 HTTP 报文刷进 WARN；
 * 2. 密钥在落日志前被打码。
 */
class ModelRetryLoggingListenerTest {

    @Test
    @DisplayName("多行异常消息被折叠成一行，且带异常类型")
    void summaryIsSingleLine() {
        String summary = ModelRetryLoggingListener.summarize(
                new NonTransientAiException(
                        "HTTP 400 - InvalidParameter\n\tat DashScopeApi.call\n  request_id=abc"
                )
        );

        assertFalse(summary.contains("\n"), "摘要不能包含换行");
        assertFalse(summary.contains("\t"), "摘要不能包含制表符");
        assertTrue(summary.startsWith("NonTransientAiException - "));
        assertTrue(summary.contains("InvalidParameter"));
    }

    @Test
    @DisplayName("message 为空时只输出异常类型")
    void summaryHandlesNullMessage() {
        assertEqualsTypeOnly(new TransientAiException(null));
        assertEqualsTypeOnly(new TransientAiException("   "));
    }

    private void assertEqualsTypeOnly(Throwable throwable) {
        String summary = ModelRetryLoggingListener.summarize(throwable);
        assertTrue(summary.equals("TransientAiException"), "实际=" + summary);
    }

    @Test
    @DisplayName("超长 message 被截断，避免单行日志过长")
    void summaryIsTruncated() {
        String summary = ModelRetryLoggingListener.summarize(
                new NonTransientAiException("x".repeat(1000))
        );

        assertTrue(summary.length() < 400, "摘要应被截断，实际长度=" + summary.length());
        assertTrue(summary.endsWith("..."));
    }

    @Test
    @DisplayName("sk- 前缀密钥与 api-key 赋值在日志中被打码")
    void secretsAreMasked() {
        String masked = ModelRetryLoggingListener.maskSecrets(
                "url=https://dashscope.aliyuncs.com?api-key=sk-abcdef1234567890&x=1"
        );

        assertFalse(masked.contains("abcdef1234567890"), "密钥原文不能出现在日志里");
        assertTrue(masked.contains("api-key=***"));

        String masked2 = ModelRetryLoggingListener.maskSecrets(
                "Authorization: Bearer sk-1234567890abcdef"
        );
        assertFalse(masked2.contains("sk-1234567890abcdef"));
        assertTrue(masked2.contains("sk-***"));
    }

    @Test
    @DisplayName("null / 空文本不会让打码逻辑抛异常")
    void maskHandlesNull() {
        assertTrue(ModelRetryLoggingListener.maskSecrets(null) == null);
        assertTrue(ModelRetryLoggingListener.maskSecrets("").isEmpty());
    }
}
