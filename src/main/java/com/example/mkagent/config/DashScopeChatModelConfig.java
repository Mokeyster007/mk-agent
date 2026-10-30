package com.example.mkagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionUtils;
import com.alibaba.cloud.ai.autoconfigure.dashscope.ResolvedConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 显式构造对话模型，把重试策略钉死在模型实例上。
 *
 * 背景（一次真实的线上问题，根因已用反编译确认）：
 * 只在容器里放一个 `RetryTemplate` Bean、指望 DashScope 自动配置把它注入给
 * `DashScopeChatModel`，是不够的。因为 spring-ai-alibaba 的 `DashScopeImageModel`
 * 构造器会**原地改写**传进去的 `RetryTemplate`：
 *
 * <pre>
 * retryTemplate.setRetryPolicy(new SimpleRetryPolicy(10));
 * FixedBackOffPolicy fixed = new FixedBackOffPolicy();
 * fixed.setBackOffPeriod(15000L);
 * retryTemplate.setBackOffPolicy(fixed);
 * </pre>
 *
 * 容器里那个"唯一的"模板被图片模型改完之后，与对话模型共享同一个对象的策略
 * 就变成了"10 次 + 固定 15 秒 + 连 Exception 都重试"，于是 400
 * （NonTransientAiException）也被重试了 8 次、每次间隔 15 秒，
 * 用户看到的是"参数错误但请求一直不返回"。启动日志却仍然打印 maxAttempts=3，
 * 因为它打印的是构建时的快照，而对象后来被改了。
 *
 * 所以这里不再依赖"自动配置恰好注入到我们的 Bean"，而是：
 * 1. 自己按 `mkagent.model-retry.*` 构建 `RetryTemplate`（与容器 Bean 同一套参数）；
 * 2. 通过 `DashScopeChatModel.Builder#retryTemplate(...)` 显式交给模型；
 * 3. 模型 Bean 与自动配置的 `dashscopeChatModel` 同名，自动配置上的
 *    `@ConditionalOnMissingBean` 会整体让位，容器里只会有一个 DashScope 对话模型。
 *
 * 其余参数（api-key、base-url、workspace、headers、chat options、观察注册表）
 * 与 `DashScopeChatAutoConfiguration#dashscopeChatModel` 保持完全一致，
 * 避免"为了改重试，把别的配置改坏了"。
 *
 * 注：这里刻意不注入容器里的 `RetryTemplate` Bean，而是从属性重新构建。
 * 模型持有的是一个私有实例，任何库代码都改不到它；
 * 即使将来容器里再出现同类型 Bean，模型的重试策略也不会被悄悄替换。
 */
@Configuration
public class DashScopeChatModelConfig {

    private static final Logger log =
            LoggerFactory.getLogger(DashScopeChatModelConfig.class);

    /**
     * DashScope 对话模型（显式构造，重试策略固定为 mkagent.model-retry.*）。
     */
    @Bean
    public DashScopeChatModel dashscopeChatModel(
            ModelRetryProperties retryProperties,
            DashScopeConnectionProperties connectionProperties,
            DashScopeChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ToolExecutionEligibilityPredicate> eligibilityPredicateProvider,
            ResponseErrorHandler responseErrorHandler
    ) {
        ResolvedConnectionProperties resolved =
                DashScopeConnectionUtils.resolveConnectionProperties(
                        connectionProperties,
                        chatProperties,
                        "chat"
                );

        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(resolved.apiKey())
                .headers(resolved.headers())
                .baseUrl(resolved.baseUrl())
                .webClientBuilder(webClientBuilderProvider
                        .getIfAvailable(WebClient::builder))
                .workSpaceId(resolved.workspaceId())
                .restClientBuilder(restClientBuilderProvider
                        .getIfAvailable(RestClient::builder))
                .responseErrorHandler(responseErrorHandler)
                .build();

        /*
         * 关键：由 mkagent.model-retry.* 直接构建，不经过容器注入。
         * 语义固定为"只重试 TransientAiException（5xx/429）与网络超时，
         * 400/401/403 立即失败"。
         */
        RetryTemplate retryTemplate = ModelRetryConfig.build(retryProperties);

        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(chatProperties.getOptions())
                .retryTemplate(retryTemplate)
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistryProvider
                        .getIfAvailable(() -> ObservationRegistry.NOOP))
                .toolExecutionEligibilityPredicate(eligibilityPredicateProvider
                        .getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
                .build();

        log.info(
                "DashScopeChatModel 已显式构造：model={}, retry={}",
                chatProperties.getOptions() == null
                        ? "（未配置 options）"
                        : chatProperties.getOptions().getModel(),
                RetryTemplateSnapshot.of(retryTemplate)
        );

        return chatModel;
    }
}
