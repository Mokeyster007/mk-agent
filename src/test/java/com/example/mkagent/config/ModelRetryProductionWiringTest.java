package com.example.mkagent.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产装配下的重试策略断言。
 *
 * 与 DashScopeChatModelRetryWiringTest 的区别：
 * 那个测试用最小上下文验证装配规则；
 * 本测试直接启动**完整的应用上下文**（application.yml + local profile + 全部自动配置），
 * 断言"真实运行时模型手里拿的到底是哪个 RetryTemplate"。
 *
 * 这正是上次事故里最难查的一步：
 * 启动日志说 maxAttempts=3，实际请求却重试了 8 次、每次十几秒。
 * 现在这个断言会把"配置"和"实例"钉在一起，任何漂移都会让构建失败。
 *
 * 事故根因见 DashScopeImageModelRetryPoisoningTest：
 * 图片模型构造时会原地改写它收到的 RetryTemplate，
 * 所以这里额外断言"对话模型持有的是私有实例"，把隔离性也钉住。
 *
 * 本测试不调用任何模型，不产生费用。
 */
@SpringBootTest
@Timeout(120)
class ModelRetryProductionWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    @Qualifier("retryTemplate")
    private RetryTemplate retryTemplate;

    @Test
    void containerHasExactlyOneRetryTemplate() {
        Map<String, RetryTemplate> beans =
                applicationContext.getBeansOfType(RetryTemplate.class);

        /*
         * 只允许存在一个：Spring AI 自动配置的 retryTemplate 必须
         * 因为 @ConditionalOnMissingBean 而让位。
         * 出现第二个就说明"谁在用哪个模板"又开始变得不可预测。
         */
        assertThat(beans)
                .as("容器中的 RetryTemplate Bean 明细")
                .containsOnlyKeys("retryTemplate");
    }

    @Test
    void chatModelHoldsConfiguredTemplateInProductionContext() {
        assertThat(chatModel)
                .as("生产上下文应注入显式构造的 DashScopeChatModel")
                .isInstanceOf(DashScopeChatModel.class);

        RetryTemplateSnapshot snapshot =
                RetryTemplateSnapshot.of(readModelTemplate());

        assertThat(snapshot.isFixedBackOff())
                .as("模型不能用 FixedBackOffPolicy：实际策略=%s", snapshot)
                .isFalse();
        assertThat(snapshot.getMaxAttempts())
                .as("模型的重试次数必须来自 mkagent.model-retry（默认 3）：%s", snapshot)
                .isEqualTo(3);
        assertThat(snapshot.getRetryOn())
                .as("只重试可自愈失败：%s", snapshot)
                .contains("TransientAiException")
                .doesNotContain("NonTransientAiException");
    }

    @Test
    void containerTemplateKeepsConfiguredStrategyAndModelOwnsPrivateInstance() {
        /*
         * 容器 Bean 是 prototype：每个消费方各拿一个实例。
         * 这里断言"拿到的实例仍是 mkagent.model-retry 的策略"，
         * 也就是图片模型的原地改写没有波及到别人。
         */
        RetryTemplateSnapshot beanSnapshot =
                RetryTemplateSnapshot.of(retryTemplate);

        assertThat(beanSnapshot.isFixedBackOff())
                .as("容器 Bean 不能被 DashScopeImageModel 改写成固定退避：%s", beanSnapshot)
                .isFalse();
        assertThat(beanSnapshot.getMaxAttempts())
                .as("容器 Bean 的尝试次数：%s", beanSnapshot)
                .isEqualTo(3);
        assertThat(beanSnapshot.getRetryOn())
                .as("容器 Bean 只重试可自愈失败：%s", beanSnapshot)
                .contains("TransientAiException");

        assertThat(readModelTemplate())
                .as("对话模型必须持有私有实例，而不是共享的容器 Bean")
                .isNotSameAs(retryTemplate);
    }

    private RetryTemplate readModelTemplate() {
        try {
            return (RetryTemplate) DashScopeChatModel.class
                    .getField("retryTemplate")
                    .get(chatModel);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取模型重试模板失败", e);
        }
    }
}
