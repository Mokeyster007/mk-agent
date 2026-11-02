package com.example.mkagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionUtils;
import com.alibaba.cloud.ai.autoconfigure.dashscope.ResolvedConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Set;

/**
 * 显式构造对话模型（DashScope 原生客户端），把重试策略钉死在模型实例上。
 *
 * 生效条件：没有配置 `mkagent.model.base-url`（即没切到 OpenAI 兼容客户端）。
 * 配了 base-url 时本类整体让位给 {@link OpenAiCompatibleChatModelConfig}，
 * 保证容器里任何时刻只有一个对话模型。
 *
 * 注：`DashScopeChatAutoConfiguration` 已在 application.yml 中被排除，
 * 模型装配完全由项目代码负责——否则第三方自动配置会按"类型是否缺失"决定要不要
 * 再造一个模型，与"谁来决定重试模板"纠缠在一起。因此这里需要自己声明
 * `@EnableConfigurationProperties`，把那两个属性类注册回来。
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
@Conditional(OpenAiCompatibleConditions.Disabled.class)
@EnableConfigurationProperties({DashScopeChatProperties.class, DashScopeConnectionProperties.class})
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

        /*
         * 先清空默认选项里的工具，再交给模型。
         * 这是"预算收尾调用不带工具"唯一可靠的保证点，原因见 stripDefaultTools。
         */
        DashScopeChatOptions defaultOptions = chatProperties.getOptions();
        stripDefaultTools(defaultOptions);

        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(defaultOptions)
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

    /**
     * 清空模型默认选项里的工具定义。
     *
     * ================== 为什么必须清 ==================
     *
     * DashScopeChatModel#buildRequestPrompt 在合并完选项后会执行：
     * <pre>
     * merged.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(
     *         runtime.getToolCallbacks(), defaultOptions.getToolCallbacks()));
     * merged.setToolNames(ToolCallingChatOptions.mergeToolNames(
     *         runtime.getToolNames(), defaultOptions.getToolNames()));
     * </pre>
     * 而这两个方法的实现是"runtime 为空就返回 defaults"：
     * <pre>
     * if (CollectionUtils.isEmpty(runtimeToolCallbacks)) {
     *     return new ArrayList<>(defaultToolCallbacks);
     * }
     * </pre>
     *
     * 也就是说：**只要 defaultOptions 带工具，任何一次 Prompt 都会把工具
     * 重新合并进请求**——包括预算耗尽后那次"不带工具"的收尾调用。
     * 在 Prompt 侧把 toolCallbacks 置空是拦不住的（空列表恰好触发回退到 defaults）。
     *
     * ================== 原地修改配置 Bean ==================
     *
     * {@code chatProperties.getOptions()} 返回的是属性对象本身（字节码就是
     * {@code getfield + areturn}，没有拷贝），所以这里是对配置 Bean 做**原地修改**。
     *
     * 本项目读这个对象的只有本配置类和 {@code ConfiguredModelNameResolver}
     * （只读 {@code model}，不碰工具字段），所以清理不会影响其他调用方。
     *
     * 这是**有意为之**：本项目的工具一律由 Agent 白名单在 Prompt 级注入
     * （{@code ToolRegistryConfig}），不再支持通过模型 defaults 注册工具。
     * 如果将来需要恢复"模型默认带工具"的用法，要先解决收尾请求的隔离问题。
     *
     * ================== 为什么可以清 ==================
     *
     * 清空对常规工具轮**零影响**（常规轮 runtime 非空，merge 直接采用 runtime）。
     *
     * ================== 只清 callbacks/names 不够 ==================
     *
     * {@code DashScopeChatOptions} 里有四个与工具相关的字段：
     * <pre>
     * toolCallbacks / toolNames  → 由 ToolCallingManager 解析成请求里的 tools
     * tools                      → 已解析好的 DashScope FunctionTool 列表，直接进请求
     * toolChoice                 → 直接进请求
     * </pre>
     * {@code buildRequestPrompt} 先做一次 ModelOptionsUtils.merge（runtime 为 null
     * 的字段回落到 defaults），{@code createRequest} 只在"解析出的工具非空"时
     * 才覆盖 tools，所以默认选项里的 tools / toolChoice 会**原样带进收尾请求**。
     * 因此这里四个字段一起清。
     *
     * tools 置 null 而不是空列表：ChatCompletionRequestParameter 标了
     * {@code @JsonInclude(NON_NULL)}，null 表示"请求里没有这个字段"，
     * 空列表会序列化成 {@code "tools": []}，是另一种线上形状。
     *
     * 真的清掉了东西时告警，避免"配置了却静默失效"。
     */
    static void stripDefaultTools(DashScopeChatOptions options) {
        if (options == null) {
            return;
        }

        boolean hadCallbacks = options.getToolCallbacks() != null
                && !options.getToolCallbacks().isEmpty();
        boolean hadNames = options.getToolNames() != null
                && !options.getToolNames().isEmpty();
        boolean hadTools = options.getTools() != null
                && !options.getTools().isEmpty();
        boolean hadChoice = options.getToolChoice() != null;

        if (!hadCallbacks && !hadNames && !hadTools && !hadChoice) {
            return;
        }

        options.setToolCallbacks(List.of());
        options.setToolNames(Set.of());
        options.setTools(null);
        options.setToolChoice(null);

        log.warn(
                "检测到 spring.ai.dashscope.chat.options 配置了工具，已清空："
                        + "toolCallbacks={}, toolNames={}, tools={}, toolChoice={}。"
                        + "工具必须由 Agent 白名单按 Prompt 注入；"
                        + "默认选项带工具会让预算收尾调用重新拿到工具。",
                hadCallbacks,
                hadNames,
                hadTools,
                hadChoice
        );
    }
}
