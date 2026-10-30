package com.example.mkagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时的重试策略自检。
 *
 * 为什么需要它：
 * "容器里有一个 RetryTemplate Bean" 不等于 "模型用的就是它"。
 * 曾经出现过这样的情况：启动日志里明明打印了
 * `maxAttempts=3, exponential, NonTransientAiException=不重试`，
 * 但真实请求的 400 仍然被重试了 8 次、每次间隔十几秒。
 * 只看配置、不看实例，就会得出错误结论。
 *
 * 所以这里在启动后做三件事：
 * 1. 列出容器里所有 `RetryTemplate` Bean 及其真实策略；
 * 2. 用反射读出 `DashScopeChatModel` 实际持有的 `RetryTemplate`，并说明它是不是容器里的 Bean；
 * 3. 如果模型持有的是 `FixedBackOffPolicy`（典型的"每次等十几秒"），
 *    或者环境里存在显式的 `spring.ai.retry.*` 覆盖，直接 WARN 出来。
 *
 * 根因备注：`FixedBackOffPolicy + 10 次` 并不是 Spring AI 的默认值，
 * 而是 `DashScopeImageModel` 构造器原地改写共享 `RetryTemplate` 的结果
 * （详见 ModelRetryConfig / DashScopeChatModelConfig 的类注释）。
 * 因此本类在 WARN 里直接点名这个已知成因，避免下次又从零开始排查。
 *
 * 本类只读不写，任何异常都不影响启动。
 */
