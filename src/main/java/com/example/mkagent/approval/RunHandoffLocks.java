package com.example.mkagent.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按 runId 的执行权交接锁（单实例，进程内）。
 *
 * 解决什么问题：
 * 暂停事务提交后，执行线程还要做"释放并发许可 / 从注册表移除"。
 * 如果批准请求在提交之后、释放之前到达，就会与仍在收尾的旧执行线程重叠，
 * 出现"新执行取了许可、旧执行又释放一次"这类错误。
 *
 * 因此暂停与批准都必须先拿这把锁：
 * 暂停：提交 → 释放许可 → 注销 → 放锁；
 * 批准：拿锁 → 取许可 → 改状态 → 提交执行 → 放锁。
 *
 * 为什么不给每个 run 永久保留一把锁：
 * 任务量随时间增长，Map 会无界膨胀。
 * 但"用完就删"有经典竞态：A 解锁后删除，B 正好在删除前拿到旧锁、
 * 删除后 C 又新建一把锁——B 和 C 同时持锁。
 *
 * 这里的做法（引用计数 + 只删无人持有）：
 * 1. `acquire` 先在 `compute` 里把引用数 +1，再 `lock()`；
 *    只要有人持有锁，引用数必然 ≥ 1，因此不会被删；
 * 2. `release` 先 `unlock()`，再在 `compute` 里把引用数 -1，
 *    只有 `refs == 0` 时才移除；
 * 3. 删除只会发生在"锁未被任何线程持有"的时刻；
 *    此后新来的线程拿到的是**新锁**，而旧锁已经没人持有，
 *    不存在两把锁同时被持有的情况。
 *
 * 单实例前提：跨进程无效。多实例部署需要换成数据库/Redis 的租约，
 * 本期明确不做。
 */
@Component
public class RunHandoffLocks {

    private static final Logger log =
            LoggerFactory.getLogger(RunHandoffLocks.class);

    private final Map<String, Holder> locks = new ConcurrentHashMap<>();

    /**
     * 阻塞获取（暂停路径使用：必须拿到，否则不能提交暂停）。
     */
    public void acquire(String runId) {
        Holder holder = locks.compute(runId, (key, existing) -> {
            Holder target = existing == null ? new Holder() : existing;
            target.refs++;
            return target;
        });
        holder.lock.lock();
    }

    /**
     * 限时获取（批准/取消路径使用：拿不到就返回 409，让客户端重试）。
     *
     * @return true 表示拿到锁
     */
    public boolean tryAcquire(String runId, Duration wait) throws InterruptedException {
        Holder holder = locks.compute(runId, (key, existing) -> {
            Holder target = existing == null ? new Holder() : existing;
            target.refs++;
            return target;
        });

        boolean acquired;
        try {
            acquired = holder.lock.tryLock(
                    wait == null ? 0L : wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 没拿到锁就必须把引用数还回去，否则这把锁永远删不掉。
            decrement(runId, holder);
            throw e;
        }

        if (!acquired) {
            decrement(runId, holder);
            log.warn("获取执行权交接锁超时：runId={}, wait={}ms", runId,
                    wait == null ? 0L : wait.toMillis());
        }
        return acquired;
    }

    /**
     * 释放（必须由持有锁的线程调用）。
     */
    public void release(String runId) {
        Holder holder = locks.get(runId);
        if (holder == null) {
            return;
        }
        if (!holder.lock.isHeldByCurrentThread()) {
            // 防御：非持有者释放会抛 IllegalMonitorStateException，
            // 这里直接忽略并告警，避免一个清理 bug 把主流程炸掉。
            log.warn("忽略非持有者的释放：runId={}, thread={}",
                    runId, Thread.currentThread().getName());
            return;
        }
        holder.lock.unlock();
        decrement(runId, holder);
    }

    /**
     * 当前是否存在该 run 的锁（测试断言用）。
     */
    public boolean exists(String runId) {
        return locks.containsKey(runId);
    }

    private void decrement(String runId, Holder holder) {
        locks.computeIfPresent(runId, (key, existing) -> {
            if (existing != holder) {
                return existing;
            }
            existing.refs--;
            return existing.refs <= 0 ? null : existing;
        });
    }

    private static final class Holder {

        private final ReentrantLock lock = new ReentrantLock();

        /**
         * 引用数：在 compute 内修改，因此对该 key 是原子的。
         */
        private int refs;
    }
}
