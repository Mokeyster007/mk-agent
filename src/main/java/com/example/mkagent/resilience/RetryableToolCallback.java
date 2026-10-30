package com.example.mkagent.resilience;

import com.example.mkagent.tools.ToolResultMarkers;
import com.example.mkagent.tools.ToolResultTexts;
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
 *
 * ================== 耗尽之后怎么办（on-exhausted） ==================
 *
 * 默认是 RETURN：**不再抛异常**，而是返回一条脱敏的失败文案作为工具结果，
 * 例如"工具 web_scrape 多次尝试仍失败：Read timed out。请换用其他方式，
 * 或在无法完成时调用 terminate_task"。
 *
 * 为什么改掉"耗尽即抛异常"：
 * 抛异常会从 ToolCallingManager 一路冒到 Agent Loop，把整个任务判成 FAILED——
 * 而一次网页抓取超时根本不该让任务终止。返回失败文案后，
 * 模型下一步能看到"这条路走不通"，自己决定换个地址、换个工具，
 * 或者调用 terminate_task 收尾。失败的那次调用仍然计入
 * toolCallCount 与 maxToolCalls 预算，成本与次数约束不受影响。
 *
 * 需要旧行为时配置 mkagent.tool-retry.on-exhausted=throw。
 *
 * ============ 站点级熔断（max-exhausted-per-target） ============
 *
 * 光有 on-exhausted=return 还不够：模型看到"多次尝试仍失败"后
 * 常常换个路径、换个查询词，继续撞同一个打不开的站点。
 * 真实运行里出现过一条 MAX_STEPS_REACHED、28308 Token 的任务，
 * 全部预算都花在同一个 URL 上。
 *
 * 因此在任务级保存一个 {@link ToolTargetExhaustionTracker}：按 (工具名, 目标主机)
 * 统计耗尽次数，达到 mkagent.tool-retry.max-exhausted-per-target（默认 2）后，
 * 后续对同一目标的调用**不再执行底层工具**，直接返回
 * "该站点连续失败，请换用其他来源或在无法完成时调用 terminate_task"。
 *
 * 为什么计数不在成员变量里：
 * 工具回调是 Spring 单例（MkManus 也是单例），
 * 实例字段会被并发任务共享，导致"上一个任务抓 GitHub 失败"
 * 影响"下一个任务"。计数器存在 AgentRunContext 上，
 * 由 ToolCallAgent.act() 在执行工具期间绑定到当前线程。
 *
 * 为什么不用 ToolCallingChatOptions.toolContext：
 * 它是 Prompt 级的，一旦非空，框架会对所有回调调用
 * call(String, ToolContext)，而该接口的默认实现遇到非空上下文会抛
 * UnsupportedOperationException，打断只实现 call(String) 的回调。
 */
