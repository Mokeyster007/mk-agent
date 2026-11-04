package com.example.mkagent.music;

import com.example.mkagent.tools.ToolResponseEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 单次写工具调用的幂等执行器。
 *
 * <h2>一个调用 = 一个事务</h2>
 *
 * <pre>
 * TX: 代次/归属/状态守卫（agent_run 行锁 SELECT ... FOR UPDATE）
 *     INSERT 幂等占位行            ← 第一条写入语句，唯一索引在这里裁决并发
 *     业务写入（MusicPlaylistWriteService，REQUIRED 加入本事务）
 *     UPDATE 成功结果 + 校验影响行数
 *     COMMIT
 * </pre>
 *
 * 占位、业务、结果三者同生共死：提交了就一定有可回放的成功结果，
 * 回滚了就一定没有副作用，也不会有"占位但没结果"的残留行。
 *
 * <h2>为什么 INSERT 必须排在业务写入之前</h2>
 *
 * 唯一键冲突在 PostgreSQL 上会让**整个事务**进入 aborted 状态，
 * 之后的语句全部失败。只有把可能冲突的 INSERT 放在最前面，
 * 才谈得上"冲突就整体回滚、然后换新事务读赢家结果"。
 *
 * <h2>为什么冲突异常要在这里转换</h2>
 *
 * 只有这一条 INSERT 的 DuplicateKeyException 才是"幂等命中"。
 * 业务表（同名歌单、歌单歌曲主键）的唯一键冲突**不是**幂等命中，
 * 不能拿去做历史回放——那会把一次真实的业务失败伪装成"上次已经成功"。
 * 因此转换只包住这一条语句，别处的 DuplicateKeyException 走原有业务/系统错误处理。
 *
 * <h2>事务代理</h2>
 *
 * 本类是独立 Bean，由工具回调跨 Bean 调用，事务代理才生效
 * （工具方法自己标 {@code @Transactional} 不走代理，见
 * {@code MusicPlaylistWriteService} 的注释）。
 * 调用方必须**不在**事务里：否则"冲突后回滚、再用新事务读赢家结果"
 * 的前提不成立，回调入口会显式拒绝这种调用。
 */
