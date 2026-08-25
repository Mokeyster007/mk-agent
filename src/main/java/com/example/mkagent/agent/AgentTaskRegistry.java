package com.example.mkagent.agent;

import com.example.mkagent.model.RunningAgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中 Agent 任务的进程内注册表。
 *
 * 定位：
 * 1. 仅用于"当前单实例进程内"管理运行中的任务，不引入数据库；
 * 2. key 使用 ctx.getRunId()（UUID 字符串），value 为 RunningAgentTask；
 * 3. 供两类角色使用：
 *    - BaseAgent：任务开始时注册，结束（成功/失败/取消/超时）后移除；
 *    - Controller：根据 runId 查询任务，实现主动取消接口。
 *
 * 并发说明：
 * 注册、查询、移除会发生在不同线程（Web 线程 / agent-* 后台线程），
 * 因此底层使用 ConcurrentHashMap 保证线程安全。
 *
 * 局限（后续优化）：
 * 单实例内存注册表无法支持分布式部署，
 * 多实例场景需要换成 Redis 等共享存储。
 */
@Component
public class AgentTaskRegistry {

    private static final Logger log =
            LoggerFactory.getLogger(AgentTaskRegistry.class);

    /**
     * 运行中任务表：key = runId，value = 任务上下文 + 异步句柄。
     */
    private final Map<String, RunningAgentTask> runningTasks =
            new ConcurrentHashMap<>();

    /**
     * 注册一个运行中的任务。
     */
    public void register(String runId, RunningAgentTask task) {
        runningTasks.put(runId, task);
        log.info(
                "任务已注册：runId={}, 当前运行中任务数={}, thread={}",
                runId, runningTasks.size(), Thread.currentThread().getName()
        );
    }

    /**
     * 移除一个已结束的任务。
     *
     * 任务无论以哪种终态结束（成功/失败/取消/超时），
     * 都必须调用本方法，避免注册表内存泄漏。
     */
    public void remove(String runId) {
        RunningAgentTask removed = runningTasks.remove(runId);
        if (removed != null) {
            log.info(
                    "任务已移除：runId={}, 剩余运行中任务数={}, thread={}",
                    runId, runningTasks.size(), Thread.currentThread().getName()
            );
        }
    }

    /**
     * 根据 runId 查询运行中的任务，可能返回 null。
     */
    public RunningAgentTask get(String runId) {
        return runningTasks.get(runId);
    }

    /**
     * 判断任务是否仍在注册表中。
     */
    public boolean contains(String runId) {
        return runningTasks.containsKey(runId);
    }

    /**
     * 当前运行中任务数量（主要用于监控和测试断言）。
     */
    public int size() {
        return runningTasks.size();
    }

    /**
     * 返回当前所有运行中任务的快照副本（主要用于监控和测试轮询）。
     */
    public Collection<RunningAgentTask> snapshot() {
        return List.copyOf(runningTasks.values());
    }
}
