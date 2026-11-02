package com.example.mkagent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测开关必须只认 mkagent.eval.*，不能继承生产开关。
 *
 * 为什么要单独锁：EvalAgent 是手动 super(...) 构造的非 Spring Bean，
 * 它的收尾策略来自 EvalProperties。一旦有人图省事把 EvalProperties
 * 绑到 mkagent.agent.*，评测就会跟着生产配置漂移——
 * 生产开 force-final-answer=true，评测就会多出一次收尾调用，
 * 历史用例的 Token 口径与可比性全部作废。
 */
class EvalPropertiesIsolationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EvalPropertiesConfig.class);

    @Test
    @DisplayName("生产开关为 true / 2 时，评测开关仍是 false / 0")
    void evalSwitchesDoNotInheritProductionSwitches() {
        runner.withPropertyValues(
                        "mkagent.agent.force-final-answer=true",
                        "mkagent.agent.budget-warning-steps=2"
                )
                .run(context -> {
                    EvalProperties properties = context.getBean(EvalProperties.class);

                    assertThat(properties.isForceFinalAnswer())
                            .as("评测不得继承 mkagent.agent.force-final-answer")
                            .isFalse();
                    assertThat(properties.getBudgetWarningSteps())
                            .as("评测不得继承 mkagent.agent.budget-warning-steps")
                            .isZero();
                });
    }

    @Test
    @DisplayName("评测自己的开关照常生效")
    void evalOwnSwitchesStillBind() {
        runner.withPropertyValues(
                        "mkagent.eval.force-final-answer=true",
                        "mkagent.eval.budget-warning-steps=3"
                )
                .run(context -> {
                    EvalProperties properties = context.getBean(EvalProperties.class);

                    assertThat(properties.isForceFinalAnswer()).isTrue();
                    assertThat(properties.getBudgetWarningSteps()).isEqualTo(3);
                });
    }

    @Configuration
    @EnableConfigurationProperties(EvalProperties.class)
    static class EvalPropertiesConfig {
    }
}
