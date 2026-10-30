package com.example.mkagent.config;

import com.example.mkagent.resilience.ModelRetryLoggingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.support.RetryTemplateBuilder;
import org.springframework.web.client.ResourceAccessException;

/**
 * 模型调用重试策略配置。
 *
 * 为什么需要这个类（先回答"它在哪里配置"）：
 * 1. DashScopeChatModel 自身不带重试策略，构造器/Builder 只是接收一个 RetryTemplate；
 * 2. 真正生效的 Bean 由 spring-ai-autoconfigure-retry 的
 *    SpringAiRetryAutoConfiguration#retryTemplate(SpringAiRetryProperties) 提供，
 *    参数绑定在 spring.ai.retry.*（默认 maxAttempts=10、指数退避 2s x5、上限 180s）；
 * 3. DashScopeChatAutoConfiguration#dashscopeChatModel(RetryTemplate, ...) 把它注入
 *    DashScopeChatModel.Builder#retryTemplate(...)。
 *
 * 也就是说：改 spring.ai.retry.* 只是"调参数"，语义仍然是"重试 TransientAiException"，
 * 但默认 10 次 + 180s 上限对一个交互式 Agent 完全不可接受。
 * 这里直接定义同名 RetryTemplate Bean（Spring AI 的自动配置带
 * @ConditionalOnMissingBean，会整体让位），把策略固定为：
 *
 * 只重试：TransientAiException（5xx / 429）、ResourceAccessException（网络超时）
 * 绝不重试：NonTransientAiException（400 参数错误、401/403 鉴权、欠费）
 * 次数：3 次（首次 + 2 次重试）
 * 退避：1s → 2s（指数，封顶 2s）
 *
 * 注意：429 默认会被 ResponseErrorHandler 归为 4xx → NonTransientAiException，
 * 所以 application.yml 里额外配置了 spring.ai.retry.on-http-codes: 429，
 * 让 429 归类为 TransientAiException，与 5xx 走同一条可重试路径。
 *
 * ================== 为什么这个 Bean 是 prototype ==================
 *
 * 真实故障复盘（400 被重试 8 次、每次间隔 15 秒）的根因不在本类，
 * 而在 spring-ai-alibaba 的 DashScopeImageModel 构造器里——它会把传进来的
 * RetryTemplate **原地改写**（反编译 1.0.0.2 得到，非猜测）：
 *
 * <pre>
 * SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(10);
 * FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
 * backOffPolicy.setBackOffPeriod(15000L);
 * retryTemplate.setRetryPolicy(retryPolicy);      // ← 改的是共享 Bean
 * retryTemplate.setBackOffPolicy(backOffPolicy);  // ← 改的是共享 Bean
 * </pre>
 *
 * 于是"容器里只有一个 RetryTemplate Bean"这件事本身成了 bug 的放大器：
 * 对话模型先创建、图片模型后创建，图片模型一构造，
 * 对话模型手里的模板（同一个对象）就变成了"10 次 + 固定 15 秒 + 连 Exception 都重试"。
 * 启动日志仍然打印着 maxAttempts=3，因为它打印的是构建时的快照。
 *
 * 两道防线：
 * 1. 对话模型不再注入本 Bean，而是在 DashScopeChatModelConfig 里自行构建
 *    （模型实例与容器彻底解耦，见该类注释）；
 * 2. 本 Bean 改为 prototype：每个消费方（Embedding、Image 等）各拿一个实例，
 *    谁改写都不影响别人。策略仍然统一来自 mkagent.model-retry.*，
 *    差别只是"不再共享同一个可变对象"。
 *
 * 对应的回归测试：DashScopeImageModelRetryPoisoningTest。
 */
@Configuration
public class ModelRetryConfig {

    private static final Logger log =
            LoggerFactory.getLogger(ModelRetryConfig.class);

    /**
     * 模型调用重试模板。
     *
     * 作用域是 prototype，不是 singleton —— 原因见类注释：
     * DashScopeImageModel 会原地 setRetryPolicy/setBackOffPolicy 改写传进去的模板，
     * 共享单例会让"图片模型一构造，对话模型的重试策略就被改掉"。
     *
     * 每个消费方拿到的策略仍然完全一致（都来自 mkagent.model-retry.*），
     * 只是不再共享同一个可变对象。
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RetryTemplate retryTemplate(ModelRetryProperties properties) {
        RetryTemplate retryTemplate = build(properties);

        log.info(
                "模型调用重试策略：maxAttempts={}, backoff=exponential(initial={}ms, x{}, max={}ms), "
                        + "retryOn=[TransientAiException{}, NonTransientAiException=不重试]",
                safeMaxAttempts(properties),
                safeInitialBackoff(properties).toMillis(),
                safeMultiplier(properties),
                safeMaxBackoff(properties).toMillis(),
                properties.isRetryOnTimeout() ? ", ResourceAccessException(网络超时)" : ""
        );

        return retryTemplate;
    }

    /**
     * 按配置构建 RetryTemplate（包级可见，便于单测直接构造，不启动 Spring）。
     */
    static RetryTemplate build(ModelRetryProperties properties) {
        RetryTemplateBuilder builder = RetryTemplate.builder()
                .maxAttempts(safeMaxAttempts(properties))
                .retryOn(TransientAiException.class)
                .customBackoff(backOffPolicy(properties))
                .withListener(new ModelRetryLoggingListener());

        if (properties.isRetryOnTimeout()) {
            builder.retryOn(ResourceAccessException.class);
        }

        RetryTemplate retryTemplate = builder.build();

        /*
         * 重试耗尽后抛出"最后一次的真实异常"，而不是 ExhaustedRetryException 包装。
         *
         * 原因：ToolCallAgent 需要根据异常类型决定 ERROR 文案
         * （NonTransient → 检查配置/额度；Transient → 稍后重试），
         * 被包装成 ExhaustedRetryException 会让上层只能靠猜。
         */
        retryTemplate.setThrowLastExceptionOnExhausted(true);

        return retryTemplate;
    }

    /**
     * 指数退避策略（对外暴露便于断言"不是 FixedBackOffPolicy"）。
     */
    static BackOffPolicy backOffPolicy(ModelRetryProperties properties) {
        ExponentialBackOffPolicy policy = new ExponentialBackOffPolicy();
        policy.setInitialInterval(safeInitialBackoff(properties).toMillis());
        policy.setMultiplier(safeMultiplier(properties));
        policy.setMaxInterval(safeMaxBackoff(properties).toMillis());
        return policy;
    }

    private static int safeMaxAttempts(ModelRetryProperties properties) {
        // 至少调用一次；上限保护，避免配置写错后出现"无限近似"的长等待。
        return Math.max(1, Math.min(properties.getMaxAttempts(), 10));
    }

    private static java.time.Duration safeInitialBackoff(ModelRetryProperties properties) {
        java.time.Duration value = properties.getInitialBackoff();
        if (value == null || value.isNegative() || value.isZero()) {
            return java.time.Duration.ofMillis(1);
        }
        return value;
    }

    private static double safeMultiplier(ModelRetryProperties properties) {
        // 指数退避要求 multiplier >= 1，否则退避时间会递减。
        return Math.max(1.0d, properties.getMultiplier());
    }

    private static java.time.Duration safeMaxBackoff(ModelRetryProperties properties) {
        java.time.Duration max = properties.getMaxBackoff();
        java.time.Duration initial = safeInitialBackoff(properties);

        if (max == null || max.compareTo(initial) < 0) {
            return initial;
        }
        return max;
    }
}