public class RetryableToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(RetryableToolCallback.class);

    /**
     * 失败原因的最大长度：文案要进模型上下文，太长等于把整段堆栈喂回去。
     */
    private static final int MAX_REASON_LENGTH = 120;

    /**
     * 重试耗尽文案的固定标记。
     *
     * 与前端文案推导共用同一常量（见 ToolResultMarkers），
     * 改文案时不会漏改下游。
     */
    public static final String EXHAUSTED_MARKER = ToolResultMarkers.EXHAUSTED;

    /**
     * 站点级熔断文案的固定标记。
     */
    public static final String TARGET_BLOCKED_MARKER =
            ToolResultMarkers.TARGET_BLOCKED;

    /**
     * 同一目标允许连续重试耗尽的默认次数（与配置默认值保持一致）。
     */
    public static final int DEFAULT_MAX_EXHAUSTED_PER_TARGET = 2;

    private final ToolCallback delegate;

    private final int maxAttempts;

    private final Duration initialBackoff;

    private final double multiplier;

    /**
     * 失败文案关键词：工具返回内容包含任一关键词时视为失败。
     */
    private final List<String> failureMarkers;

    /**
     * 重试耗尽后的行为。
     */
    private final ExhaustedStrategy onExhausted;

    /**
     * 同一目标（工具 + 主机）允许连续重试耗尽的次数。
     *
     * 达到后，后续对同一目标的调用**不再真正执行**底层工具，
     * 直接返回"该站点连续失败"提示，防止模型把全部预算烧在
     * 一个打不开的站点上（真实运行出现过 MAX_STEPS_REACHED、28308 Token
     * 的任务）。
     *
     * 小于等于 0 表示关闭该保护。
     */
    private final int maxExhaustedPerTarget;

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
        this(delegate, maxAttempts, initialBackoff, multiplier, failureMarkers,
                ExhaustedStrategy.RETURN);
    }

    public RetryableToolCallback(
            ToolCallback delegate,
            int maxAttempts,
            Duration initialBackoff,
            double multiplier,
            List<String> failureMarkers,
            ExhaustedStrategy onExhausted
    ) {
        this(delegate, maxAttempts, initialBackoff, multiplier, failureMarkers,
                onExhausted, DEFAULT_MAX_EXHAUSTED_PER_TARGET);
    }

    public RetryableToolCallback(
            ToolCallback delegate,
            int maxAttempts,
            Duration initialBackoff,
            double multiplier,
            List<String> failureMarkers,
            ExhaustedStrategy onExhausted,
            int maxExhaustedPerTarget
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
        this.onExhausted = onExhausted == null
                ? ExhaustedStrategy.RETURN
                : onExhausted;
        this.maxExhaustedPerTarget = maxExhaustedPerTarget;
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
        return guardedCall(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return guardedCall(
                toolInput,
                () -> delegate.call(toolInput, toolContext)
        );
    }

    /**
     * 带站点级熔断的调用入口（两个 call 重载共用）。
     *
     * 计数器来自当前线程绑定（ToolCallAgent.act 在执行工具前后绑定/解除）。
     * 没有绑定时——单测直接调用、或非 Agent 链路——只保留单次调用重试语义，
     * 与改动前完全一致。
     */
    private String guardedCall(String toolInput, ToolInvocation invocation) {
        ToolTargetExhaustionTracker tracker =
                ToolTargetExhaustionTracker.current();

        if (tracker == null) {
            return callWithRetry(invocation);
        }

        String toolName = getToolDefinition().name();
        String targetKey = ToolTargetExhaustionTracker
                .targetKey(toolName, toolInput);

        /*
         * 站点级熔断：同一目标已经连续重试耗尽到阈值，
         * 这一次**不再执行底层工具**（否则模型每轮都白烧一次
         * maxAttempts × 退避等待），直接把结论告诉模型。
         */
        if (tracker.isBlocked(targetKey, maxExhaustedPerTarget)) {
            int exhausted = tracker.count(targetKey);
            log.warn(
                    "同一目标连续重试耗尽，本次调用直接跳过：tool={}, target={}, "
                            + "exhausted={}, max={}",
                    toolName, targetKey, exhausted, maxExhaustedPerTarget
            );
            return buildTargetBlockedMessage(toolName, targetKey, exhausted);
        }

        String result = callWithRetry(invocation);

        /*
         * 只有"真的耗尽"才计数：成功一次不清零是有意的——
         * 目标是"同一个站点别再反复撞"，不是"连续失败次数"，
         * 中间偶发一次成功并不代表站点已经可用。
         */
        if (isExhaustedResult(result)) {
            int exhausted = tracker.record(targetKey);
            log.warn(
                    "目标级耗尽计数 +1：tool={}, target={}, exhausted={}/{}",
                    toolName, targetKey, exhausted, maxExhaustedPerTarget
            );
        }

        return result;
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
                        ToolResultTexts.decode(result)
                );

            } catch (RuntimeException e) {
                lastFailure = e;
            }

            retryCount.incrementAndGet();
            GLOBAL_RETRY_COUNT.incrementAndGet();

            log.warn(
                    "工具执行失败，准备重试：tool={}, attempt={}/{}, backoff={}ms, reason={}",
                    toolName, attempt, maxAttempts, backoffMillis,
                    // 同样折叠换行 + 脱敏：单条 WARN 也不该刷屏或泄露密钥。
                    summarize(lastFailure)
            );

            // 最后一次尝试失败后不再等待，直接跳出循环走耗尽处理。
            if (attempt == maxAttempts) {
                break;
            }

            sleepBeforeRetry(backoffMillis);
            backoffMillis = (long) (backoffMillis * multiplier);
        }

        /*
         * ERROR 只记一行：原因里的换行/堆栈一律折叠，
         * 否则一次抓取超时就能把日志刷屏。
         */
        String reason = summarize(lastFailure);

        log.error(
                "工具重试耗尽仍失败：tool={}, maxAttempts={}, totalRetries={}, onExhausted={}, finalReason={}",
                toolName, maxAttempts, retryCount.get(), onExhausted, reason
        );

        if (onExhausted == ExhaustedStrategy.THROW) {
            /*
             * 旧行为：向上抛出，由上层把任务判为 FAILED。
             * 保留它只是为了可回滚，默认不再使用。
             */
            throw lastFailure;
        }

        /*
         * 默认行为：把失败变成"模型可读的结果"。
         *
         * 注意这里返回的是**纯文本**（不是 JSON 字符串字面量），
         * 与正常路径（MethodToolCallback 序列化后的带引号字符串）形态不同；
         * 下游统一经 ToolResultTexts.decode 处理，两种形态都能识别。
         */
        return buildExhaustedMessage(toolName, reason);
    }

    /**
     * 重试耗尽后交给模型的失败文案。
     *
     * 三个要素缺一不可：
     * 1. 哪个工具失败（模型需要知道换掉哪一个）；
     * 2. 简短原因（脱敏、单行、截断）；
     * 3. 下一步可以怎么做（换方式 / 收尾），避免模型反复重试同一个工具。
     */
    private String buildExhaustedMessage(String toolName, String reason) {
        return "工具 " + toolName + " " + EXHAUSTED_MARKER + "：" + reason
                + "。请换用其他方式，或在无法完成时调用 terminate_task";
    }

    /**
     * 站点级熔断后交给模型的提示文案。
     *
     * 与 {@link #buildExhaustedMessage} 的区别必须让模型看得出来：
     * 那条是"这次试了但失败了"，这条是"根本没再试"。
     * 否则模型会以为只是又一次偶发失败，继续原样重试。
     */
    private String buildTargetBlockedMessage(
            String toolName,
            String targetKey,
            int exhausted
    ) {
        String host = ToolTargetExhaustionTracker.hostOfKey(targetKey);
        String target = host.isEmpty() ? "同一目标" : host;

        return TARGET_BLOCKED_MARKER
                + "，请换用其他来源或在无法完成时调用 terminate_task"
                + "（工具 " + toolName
                + "，目标 " + target
                + "，已连续重试耗尽 " + exhausted + " 次，本次未再执行）";
    }

    /**
     * 结果是否为"重试耗尽"文案（先解码再匹配）。
     */
    private boolean isExhaustedResult(String result) {
        if (result == null) {
            return false;
        }
        return ToolResultTexts.decode(result).contains(EXHAUSTED_MARKER);
    }

    /**
     * 把异常整理成"单行 + 脱敏 + 限长"的原因。
     */
    private String summarize(RuntimeException failure) {
        String message = failure == null ? null : failure.getMessage();

        if (message == null || message.isBlank()) {
            return "原因未知";
        }

        // 失败文案可能本身是编码过的工具返回值，先解码再分析。
        String oneLine = ToolResultTexts.decode(message)
                .replaceAll("\\s+", " ")
                .trim();

        if (oneLine.isEmpty()) {
            return "原因未知";
        }

        // 与模型重试日志同一套脱敏规则（sk-xxx、api-key=xxx）。
        oneLine = ModelRetryLoggingListener.maskSecrets(oneLine);

        return oneLine.length() <= MAX_REASON_LENGTH
                ? oneLine
                : oneLine.substring(0, MAX_REASON_LENGTH) + "…";
    }

    /**
     * 判断工具返回文本是否为失败文案（按配置的关键词包含匹配）。
     *
     * 先解码再匹配：@Tool 方法经由 MethodToolCallback 执行时，
     * 返回值会被序列化为 JSON 字符串（外层包裹双引号、内部换行转义成 \n），
     * 失败文案不会出现在字符串最开头，直接 startsWith 一定失效。
     */
    private boolean isFailureResult(String result) {
        if (result == null) {
            return false;
        }
        String decoded = ToolResultTexts.decode(result);
        return failureMarkers.stream().anyMatch(decoded::contains);
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

    /**
     * 本工具实例累计重试次数。
     */
    public int getRetryCount() {
        return retryCount.get();
    }

    /**
     * 站点级熔断阈值（<=0 表示关闭）。
     */
    public int getMaxExhaustedPerTarget() {
        return maxExhaustedPerTarget;
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

    /**
     * 重试耗尽后的行为。
     *
     * RETURN：返回脱敏失败文案给模型（默认，任务继续）；
     * THROW：向上抛异常（旧行为，任务直接 FAILED，仅用于回滚）。
     */
    public enum ExhaustedStrategy {

        RETURN,

        THROW;

        /**
         * 解析配置值，大小写不敏感；非法值回退 RETURN 而不是启动失败——
         * 这是一个"降级也能工作"的开关，不该因为写错一个词让服务起不来。
         */
        public static ExhaustedStrategy parse(String value) {
            if (value == null || value.isBlank()) {
                return RETURN;
            }
            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
            if ("THROW".equals(normalized)) {
                return THROW;
            }
            return RETURN;
        }
    }
}
