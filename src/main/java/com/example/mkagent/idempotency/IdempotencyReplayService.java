package com.example.mkagent.idempotency;

import com.example.mkagent.tools.ToolResponseEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 幂等回放：把已提交的成功结果原样还给模型，不重新执行业务。
 */
@Service
public class IdempotencyReplayService {

    private static final Logger log =
            LoggerFactory.getLogger(IdempotencyReplayService.class);

    private final ToolIdempotencyStore store;

    public IdempotencyReplayService(ToolIdempotencyStore store) {
        this.store = store;
    }

    public String replay(IdempotentWriteExecutor.Call call) {
        ToolIdempotencyStore.Record record =
                store.find(call.userId(), call.idempotencyKey());

        if (record == null) {
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
            log.warn("幂等记录不可回放：runId={}, tool={}, 结果为空或非成功信封",
                    call.runId(), call.toolName());
            throw new IdempotencyReplayInvalidException("幂等记录缺少有效结果");
        }

        requireAccess(call);

        log.info("幂等回放命中：runId={}, tool={}, step={}",
                call.runId(), call.toolName(), call.step());
        return resultJson;
    }

    private void requireAccess(IdempotentWriteExecutor.Call call) {
        ToolIdempotencyStore.RunGuard guard = store.findRun(call.runId().toString());
        if (guard == null || !call.userId().equals(guard.userId())) {
            throw new IdempotencyReplayDeniedException();
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {

        public IdempotencyConflictException() {
            super("同一工具调用身份对应了不同的请求");
        }
    }

    public static class IdempotencyReplayInvalidException extends RuntimeException {

        public IdempotencyReplayInvalidException(String message) {
            super(message);
        }
    }

    public static class IdempotencyReplayDeniedException extends RuntimeException {

        public IdempotencyReplayDeniedException() {
            super("当前用户已无权访问该操作对应的资源");
        }
    }
}
