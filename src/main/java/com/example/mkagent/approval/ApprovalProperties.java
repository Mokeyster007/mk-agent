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

    /**
     * 已决定但尚未启动的等待期限（批次 2）。
     *
     * 与 {@link #ttl} 是两回事：
     * - ttl 管"人什么时候必须做决定"（PENDING 的有效期）；
     * - resumeStartTimeout 管"决定之后多久之内必须真正跑起来"。
     *
     * 超过这个期限还起不来（一直抢不到并发许可 / 执行器持续拒绝），
     * 任务进入明确失败态，而不是无限期挂在 APPROVED。
     * 这类等待不消耗"实际执行失败次数"。
     */
    private Duration resumeStartTimeout = Duration.ofMinutes(5);

    /**
     * 启动收尾扫描开关（批次 4B）。**默认关闭**。
     *
     * <p>打开它的前提是**部署约定**，不是代码能证明的事实：
     * 1. 上一次的进程已经退出；
     * 2. 当前实例独占使用这个业务库（没有第二个实例同时在跑）。
     *
     * <p>系统本批**不提供**跨进程的单实例强制保证，也没有 owner/租约/心跳字段。
     * 因此这个开关不能被理解为"已经证明其他实例死亡"——
     * 在多实例或部署约定不明确的环境里打开它，会把别的实例正在执行的任务判成中断。
     */
    private boolean recoveryEnabled = false;

    /**
     * 每批读取的候选行数（分页大小）。
     */
    private int recoveryBatchSize = 50;

    /**
     * 单次扫描最多处理多少行。
     *
     * 达到上限仍有候选时，扫描按"未完成"结束，执行入口保持关闭，
     * 不会把剩余记录留给一个不存在的"下一轮"。
     */
    private int recoveryMaxRows = 200;

    /**
     * 单次扫描的墙钟预算（不是"批次之间"才检查，每处理一条之前都检查）。
     */
    private Duration recoveryMaxDuration = Duration.ofSeconds(10);

    /**
     * 单条记录事务的锁等待上限（仅 PostgreSQL，作用于当前事务）。
     */
    private Duration recoveryLockTimeout = Duration.ofSeconds(2);

    public boolean isRecoveryEnabled() {
        return recoveryEnabled;
    }

    public void setRecoveryEnabled(boolean recoveryEnabled) {
        this.recoveryEnabled = recoveryEnabled;
    }

    public int getRecoveryBatchSize() {
        return recoveryBatchSize;
    }

    public void setRecoveryBatchSize(int recoveryBatchSize) {
        this.recoveryBatchSize = recoveryBatchSize;
    }

    public int getRecoveryMaxRows() {
        return recoveryMaxRows;
    }

    public void setRecoveryMaxRows(int recoveryMaxRows) {
        this.recoveryMaxRows = recoveryMaxRows;
    }

    public Duration getRecoveryMaxDuration() {
        return recoveryMaxDuration;
    }

    public void setRecoveryMaxDuration(Duration recoveryMaxDuration) {
        this.recoveryMaxDuration = recoveryMaxDuration;
    }

    public Duration getRecoveryLockTimeout() {
        return recoveryLockTimeout;
    }

    public void setRecoveryLockTimeout(Duration recoveryLockTimeout) {
        this.recoveryLockTimeout = recoveryLockTimeout;
    }

    public Duration getResumeStartTimeout() {
        return resumeStartTimeout;
    }

    public void setResumeStartTimeout(Duration resumeStartTimeout) {
        this.resumeStartTimeout = resumeStartTimeout;
    }

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