@Component
public class ModelRetryDiagnostics implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(ModelRetryDiagnostics.class);

    /**
     * DashScope 模型里保存重试模板的字段名（public final）。
     */
    private static final String MODEL_RETRY_FIELD = "retryTemplate";

    private final ApplicationContext applicationContext;

    private final Environment environment;

    private final ChatModel chatModel;

    public ModelRetryDiagnostics(
            ApplicationContext applicationContext,
            Environment environment,
            ChatModel chatModel
    ) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.chatModel = chatModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        /*
         * 三个步骤各自兜底：任何一步抛异常（含反射失败、Bean 实例化失败）
         * 都只记一条 WARN，绝不向外抛。
         *
         * 为什么必须这么写：本类是 ApplicationRunner，run() 抛出的异常会让
         * SpringApplication.run 失败并关闭上下文——诊断代码反过来把应用搞挂，
         * 比不做诊断还糟。这里连 LinkageError 也一并兜住（可选依赖缺失时可能出现）。
         */
        Map<String, RetryTemplate> beans = new LinkedHashMap<>();

        runStep("枚举 RetryTemplate Bean", () -> {
            beans.putAll(applicationContext.getBeansOfType(RetryTemplate.class));
            log.info(
                    "重试策略自检：容器内 RetryTemplate Bean 数量={}, 明细=[{}]",
                    beans.size(),
                    RetryTemplateSnapshot.describeAll(beans)
            );
        });

        runStep("校验模型实际持有的模板", () -> checkModelRetryTemplate(beans));
        runStep("检查 spring.ai.retry 覆盖", this::warnOnSpringAiRetryOverrides);
    }

    /**
     * 执行一步自检；失败只 WARN，不抛出。
     */
    private void runStep(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            log.warn(
                    "重试策略自检「{}」失败，已忽略（不影响启动）：{}",
                    step,
                    e.toString()
            );
        }
    }

    /**
     * 逐个确认容器里每个对话模型实际持有的重试模板。
     *
     * 为什么是"逐个"而不是"看那个唯一的 ChatModel"：
     * 按用途分层之后，容器里会有 agent / auxiliary / vision 多个模型，
     * 每个都必须持有自己的私有模板。只抽查一个，等于放过了另外两个。
     */
    private void checkModelRetryTemplate(Map<String, RetryTemplate> retryBeans) {
        Map<String, ChatModel> models = new LinkedHashMap<>(
                applicationContext.getBeansOfType(ChatModel.class));

        if (models.isEmpty()) {
            /*
             * 兜底：单元测试里模型往往不是注册的 Bean，而是直接构造传入。
             * 此时至少把注入进来的这个检查一遍。
             */
            models.put(
                    chatModel.getClass().getSimpleName() + "（注入实例）",
                    chatModel
            );
        }

        models.forEach((name, model) -> inspectModel(name, model, retryBeans));
    }

    private void inspectModel(
            String name,
            ChatModel model,
            Map<String, RetryTemplate> retryBeans
    ) {
        RetryTemplate held = readRetryTemplate(model);
        if (held == null) {
            log.info(
                    "重试策略自检：{} 未暴露 retryTemplate 字段，跳过实例级校验",
                    name
            );
            return;
        }

        RetryTemplateSnapshot snapshot = RetryTemplateSnapshot.of(held);
        String beanName = RetryTemplateSnapshot.nameOf(retryBeans, held);

        log.info(
                "重试策略自检：{} 实际持有的 RetryTemplate = [{}]，来源={}",
                name,
                snapshot,
                beanName.isEmpty()
                        ? "模型私有实例（预期：与容器 Bean 隔离，库代码改不到）"
                        : "Bean:" + beanName
        );

        if (snapshot.isFixedBackOff()) {
            log.warn(
                    "重试策略自检异常：{} 正在使用 FixedBackOffPolicy（{}）。"
                            + "已知成因：spring-ai-alibaba 的 DashScopeImageModel 构造器会原地改写"
                            + "传进去的 RetryTemplate（setRetryPolicy(SimpleRetryPolicy(10)) + "
                            + "FixedBackOffPolicy(15000ms)），从而连 400 都会被重试。"
                            + "对话模型应持有私有模板（见 DashScopeChatModelConfig）。",
                    name,
                    snapshot.getBackOffDescription()
            );
        }

        if (snapshot.getMaxAttempts() > 5) {
            log.warn(
                    "重试策略自检异常：{} 的 maxAttempts={}，交互式 Agent 不应重试这么多次",
                    name,
                    snapshot.getMaxAttempts()
            );
        }
    }

    /**
     * 读取模型内部的 RetryTemplate；读不到返回 null。
     *
     * 沿类继承链查找 `getDeclaredField`：`DashScopeChatModel` 的字段是
     * `public final`，而 `OpenAiChatModel` 的是 `private final`，
     * 只认 public 会漏掉后者。
     */
    private RetryTemplate readRetryTemplate(ChatModel model) {
        Class<?> type = model.getClass();

        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(MODEL_RETRY_FIELD);
                field.setAccessible(true);
                Object value = field.get(model);
                return value instanceof RetryTemplate template ? template : null;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        return null;
    }

    /**
     * 环境里若存在显式的 spring.ai.retry.* 覆盖，说明"另一套重试参数"仍在生效范围里，
     * 需要提示排查（这套参数只影响 Spring AI 自动配置的 RetryTemplate）。
     */
    private void warnOnSpringAiRetryOverrides() {
        List<String> overrides = new ArrayList<>();

        addIfPresent(overrides, "spring.ai.retry.max-attempts");
        addIfPresent(overrides, "spring.ai.retry.backoff.initial-interval");
        addIfPresent(overrides, "spring.ai.retry.backoff.multiplier");
        addIfPresent(overrides, "spring.ai.retry.backoff.max-interval");
        addIfPresent(overrides, "spring.ai.retry.on-client-errors");

        if (!overrides.isEmpty()) {
            log.warn(
                    "检测到显式的 spring.ai.retry.* 配置：{}。"
                            + "这套参数只作用于 Spring AI 自动配置的 RetryTemplate；"
                            + "本项目对话模型使用 mkagent.model-retry.*，"
                            + "如两边不一致请清理本地配置，避免排查时被误导。",
                    overrides
            );
        }
    }

    private void addIfPresent(List<String> target, String key) {
        String value = environment.getProperty(key);
        if (value != null && !value.isBlank()) {
            target.add(key + "=" + value);
        }
    }
}
