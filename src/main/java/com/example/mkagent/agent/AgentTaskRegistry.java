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
 *    - BaseAgent：任务开始时注册，结束（成功/失败/取消/超时/暂停）后移除；
 *    - Controller：根据 runId 查询任务，实现主动取消接口。
 *
 * 代次（epoch）语义（M3）：
 * 审批恢复会给同一个 runId 创建新的执行句柄。
 * 因此注册与移除都带代次：
 * - {@link #register} 拒绝更旧的代次（旧线程迟到的注册不能覆盖新句柄）；
 * - {@link #remove(String, int)} 只在句柄代次相等时移除（旧线程不能删新句柄）。
 *
 * 并发说明：
 * 注册、查询、移除会发生在不同线程（Web 线程 / agent-* 后台线程），
 * 因此底层使用 ConcurrentHashMap 保证线程安全；register 用 compute 做原子比较。
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
     * 运行中任务表：key = runId，value = 任务上下文 + 异步句柄 + 代次。
     */
    private final Map<String, RunningAgentTask> runningTasks =
            new ConcurrentHashMap<>();

    /**
     * 注册一个运行中的任务。
     *
     * 若同一 runId 已有更新的代次，拒绝本次注册并返回 false：
     * 说明旧执行线程还在收尾，而新代次已经开始，
     * 此时让旧线程覆盖注册表会让取消接口指向一个已经废弃的句柄。
     *
     * @return true 表示注册成功（或替换了同代次/更旧代次）
     */
    public boolean register(String runId, RunningAgentTask task) {
        boolean[] accepted = {false};

        runningTasks.compute(runId, (key, existing) -> {
            if (existing != null && existing.epoch() > task.epoch()) {
                log.warn(
                        "拒绝注册过期代次：runId={}, 现有 epoch={}, 待注册 epoch={}",
                        runId, existing.epoch(), task.epoch()
                );
                return existing;
            }
            accepted[0] = true;
            return task;
        });

        if (accepted[0]) {
            log.info(
                    "任务已注册：runId={}, epoch={}, 当前运行中任务数={}, thread={}",
                    runId, task.epoch(), runningTasks.size(),
                    Thread.currentThread().getName()
            );
        }
        return accepted[0];
    }

    /**
     * 移除一个已结束的任务（仅当代次匹配）。
     *
     * 任务无论以哪种终态结束（成功/失败/取消/超时/暂停）都必须调用本方法，
     * 避免注册表内存泄漏。
     *
     * @param epoch 调用方自己的执行代次
     * @return true 表示确实移除了自己的句柄
     */
    public boolean remove(String runId, int epoch) {
        boolean[] removed = {false};

        runningTasks.computeIfPresent(runId, (key, existing) -> {
            if (existing.epoch() != epoch) {
                log.info(
                        "跳过移除：runId={}, 现有 epoch={}, 调用方 epoch={}（新代次已接管）",
                        runId, existing.epoch(), epoch
                );
                return existing;
            }
            removed[0] = true;
            return null;
        });

        if (removed[0]) {
            log.info(
                    "任务已移除：runId={}, epoch={}, 剩余运行中任务数={}, thread={}",
                    runId, epoch, runningTasks.size(), Thread.currentThread().getName()
            );
        }
        return removed[0];
    }

    /**
     * 根据 runId 查询运行中的任务，可能返回 null。
     */
    public RunningAgentTask get(String runId) {
        return runningTasks.get(runId);
    }

    /**
     * 当前注册的句柄是否就是调用方自己（按对象身份比较）。
     *
     * 供 SSE 回调使用：回调捕获自己的句柄，执行前确认注册表里仍是它，
     * 否则说明已被新代次接管，不得中断线程、删任务或释放许可。
     */
    public boolean isCurrent(String runId, RunningAgentTask task) {
        return task != null && runningTasks.get(runId) == task;
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
