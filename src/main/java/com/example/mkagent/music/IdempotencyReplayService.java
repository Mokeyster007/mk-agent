package com.example.mkagent.music;

import com.example.mkagent.tools.ToolResponseEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 幂等回放：把已提交的成功结果原样还给模型，不重新执行业务。
 *
 * <h2>调用时机</h2>
 *
 * 只由 {@code IdempotentToolCallback} 在
 * {@link IdempotentWriteExecutor} 抛出"记录已存在"、且**事务已回滚之后**调用。
 * 因此这里的每条查询都是新事务/自动提交，能看到赢家已提交的行。
 * 如果调用方自己还在事务里，隔离级别可能让赢家的提交不可见——
 * 回调入口显式禁止在事务中调用。
 *
 * <h2>回放不是"无脑返回字符串"</h2>
 *
 * 1. 工具名或请求哈希对不上 → 冲突失败关闭（同一身份、不同请求）；
 * 2. 历史行没有结果 / 结果不是成功信封 → 失败关闭，
 *    既不当成成功回放，也不删除后重执行（那会绕过幂等）；
 * 3. 当前用户对这条记录涉及的资源已没有访问权 → 稳定权限错误。
 *    这不代表历史操作被撤销，只是不再把旧结果里的资源信息回给当前用户。
 */
@Service
public class IdempotencyReplayService {

    private static final Logger log =
            LoggerFactory.getLogger(IdempotencyReplayService.class);

    private final ToolIdempotencyStore store;

    public IdempotencyReplayService(ToolIdempotencyStore store) {
        this.store = store;
    }

    /**
     * 回放一次已提交的成功结果。
     *
     * @return 与首次执行**完全相同**的工具结果字符串
     * @throws IdempotencyConflictException   同一身份、不同工具名或参数
     * @throws IdempotencyReplayInvalidException 记录不可用于回放（无结果/非成功/查不到）
     * @throws IdempotencyReplayDeniedException  当前用户已无权访问该结果涉及的资源
     */
    public String replay(IdempotentWriteExecutor.Call call) {
        ToolIdempotencyStore.Record record =
                store.find(call.userId(), call.idempotencyKey());

        if (record == null) {
            /*
             * 正常时序下不该发生：唯一键冲突意味着赢家事务已经结束并提交
             * （PostgreSQL 会先等待、再报冲突）。查不到说明记录被删或隔离级别异常，
             * 都不该盲目重执行业务。
             */
            log.warn("幂等记录冲突后读不到已提交行：runId={}, tool={}",
                    call.runId(), call.toolName());
            throw new IdempotencyReplayInvalidException("幂等记录不可读");
        }

        if (!call.toolName().equals(record.toolName())
                || !call.requestHash().equals(record.requestHash())) {
            log.warn("幂等身份冲突：同一 key 对应了不同请求。runId={}, tool={}",
                    call.runId(), call.toolName());
            throw new IdempotencyConflictException();
        }

        String resultJson = record.resultJson();
        if (resultJson == null || resultJson.isBlank()
                || !ToolResponseEnvelope.isSuccess(resultJson)) {
            /*
             * 旧行没有结果、或存的是失败信封/非法 JSON：
             * 失败关闭。既不能当成功回放，也不能删掉重执行——
             * 后者等于用一次新的副作用掩盖历史不一致。
             */
            log.warn("幂等记录不可回放：runId={}, tool={}, 结果为空或非成功信封",
                    call.runId(), call.toolName());
            throw new IdempotencyReplayInvalidException("幂等记录缺少有效结果");
        }

        requireAccess(call, resultJson);

        log.info("幂等回放命中：runId={}, tool={}, step={}",
                call.runId(), call.toolName(), call.step());
        return resultJson;
    }

    /**
     * 回放前的访问权复核。
     *
     * key 里的 userId 只是第一层隔离；这里再确认两件事：
     * 1. 这次调用所属的 run 仍然属于当前用户（run 记录存在且归属一致）；
     * 2. 结果里涉及的业务资源（歌单）当前仍归当前用户。
     *
     * 只读校验，不修改历史记录、不重新执行业务。
     */
    private void requireAccess(IdempotentWriteExecutor.Call call, String resultJson) {
        ToolIdempotencyStore.RunGuard guard = store.findRun(call.runId().toString());
        if (guard == null || !call.userId().equals(guard.userId())) {
            throw new IdempotencyReplayDeniedException();
        }

        Long playlistId = ToolResponseEnvelope.playlistIdOf(resultJson);
        if (playlistId != null && !store.ownsPlaylist(call.userId(), playlistId)) {
            throw new IdempotencyReplayDeniedException();
        }
    }

    /**
     * 同一身份、不同请求。
     */
    public static class IdempotencyConflictException extends RuntimeException {

        public IdempotencyConflictException() {
            super("同一工具调用身份对应了不同的请求");
        }
    }

    /**
     * 记录不可用于回放。
     */
    public static class IdempotencyReplayInvalidException extends RuntimeException {

        public IdempotencyReplayInvalidException(String message) {
            super(message);
        }
    }

    /**
     * 当前用户已无权访问该结果涉及的资源。
     */
    public static class IdempotencyReplayDeniedException extends RuntimeException {

        public IdempotencyReplayDeniedException() {
            super("当前用户已无权访问该操作对应的资源");
        }
    }
}
