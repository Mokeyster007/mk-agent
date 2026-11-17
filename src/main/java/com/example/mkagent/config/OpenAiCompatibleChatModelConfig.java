package com.example.mkagent.config;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Locale;

/**
 * OpenAI 兼容接口的按用途模型装配（agent / auxiliary / vision）。
 *
 * 启用条件：配了 `mkagent.model.base-url`。
 * 没配时本类整体不生效，继续由 {@link DashScopeChatModelConfig} 提供对话模型，
 * 现有部署行为完全不变。
 *
 * ==================== 一条硬约束：私有 RetryTemplate ====================
 *
 * 每个模型实例都**自己构建**一份 {@link RetryTemplate}（{@code ModelRetryConfig.build}），
 * 绝不注入容器里的同名 Bean。原因是一次真实事故：
 * spring-ai-alibaba 的 `DashScopeImageModel` 构造器会对传进来的 `RetryTemplate`
 * 原地调用 `setRetryPolicy(new SimpleRetryPolicy(10))` 与
 * `setBackOffPolicy(fixedBackOff(15000))`，把共享实例改成"10 次 + 固定 15 秒 + 连
 * `Exception` 都重试"。于是只要容器里存在一个共享模板，任何"借用即改写"的库代码
 * 都能悄悄改掉别处的重试策略，而启动日志仍然打印着正确值。
 *
 * 所以这里的原则是：**模型实例与容器 Bean 之间不共享任何可变对象。**
 * 三个模型因此有**三个不同的 RetryTemplate 实例**（不是同一个对象的三个引用），
 * 对应测试见 OpenAiChatModelWiringTest / OpenAiChatModelFallbackTest。
 *
 * ==================== 关于 Bean 名 ====================
 *
 * agent 模型的 Bean 名保留为 `dashscopeChatModel`，尽管它的实现已经换成
 * OpenAI 兼容客户端。这是刻意的：
 * 1. 现有 11 个测试类按这个 Bean 名覆盖模型，10 处按类型注入 `ChatModel`；
 * 2. 运维侧看板/配置里引用的也是这个坐标。
 * 名字是历史包袱，实现由配置决定——启动日志会打印实际实现类与模型名，
 * 不要靠 Bean 名判断用的是哪家。
 */
