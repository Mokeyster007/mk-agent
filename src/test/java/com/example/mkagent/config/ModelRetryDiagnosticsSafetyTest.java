package com.example.mkagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 自检代码不得成为新的故障源。
 *
 * {@link ModelRetryDiagnostics} 是 `ApplicationRunner`：`run()` 抛出的异常会让
 * `SpringApplication.run` 失败并关闭上下文。也就是说，一段"只用来排查问题"的代码
 * 完全可能把应用搞挂——这比不做诊断更糟。
 *
 * 因此这里逐个验证失败路径：
 * 1. Bean 枚举失败（Bean 实例化直接抛异常）；
 * 2. 模型没有 `retryTemplate` 字段（反射失败）；
 * 3. 策略异常时只 WARN，不抛异常。
 *
 * 这些用例都不启动完整的 Spring Boot 应用，也不发任何模型请求。
 */
@ExtendWith(OutputCaptureExtension.class)
class ModelRetryDiagnosticsSafetyTest {

    @Test
    @DisplayName("Bean 实例化失败时只 WARN，不影响启动")
    void beanEnumerationFailureIsSwallowed(CapturedOutput output) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            /*
             * 必须 lazy-init：否则 refresh() 阶段就会实例化并抛异常，
             * 测试还没走到自检就失败了，验证不到"枚举失败被吞掉"这条路径。
             * lazy 之后，异常发生在 getBeansOfType(...) 里，正是要测的位置。
             */
            context.registerBean("retryTemplate", RetryTemplate.class, () -> {
                throw new IllegalStateException("模拟 Bean 实例化失败");
            }, beanDefinition -> beanDefinition.setLazyInit(true));
            context.refresh();

            ModelRetryDiagnostics diagnostics = new ModelRetryDiagnostics(
                    context,
                    new StandardEnvironment(),
                    new ChatModelWithoutRetryField()
            );

            assertThatCode(() -> diagnostics.run(null))
                    .as("自检异常必须被吞掉，不能冒泡到 SpringApplication.run")
                    .doesNotThrowAnyException();

            assertThat(output)
                    .as("应该留下可排查的 WARN（步骤名 + 已忽略）")
                    .contains("不影响启动")
                    .contains("枚举 RetryTemplate Bean");
        }
    }

    @Test
    @DisplayName("模型没有 retryTemplate 字段时跳过实例级校验，不抛异常")
    void missingRetryFieldIsSkipped(CapturedOutput output) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            ModelRetryDiagnostics diagnostics = new ModelRetryDiagnostics(
                    context,
                    new StandardEnvironment(),
                    new ChatModelWithoutRetryField()
            );

            assertThatCode(() -> diagnostics.run(null)).doesNotThrowAnyException();
            assertThat(output)
                    .as("应说明是跳过了实例级校验")
                    .contains("跳过实例级校验");
        }
    }

    @Test
    @DisplayName("发现 FixedBackOffPolicy 时只 WARN，不抛异常")
    void fixedBackOffOnlyWarns(CapturedOutput output) {
        RetryTemplate poisoned = new RetryTemplate();
        poisoned.setRetryPolicy(new SimpleRetryPolicy(10));
        FixedBackOffPolicy fixed = new FixedBackOffPolicy();
        fixed.setBackOffPeriod(15000L);
        poisoned.setBackOffPolicy(fixed);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("retryTemplate", RetryTemplate.class, () -> poisoned);
            context.refresh();

            ModelRetryDiagnostics diagnostics = new ModelRetryDiagnostics(
                    context,
                    new StandardEnvironment(),
                    new ChatModelHolding(poisoned)
            );

            assertThatCode(() -> diagnostics.run(null)).doesNotThrowAnyException();

            assertThat(output)
                    .as("必须指出具体的退避参数，而不是只说“有问题”")
                    .contains("FixedBackOffPolicy(period=15000ms)")
                    .contains("DashScopeImageModel");
            assertThat(output)
                    .as("只 WARN，不能是 ERROR")
                    .doesNotContain("ERROR");
        }
    }

    /**
     * 不带 `retryTemplate` 字段的假模型：反射读取必须优雅失败。
     */
    static class ChatModelWithoutRetryField implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("测试替身不真正调用模型");
        }
    }

    /**
     * 持有指定模板的假模型：字段名与 `DashScopeChatModel` 一致，供反射读取。
     */
    static class ChatModelHolding implements ChatModel {

        public final RetryTemplate retryTemplate;

        ChatModelHolding(RetryTemplate retryTemplate) {
            this.retryTemplate = retryTemplate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("测试替身不真正调用模型");
        }
    }
}
