package com.example.mkagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeImageApi;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageModel;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageOptions;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复现并钉死"重试策略被库代码原地改写"这个根因。
 *
 * 事故现象：HTTP 400（NonTransientAiException）被重试 8 次、每次间隔约 15 秒，
 * 堆栈落在 DashScopeChatModel.internalCall，策略是 FixedBackOffPolicy。
 * 但启动日志明明白白写着 maxAttempts=3 / exponential。
 *
 * 根因（反编译 spring-ai-alibaba-core 1.0.0.2 确认，不是推测）：
 * DashScopeImageModel 的构造器会对自己收到的 RetryTemplate 直接调
 * setRetryPolicy(new SimpleRetryPolicy(10)) 与
 * setBackOffPolicy(fixedBackOff(15000))。它改的不是"自己的副本"，而是**共享实例**。
 * 于是"容器里只有一个 RetryTemplate Bean"这件事本身成了放大器：
 * 图片模型一构造，对话模型手里那个模板（同一个对象）就变成了
 * 10 次 + 固定 15 秒 + 连 Exception 都重试。
 *
 * 这两个用例分别验证：
 * 1. 该库行为确实存在（如果哪天升级修掉了，这个用例会失败，提醒我们回来简化配置）；
 * 2. 对话模型使用私有实例后，容器模板被改写也影响不到它（修复的隔离性）。
 *
 * 不联网、不启动 Spring。
 */
class DashScopeImageModelRetryPoisoningTest {

    private final ModelRetryProperties properties = new ModelRetryProperties();

    @Test
    @DisplayName("DashScopeImageModel 会原地改写传入的 RetryTemplate（库缺陷复现）")
    void imageModelRewritesInjectedRetryTemplateInPlace() {
        RetryTemplate template = ModelRetryConfig.build(properties);

        RetryTemplateSnapshot before = RetryTemplateSnapshot.of(template);
        assertThat(before.getMaxAttempts())
                .as("改写前的初始状态")
                .isEqualTo(3);
        assertThat(before.isFixedBackOff()).isFalse();

        newImageModel(template);

        RetryTemplateSnapshot after = RetryTemplateSnapshot.of(template);
        assertThat(after.getMaxAttempts())
                .as("图片模型把次数改成了 10：%s", after)
                .isEqualTo(10);
        assertThat(after.isFixedBackOff())
                .as("图片模型把退避改成了固定 15 秒：%s", after)
                .isTrue();
        assertThat(after.getBackOffDescription())
                .isEqualTo("FixedBackOffPolicy(period=15000ms)");
        assertThat(after.getRetryOn())
                .as("SimpleRetryPolicy(10) 连 Exception 都重试，所以 400 也逃不掉")
                .contains("Exception");
    }

    @Test
    @DisplayName("对话模型使用私有模板，不受容器模板被改写的影响")
    void chatTemplateStaysCleanWhenContainerTemplateIsRewritten() {
        // 模拟容器 Bean：交给图片模型，随后被改写
        RetryTemplate containerTemplate = ModelRetryConfig.build(properties);
        // 模拟 DashScopeChatModelConfig 里给对话模型构建的私有模板
        RetryTemplate chatTemplate = ModelRetryConfig.build(properties);

        newImageModel(containerTemplate);

        assertThat(RetryTemplateSnapshot.of(containerTemplate).isFixedBackOff())
                .as("容器模板确实被改写了（这正是要隔离的对象）")
                .isTrue();

        RetryTemplateSnapshot chatSnapshot = RetryTemplateSnapshot.of(chatTemplate);
        assertThat(chatSnapshot.isFixedBackOff())
                .as("对话模型的模板必须是独立实例，不能被连带改写：%s", chatSnapshot)
                .isFalse();
        assertThat(chatSnapshot.getMaxAttempts()).isEqualTo(3);
        assertThat(chatSnapshot.getRetryOn())
                .contains("TransientAiException")
                .doesNotContain("Exception");
    }

    private static DashScopeImageModel newImageModel(RetryTemplate retryTemplate) {
        // 构造器只做参数校验，不发请求；api-key 随便填一个即可。
        return new DashScopeImageModel(
                new DashScopeImageApi("test-api-key"),
                DashScopeImageOptions.builder()
                        .withModel(DashScopeImageApi.DEFAULT_IMAGE_MODEL)
                        .build(),
                retryTemplate,
                ObservationRegistry.NOOP
        );
    }
}