@Configuration
@EnableConfigurationProperties(OpenAiCompatibleModelProperties.class)
@Conditional(OpenAiCompatibleConditions.Enabled.class)
public class OpenAiCompatibleChatModelConfig {

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiCompatibleChatModelConfig.class);

    /**
     * 用途标识，只用于日志与错误信息。
     */
    private static final String PURPOSE_AGENT = "agent";

    private static final String PURPOSE_AUXILIARY = "auxiliary";

    private static final String PURPOSE_VISION = "vision";

    private final OpenAiCompatibleModelProperties properties;

    private final ModelRetryProperties retryProperties;

    private final ToolCallingManager toolCallingManager;

    private final ResponseErrorHandler responseErrorHandler;

    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;

    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;

    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    private final ObjectProvider<ToolExecutionEligibilityPredicate> eligibilityPredicateProvider;

    public OpenAiCompatibleChatModelConfig(
            OpenAiCompatibleModelProperties properties,
            ModelRetryProperties retryProperties,
            ToolCallingManager toolCallingManager,
            ResponseErrorHandler responseErrorHandler,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ToolExecutionEligibilityPredicate> eligibilityPredicateProvider
    ) {
        this.properties = properties;
        this.retryProperties = retryProperties;
        this.toolCallingManager = toolCallingManager;
        this.responseErrorHandler = responseErrorHandler;
        this.restClientBuilderProvider = restClientBuilderProvider;
        this.webClientBuilderProvider = webClientBuilderProvider;
        this.observationRegistryProvider = observationRegistryProvider;
        this.eligibilityPredicateProvider = eligibilityPredicateProvider;
    }

    /**
     * 主推理模型。
     *
     * Bean 名保持 `dashscopeChatModel`（见类注释）；
     * `@Primary` 让所有按类型注入 `ChatModel` 的地方（Agent、RAG、健康检查…）
     * 继续无歧义地拿到它，而不必逐个改 `@Qualifier`。
     */
    @Bean("dashscopeChatModel")
    @Primary
    public ChatModel dashscopeChatModel() {
        return build(PURPOSE_AGENT, properties.getAgent());
    }

    /**
     * 辅助模型：评测打分、查询改写这类短调用。
     */
    @Bean("auxiliaryChatModel")
    public ChatModel auxiliaryChatModel() {
        return build(PURPOSE_AUXILIARY, properties.getAuxiliary());
    }

    /**
     * 多模态模型：看图。
     *
     * 当前没有调用方（图片检索走 MCP 工具，不直接调模型），
     * 先把装配与隔离性做好，接入时只需按名注入。
     */
    @Bean("visionChatModel")
    public ChatModel visionChatModel() {
        return build(PURPOSE_VISION, properties.getVision());
    }

    // ==================== 装配 ====================

    private ChatModel build(String purpose, OpenAiCompatibleModelProperties.ModelSpec spec) {
        Assert.hasText(
                properties.getApiKey(),
                "未配置模型凭据：启用 OpenAI 兼容客户端（mkagent.model.base-url）时，"
                        + "必须同时提供 mkagent.model.api-key。"
                        + "建议设置环境变量 MKAGENT_MODEL_API_KEY——"
                        + "classpath 上的 application-local.yml 已把该属性绑定成 "
                        + "${MKAGENT_MODEL_API_KEY:}；"
                        + "若在 config/application-local.yml 里覆盖，请照抄同一写法。"
                        + "详见 docs/SECURITY.md 第 7 节。"
        );

        String modelName = resolveModelName(purpose, spec);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(restClientBuilderProvider
                        .getIfAvailable(RestClient::builder))
                .webClientBuilder(webClientBuilderProvider
                        .getIfAvailable(WebClient::builder))
                .responseErrorHandler(responseErrorHandler)
                .build();

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(modelName);

        if (spec.getTemperature() != null) {
            options.temperature(spec.getTemperature());
        }
        if (spec.getMaxTokens() != null) {
            options.maxTokens(spec.getMaxTokens());
        }

        /*
         * 私有实例：每个模型一份，不与容器 Bean、也不与其它模型共享。
         * 这是本类最重要的不变量，改动前请先读类注释里的事故说明。
         */
        RetryTemplate retryTemplate = ModelRetryConfig.build(retryProperties);

        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options.build())
                .retryTemplate(retryTemplate)
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistryProvider
                        .getIfAvailable(() -> ObservationRegistry.NOOP))
                .toolExecutionEligibilityPredicate(eligibilityPredicateProvider
                        .getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
                .build();

        log.info(
                "OpenAI 兼容模型已构造：purpose={}, model={}, baseUrl={}, retry={}",
                purpose,
                modelName,
                properties.getBaseUrl(),
                RetryTemplateSnapshot.of(retryTemplate)
        );

        return model;
    }

    /**
     * 解析某个用途实际使用的模型名。
     *
     * 回退只发生在**名字**层面：auxiliary / vision 没配名字就用 agent 的名字，
     * 但仍然是独立客户端、独立 RetryTemplate —— 不共享实例。
     */
    private String resolveModelName(
            String purpose,
            OpenAiCompatibleModelProperties.ModelSpec spec
    ) {
        if (StringUtils.hasText(spec.getName())) {
            return spec.getName();
        }

        String agentName = properties.getAgent().getName();
        if (!StringUtils.hasText(agentName)) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "mkagent.model.%s.name 与 mkagent.model.agent.name 均为空，无法确定模型名",
                    purpose
            ));
        }

        log.info(
                "mkagent.model.{}.name 未配置，回退到 agent 模型名：{}（实例仍独立）",
                purpose,
                agentName
        );

        return agentName;
    }
}
