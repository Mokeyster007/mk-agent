package com.example.mkagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 模型调用重试配置（mkagent.model-retry.*）。
 *
 * 背景：
 * DashScopeChatModel 本身不决定重试策略，它使用容器里的 RetryTemplate Bean
 * （来自 spring-ai-autoconfigure-retry 的 SpringAiRetryAutoConfiguration）。
 * 那个默认策略是 maxAttempts=10 + 指数退避（2s 起、x5、上限 180s），
 * 一次 400 参数错误会白白等上几分钟，最后还可能先撞上 Agent 总超时（TIMED_OUT）。
 *
 * 这里把策略显式收敛成"只重试可能自愈的失败"：
 * 1. TransientAiException（5xx、429）：最多 3 次，1s → 2s 指数退避；
 * 2. 网络超时（ResourceAccessException）：同上；
 * 3. NonTransientAiException（400/401/403 参数错误、鉴权失败、欠费）：一次都不重试。
 */
@Component
@ConfigurationProperties(prefix = "mkagent.model-retry")
public class ModelRetryProperties {

    /**
     * 最多尝试次数（含第一次调用）。
     *
     * 3 表示"首次 + 最多 2 次重试"，配合 1s/2s 退避，最坏等待约 3 秒。
     */
    private int maxAttempts = 3;

    /**
     * 首次重试前的等待时间。
     */
    private Duration initialBackoff = Duration.ofSeconds(1);

    /**
     * 退避倍数（指数退避）。
     */
    private double multiplier = 2.0;

    /**
     * 单次退避上限，避免指数增长到几十秒。
     */
    private Duration maxBackoff = Duration.ofSeconds(2);

    /**
     * 是否把网络超时（ResourceAccessException）也视为可重试。
     */
    private boolean retryOnTimeout = true;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public boolean isRetryOnTimeout() {
        return retryOnTimeout;
    }

    public void setRetryOnTimeout(boolean retryOnTimeout) {
        this.retryOnTimeout = retryOnTimeout;
    }
}
