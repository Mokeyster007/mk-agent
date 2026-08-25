package com.example.mkagent.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 全局 Agent 并发控制闸门（进程级）。
 *
 * 为什么需要它：
 * 1. agentExecutor 线程池只能限制"执行线程数量"，
 *    无法限制"同时存活的 Agent 任务数量"
 *    （任务可能阻塞在模型 HTTP 调用上，占用线程很久）；
 * 2. 每个 Agent 任务都会消耗真实付费的大模型 Token，
 *    无上限并发会同时放大成本、延迟和下游 API 限流风险。
 *
 * 实现方式：
 * 公平模式 Semaphore， permits 数量 = mkagent.agent.max-concurrency。
 * BaseAgent 在任务开始前 tryAcquire，
 * 任务以任何终态结束（成功 / 失败 / 取消 / 超时）时在 finally 中 release。
 *
 * 线程安全：Semaphore 本身线程安全，可在 Web 线程与
 * agent-* 后台线程之间跨线程获取 / 释放。
 */
@Component
public class AgentConcurrencyGuard {

    private static final Logger log =
            LoggerFactory.getLogger(AgentConcurrencyGuard.class);

    /**
     * 全局最大并发 Agent 任务数，可通过配置调整。
     */
    private final int maxConcurrency;

    /**
     * 公平模式：先到先得，避免高并发下部分请求饥饿。
     */
    private final Semaphore semaphore;

    public AgentConcurrencyGuard(
            @Value("${mkagent.agent.max-concurrency:8}") int maxConcurrency
    ) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException(
                    "mkagent.agent.max-concurrency 必须为正数：" + maxConcurrency
            );
        }
        this.maxConcurrency = maxConcurrency;
        this.semaphore = new Semaphore(maxConcurrency, true);
        log.info("Agent 全局并发闸门初始化：maxConcurrency={}", maxConcurrency);
    }

    /**
     * 尝试获取一个任务许可（不阻塞等待）。
     *
     * @return true 获取成功，调用方必须在任务终态后调用 release()；
     *         false 表示当前并发已满，应拒绝本次请求。
     */
    public boolean tryAcquire() {
        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            log.warn(
                    "Agent 并发已满，拒绝新任务：maxConcurrency={}, available={}",
                    maxConcurrency, semaphore.availablePermits()
            );
        }
        return acquired;
    }

    /**
     * 释放一个任务许可。
     *
     * 必须由成功 tryAcquire 的调用方在任务终态后调用一次；
     * 未获取许可时禁止调用，否则许可数会超过上限。
     */
    public void release() {
        semaphore.release();
        log.debug(
                "Agent 并发许可已释放：available={}/{}",
                semaphore.availablePermits(), maxConcurrency
        );
    }

    /**
     * 当前空闲许可数（监控与测试断言用）。
     */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * 配置的最大并发数（监控与测试断言用）。
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }
}
