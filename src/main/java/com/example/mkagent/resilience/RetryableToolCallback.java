package com.example.mkagent.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 只读幂等工具的重试装饰器（装饰器模式，不修改原工具）。
 *
 * 适用范围（严格白名单）：
 * 只包装 web_search / web_scrape 这类"安全、只读、幂等"的工具；
 * file_operation / resource_download / pdf_generation 等有副作用的
 * 高风险工具绝不包装，避免重复写文件、重复下载等不可逆后果。
 *
 * 重试策略：
 * 1. 有限次数（maxAttempts 含首次），绝不无限重试；
 * 2. 指数退避：第 n 次失败后等待
 *    initialBackoff * multiplier^(n-1)；
 * 3. 两类失败都触发重试：
 *    - 工具抛出异常；
 *    - 工具内部吞掉异常后返回失败文案
 *      （如 "搜索工具执行失败"，通过 failureMarkers 识别）。
 *      注意：MethodToolCallback 会把工具返回值序列化为 JSON 字符串
 *      （外层带双引号），因此用 contains 而非 startsWith 匹配；
 * 4. 任务被取消（线程中断）时立即停止重试并向上抛出；
 * 5. 记录每次重试与最终状态（重试次数计数器 + 日志）。
 */
public class RetryableToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(RetryableToolCallback.class);

    private final ToolCallback delegate;

    private final int maxAttempts;

    private final Duration initialBackoff;

    private final double multiplier;

    /**
     * 失败文案关键词：工具返回内容包含任一关键词时视为失败。
     */
    private final List<String> failureMarkers;

    /**
     * 本工具累计重试次数（供测试与监控断言）。
     */
    private final AtomicInteger retryCount = new AtomicInteger(0);

    /**
     * 全局累计重试次数（静态，跨工具汇总，供测试断言）。
     */
    private static final AtomicLong GLOBAL_RETRY_COUNT = new AtomicLong(0);

    public RetryableToolCallback(
            ToolCallback delegate,
            int maxAttempts,
            Duration initialBackoff,
            double multiplier,
            List<String> failureMarkers
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "maxAttempts 必须 >= 1：" + maxAttempts
            );
        }
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.failureMarkers = failureMarkers == null
                ? List.of()
                : List.copyOf(failureMarkers);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // 工具定义完全透传：名称 / 描述 / 参数不变，模型无感知。
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return callWithRetry(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return callWithRetry(() -> delegate.call(toolInput, toolContext));
    }

    /**
     * 指数退避重试核心逻辑。
     */
    private String callWithRetry(ToolInvocation invocation) {
        String toolName = getToolDefinition().name();
        RuntimeException lastFailure = null;
        long backoffMillis = initialBackoff.toMillis();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            /*
             * 任务取消检测：中断后不再重试，立即向上抛出，
             * 让 Agent Loop 走 CANCELLED 终态。
             */
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException(
                        new InterruptedException("工具重试被任务取消中断：" + toolName)
                );
            }

            try {
                String result = invocation.invoke();

                if (!isFailureResult(result)) {
                    if (attempt > 1) {
                        log.info(
                                "工具重试成功：tool={}, attempt={}/{}, totalRetries={}",
                                toolName, attempt, maxAttempts, retryCount.get()
                        );
                    }
                    return result;
                }

                // 工具内部吞异常返回失败文案，与抛异常同等对待。
                lastFailure = new RuntimeException(
                        "工具返回失败结果：" + truncate(result)
                );

            } catch (RuntimeException e) {
                lastFailure = e;
            }

            retryCount.incrementAndGet();
            GLOBAL_RETRY_COUNT.incrementAndGet();

            log.warn(
                    "工具执行失败，准备重试：tool={}, attempt={}/{}, backoff={}ms, reason={}",
                    toolName, attempt, maxAttempts, backoffMillis,
                    lastFailure.getMessage()
            );

            // 最后一次尝试失败后不再等待，直接抛出。
            if (attempt == maxAttempts) {
                break;
            }

            sleepBeforeRetry(backoffMillis);
            backoffMillis = (long) (backoffMillis * multiplier);
        }

        log.error(
                "工具重试耗尽仍失败：tool={}, maxAttempts={}, totalRetries={}, finalReason={}",
                toolName, maxAttempts, retryCount.get(),
                lastFailure == null ? "unknown" : lastFailure.getMessage()
        );

        /*
         * 向上抛出：DefaultToolCallingManager 会把它转成
         * 模型可见的错误文本，与项目现有失败语义保持一致。
         */
        throw lastFailure;
    }

    /**
     * 判断工具返回文本是否为失败文案（按配置的关键词包含匹配）。
     *
     * 用 contains 而非 startsWith：@Tool 方法经由 MethodToolCallback
     * 执行时，返回值会被序列化为 JSON 字符串（外层包裹双引号），
     * 失败文案不会出现在字符串最开头。
     */
    private boolean isFailureResult(String result) {
        if (result == null) {
            return false;
        }
        return failureMarkers.stream().anyMatch(result::contains);
    }

    private void sleepBeforeRetry(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    new InterruptedException("工具重试等待被中断")
            );
        }
    }

    private String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    /**
     * 本工具实例累计重试次数。
     */
    public int getRetryCount() {
        return retryCount.get();
    }

    /**
     * 被装饰的原始工具（测试与诊断用）。
     */
    public ToolCallback getDelegate() {
        return delegate;
    }

    /**
     * 全局累计重试次数（所有被装饰工具合计）。
     */
    public static long getGlobalRetryCount() {
        return GLOBAL_RETRY_COUNT.get();
    }

    /**
     * 测试前重置全局计数器。
     */
    public static void resetGlobalRetryCount() {
        GLOBAL_RETRY_COUNT.set(0);
    }

    /**
     * 一次工具调用（可能抛异常）。
     */
    @FunctionalInterface
    private interface ToolInvocation {

        String invoke();
    }
}