@Service
public class IdempotentWriteExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(IdempotentWriteExecutor.class);

    private final ToolIdempotencyStore store;

    public IdempotentWriteExecutor(ToolIdempotencyStore store) {
        this.store = store;
    }

    /**
     * 一次写调用的全部输入。
     *
     * @param userId         服务端固化的用户
     * @param runId          任务 ID（只用于守卫与排查，已包含在 key 里）
     * @param step           步号（只用于排查）
     * @param toolCallId     模型给出的调用 ID（只用于排查）
     * @param toolName       工具名
     * @param idempotencyKey 逻辑身份
     * @param requestHash    工具名 + 规范化参数
     * @param executionEpoch 本次执行持有的代次
     * @param business       真正的业务调用（返回工具信封 JSON）
     */
    public record Call(
            String userId,
            UUID runId,
            int step,
            String toolCallId,
            String toolName,
            String idempotencyKey,
            String requestHash,
            int executionEpoch,
            Supplier<String> business
    ) {
    }

    /**
     * 在单个事务里执行一次写调用。
     *
     * @return 成功信封 JSON（与业务调用返回的字符串**完全一致**，供原样回放）
     * @throws IdempotencyRecordConflictException 同一身份已有记录（调用方需回滚后读赢家结果）
     * @throws BusinessEnvelopeFailure            业务返回了失败信封（占位行已回滚）
     * @throws IdempotencyRecordLostException     结果更新影响行数不是 1（整体回滚）
     * @throws WriteRejectedException            任务状态/代次/归属不允许写入
     */
    @Transactional
    public String execute(Call call) {
        /*
         * 先设本事务的等待上限，再去做任何可能排队的事情：
         * 守卫的行锁等待与占位 INSERT 的唯一索引等待都必须在同一个上限内结束。
         * （SET LOCAL 只作用于本事务，事务结束即失效，不会污染连接池。）
         */
        store.beginBoundedWait();

        /*
         * 代次守卫：幂等键不含 epoch，但"能不能写"仍然由代次决定。
         * 旧代次的执行（线程还活着、或者迟到的回调）不得创建新的业务副作用。
         *
         * 必须**加锁读**（SELECT ... FOR UPDATE）而不是普通 SELECT：
         * 普通读只能证明"读的那一刻代次还对"，读完之后代次仍可能被推进，
         * 于是旧执行照样写出副作用。加锁后与代次推进（claim 的
         * UPDATE agent_run ... WHERE state='WAITING_APPROVAL'）在同一行上串行化：
         * 要么我们先进、推进被阻塞到我们提交（此时状态是 RUNNING，推进影响 0 行），
         * 要么推进先进、我们读到新代次并拒绝。详见 ToolIdempotencyStore#lockRun。
         */
        ToolIdempotencyStore.RunGuard guard = store.lockRun(call.runId().toString());
        if (guard == null) {
            throw new WriteRejectedException("任务状态无法确认，本次写操作已拒绝");
        }
        if (!call.userId().equals(guard.userId())) {
            throw new WriteRejectedException("任务归属不一致，本次写操作已拒绝");
        }
        if (!"RUNNING".equals(guard.state())) {
            /*
             * 只看代次不够：暂停中/已终态的任务同样不该再产生写副作用
             * （例如恢复后又被暂停，而旧线程的迟到回调刚好到达）。
             */
            log.warn("拒绝非运行中任务的写入：runId={}, state={}, tool={}",
                    call.runId(), guard.state(), call.toolName());
            throw new WriteRejectedException("任务当前不在执行状态，写操作已拒绝");
        }
        if (guard.executionEpoch() != call.executionEpoch()) {
            log.warn("拒绝旧代次写入：runId={}, ctxEpoch={}, dbEpoch={}, tool={}",
                    call.runId(), call.executionEpoch(), guard.executionEpoch(),
                    call.toolName());
            throw new WriteRejectedException("本次执行已不是当前代次，写操作已拒绝");
        }

        try {
            store.insertPlaceholder(
                    call.userId(),
                    call.idempotencyKey(),
                    call.toolName(),
                    call.requestHash(),
                    call.runId().toString(),
                    call.step(),
                    call.toolCallId()
            );
        } catch (DuplicateKeyException e) {
            /*
             * 只在这一条语句上识别"幂等命中"。
             * 异常继续向上抛（换成专用类型），事务由代理回滚；
             * 绝不能在这里 catch 后继续用这个事务读数据——
             * PostgreSQL 上它已经 aborted，H2 上虽然还能读，
             * 但那样写出来的代码在 PG 上必炸。
             */
            throw new IdempotencyRecordConflictException(call.idempotencyKey());
        }

        /*
         * 业务写入。异常直接向上抛：占位行随事务一起回滚，
         * 不会留下"没跑完却已占位"的记录。
         */
        String result = call.business().get();

        if (!ToolResponseEnvelope.isSuccess(result)) {
            /*
             * 工具把业务失败包成了错误信封（同名歌单、歌单不存在、参数非法…）。
             * 这种结果不能缓存：缓存了就等于把一次失败永久当成这次调用的答案。
             * 抛内部异常回滚占位行，外层再把原始信封原样还给模型。
             */
            throw new BusinessEnvelopeFailure(result);
        }

        int rows = store.saveResult(
                call.userId(),
                call.idempotencyKey(),
                call.requestHash(),
                result
        );
        if (rows != 1) {
            /*
             * 占位行在同一个事务里凭空消失，只可能是并发删除或数据损坏。
             * 不能"记个日志然后提交"——那会提交一次没有幂等记录保护的业务写入。
             */
            throw new IdempotencyRecordLostException(call.idempotencyKey(), rows);
        }

        return result;
    }

    /**
     * 同一身份已有记录：本次执行已回滚，调用方应读取已提交记录并核对请求哈希。
     */
    public static class IdempotencyRecordConflictException extends RuntimeException {

        private final transient String idempotencyKey;

        public IdempotencyRecordConflictException(String idempotencyKey) {
            super("幂等记录已存在");
            this.idempotencyKey = idempotencyKey;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }
    }

    /**
     * 业务返回失败信封：用于回滚占位行，并携带原始信封供外层原样返回。
     */
    public static class BusinessEnvelopeFailure extends RuntimeException {

        private final transient String envelope;

        public BusinessEnvelopeFailure(String envelope) {
            super("业务返回失败信封");
            this.envelope = envelope;
        }

        public String getEnvelope() {
            return envelope;
        }
    }

    /**
     * 结果更新影响行数不是 1：整体回滚。
     */
    public static class IdempotencyRecordLostException extends RuntimeException {

        public IdempotencyRecordLostException(String idempotencyKey, int rows) {
            super("幂等记录结果写入失败：key=" + idempotencyKey + ", rows=" + rows);
        }
    }

    /**
     * 任务状态/代次/归属不允许写入。消息已脱敏，可直接返回给模型。
     */
    public static class WriteRejectedException extends RuntimeException {

        public WriteRejectedException(String message) {
            super(message);
        }
    }
}
