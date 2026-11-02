package com.example.mkagent.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 审批配置（M3）。
 *
 * 与执行预算分开：
 * - `mkagent.approval.ttl` 是"人什么时候必须点确认"，与模型执行无关；
 * - `mkagent.agent.*.timeout` 是"模型执行多久算超时"。
 * 审批等待不计入执行预算，两者不能混成一个配置。
 */
@Component
@ConfigurationProperties(prefix = "mkagent.approval")
public class ApprovalProperties {

    /**
     * 是否启用审批暂停。
     *
     * 关闭时命中写工具**不会**直接执行，而是让任务失败（失败关闭）：
     * 静默放行等于把"需要人工确认"的承诺作废。
     */
    private boolean enabled = true;

    /**
     * 审批有效期（从创建审批记录开始算）。
     */
    private Duration ttl = Duration.ofMinutes(15);

    /**
     * 单个任务最多创建多少个审批（R4）。
     *
     * 只在**创建新审批**时 +1；拒绝、重复请求、恢复重试都不重复计数。
     * 目的是防止"模型反复触发写工具 → 反复暂停"把一次任务拖成无限长。
     */
    private int maxPerRun = 3;

    /**
     * 后台恢复扫描间隔（批次 4 使用）。
     */
    private Duration resumeSweepDelay = Duration.ofSeconds(30);

    /**
     * 单条审批最多尝试恢复几次（批次 2/4 使用）。
     *
     * 注意：拿不到并发许可不算一次尝试——
     * 那是"系统忙"，不是"恢复失败"。
     */
    private int maxResumeAttempts = 3;

    /**
     * 等待执行权交接锁的最长时间（批次 2 使用）。
     */
    private Duration leaseWait = Duration.ofSeconds(3);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxPerRun() {
        return maxPerRun;
    }

    public void setMaxPerRun(int maxPerRun) {
        this.maxPerRun = maxPerRun;
    }

    public Duration getResumeSweepDelay() {
        return resumeSweepDelay;
    }

    public void setResumeSweepDelay(Duration resumeSweepDelay) {
        this.resumeSweepDelay = resumeSweepDelay;
    }

    public int getMaxResumeAttempts() {
        return maxResumeAttempts;
    }

    public void setMaxResumeAttempts(int maxResumeAttempts) {
        this.maxResumeAttempts = maxResumeAttempts;
    }

    public Duration getLeaseWait() {
        return leaseWait;
    }

    public void setLeaseWait(Duration leaseWait) {
        this.leaseWait = leaseWait;
    }
}
