package com.example.mkagent.eval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mkagent.eval 新增预算项的属性绑定测试。
 *
 * 使用轻量 ApplicationContextRunner，不启动完整应用上下文，
 * 只验证 kebab-case 配置能正确绑定到 EvalProperties。
 */
class EvalPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultsAreWarningOffAndForceFinalAnswerOff() {
        runner.run(context -> {
            EvalProperties properties = context.getBean(EvalProperties.class);

            assertThat(properties.getBudgetWarningSteps())
                    .as("评测默认不追加收尾提示")
                    .isZero();
            assertThat(properties.isForceFinalAnswer())
                    .as("评测默认不执行预算耗尽后的强制收尾调用")
                    .isFalse();
        });
    }

    @Test
    void propertiesCanBeOverriddenForComparisonExperiments() {
        runner.withPropertyValues(
                        "mkagent.eval.budget-warning-steps=2",
                        "mkagent.eval.force-final-answer=true"
                )
                .run(context -> {
                    EvalProperties properties =
                            context.getBean(EvalProperties.class);

                    assertThat(properties.getBudgetWarningSteps()).isEqualTo(2);
                    assertThat(properties.isForceFinalAnswer()).isTrue();
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EvalProperties.class)
    static class TestConfig {
    }
}
