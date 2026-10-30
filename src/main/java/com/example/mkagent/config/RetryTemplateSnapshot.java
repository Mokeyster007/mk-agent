package com.example.mkagent.config;

import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 运行时重试策略快照。
 *
 * 为什么需要反射：
 * `RetryTemplate` 只有 setter，没有 `getRetryPolicy()` / `getBackOffPolicy()`，
 * 想确认"某个模型实际持有的是哪套策略"只能读私有字段。
 * 这个类把反射收敛在一处，供启动诊断与测试共用：
 *
 * 1. 启动时打印"容器里有哪些 RetryTemplate Bean、ChatModel 实际持有哪一个"；
 * 2. 测试里断言"对话模型的重试策略不是 FixedBackOffPolicy、400 不会被重试"。
 *
 * 反射失败不抛异常：诊断代码不应该让应用启动失败。
 */
public final class RetryTemplateSnapshot {

    private static final String UNKNOWN = "未知";

    private final int maxAttempts;

    private final List<String> retryOn;

    private final String backOffDescription;

    private final boolean fixedBackOff;

    private RetryTemplateSnapshot(
            int maxAttempts,
            List<String> retryOn,
            String backOffDescription,
            boolean fixedBackOff
    ) {
        this.maxAttempts = maxAttempts;
        this.retryOn = List.copyOf(retryOn);
        this.backOffDescription = backOffDescription;
        this.fixedBackOff = fixedBackOff;
    }

    public static RetryTemplateSnapshot of(RetryTemplate template) {
        if (template == null) {
            return new RetryTemplateSnapshot(-1, List.of(), UNKNOWN, false);
        }

        RetryPolicy policy = readField(template, "retryPolicy", RetryPolicy.class);
        BackOffPolicy backOffPolicy = readField(
                template, "backOffPolicy", BackOffPolicy.class);

        return new RetryTemplateSnapshot(
                readMaxAttempts(policy),
                readRetryableExceptions(policy),
                describeBackOff(backOffPolicy),
                backOffPolicy instanceof FixedBackOffPolicy
        );
    }

    /**
     * 最多尝试次数（含首次调用）；读不到返回 -1。
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 会被重试的异常类名（简单名）；读不到返回空列表。
     */
    public List<String> getRetryOn() {
        return retryOn;
    }

    public String getBackOffDescription() {
        return backOffDescription;
    }

    /**
     * 是否使用了固定间隔退避。
     *
     * 本项目期望 false：交互式 Agent 不接受"每 15 秒重试一次、连续 8 次"这种策略。
     */
    public boolean isFixedBackOff() {
        return fixedBackOff;
    }

    @Override
    public String toString() {
        return "maxAttempts=" + (maxAttempts > 0 ? maxAttempts : UNKNOWN)
                + ", retryOn=" + (retryOn.isEmpty() ? UNKNOWN : retryOn)
                + ", backoff=" + backOffDescription;
    }

    // ==================== 反射读取 ====================

    private static int readMaxAttempts(RetryPolicy policy) {
        if (policy == null) {
            return -1;
        }

        Integer direct = readIntField(policy, "maxAttempts");
        if (direct != null) {
            return direct;
        }

        // RetryTemplateBuilder 会包一层 CompositeRetryPolicy，真正的策略在 policies 数组里。
        RetryPolicy[] policies = readField(policy, "policies", RetryPolicy[].class);
        if (policies != null) {
            for (RetryPolicy child : policies) {
                Integer value = readIntField(child, "maxAttempts");
                if (value != null) {
                    return value;
                }
            }
        }
        return -1;
    }

    private static List<String> readRetryableExceptions(RetryPolicy policy) {
        Set<String> result = new LinkedHashSet<>();
        collectRetryableExceptions(policy, result, 0);
        return new ArrayList<>(result);
    }

    private static void collectRetryableExceptions(
            RetryPolicy policy,
            Set<String> result,
            int depth
    ) {
        if (policy == null || depth > 3) {
            return;
        }

        RetryPolicy[] policies = readField(policy, "policies", RetryPolicy[].class);
        if (policies != null) {
            for (RetryPolicy child : policies) {
                collectRetryableExceptions(child, result, depth + 1);
            }
        }

        /*
         * BinaryExceptionClassifierRetryPolicy / SimpleRetryPolicy
         * 都把"异常类型 → 是否重试"放在一个 Map<Class<?>, Boolean> 里。
         */
        for (String fieldName : List.of(
                "exceptionClassifier",
                "retryableClassifier",
                "retryableExceptions"
        )) {
            Object holder = readField(policy, fieldName, Object.class);
            if (holder == null) {
                continue;
            }
            /*
             * SubclassClassifier（BinaryExceptionClassifier 的父类）把
             * "异常类型 → 是否重试" 存在 classified 字段里；
             * 老版本 / 其它实现可能叫 classifier 或 retryableExceptions。
             */
            Map<?, ?> map = readField(holder, "classified", Map.class);
            if (map == null) {
                map = readField(holder, "classifier", Map.class);
            }
            if (map == null) {
                map = readField(holder, "retryableExceptions", Map.class);
            }
            if (map == null) {
                continue;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof Class<?> type
                        && Boolean.TRUE.equals(entry.getValue())) {
                    result.add(type.getSimpleName());
                }
            }
        }
    }

    private static String describeBackOff(BackOffPolicy backOffPolicy) {
        if (backOffPolicy == null) {
            return UNKNOWN;
        }
        if (backOffPolicy instanceof ExponentialBackOffPolicy exponential) {
            return String.format(
                    Locale.ROOT,
                    "ExponentialBackOffPolicy(initial=%dms, multiplier=%.1f, max=%dms)",
                    exponential.getInitialInterval(),
                    exponential.getMultiplier(),
                    exponential.getMaxInterval()
            );
        }
        if (backOffPolicy instanceof FixedBackOffPolicy fixed) {
            return "FixedBackOffPolicy(period=" + fixed.getBackOffPeriod() + "ms)";
        }
        if (backOffPolicy instanceof NoBackOffPolicy) {
            return "NoBackOffPolicy";
        }
        return backOffPolicy.getClass().getSimpleName();
    }

    private static <T> T readField(Object target, String name, Class<T> type) {
        Field field = ReflectionUtils.findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            ReflectionUtils.makeAccessible(field);
            Object value = field.get(target);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (RuntimeException | IllegalAccessException e) {
            return null;
        }
    }

    private static Integer readIntField(Object target, String name) {
        Object value = readField(target, name, Object.class);
        return value instanceof Integer number ? number : null;
    }

    /**
     * 调试用：把一组 Bean 名与快照拼成一行。
     */
    public static String describeAll(Map<String, RetryTemplate> beans) {
        if (beans == null || beans.isEmpty()) {
            return "（无）";
        }
        List<String> lines = new ArrayList<>();
        beans.forEach((name, template) -> lines.add(
                name + " -> " + of(template)
        ));
        return String.join("; ", lines);
    }

    /**
     * 判断集合里是否包含某个实例（按引用比较，用于确认模型持有的是哪个 Bean）。
     */
    public static String nameOf(
            Map<String, RetryTemplate> beans,
            RetryTemplate target
    ) {
        for (Map.Entry<String, RetryTemplate> entry : beans.entrySet()) {
            if (entry.getValue() == target) {
                return entry.getKey();
            }
        }
        return "";
    }
}
