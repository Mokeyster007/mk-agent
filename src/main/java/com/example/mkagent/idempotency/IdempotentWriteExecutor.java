package com.example.mkagent.idempotency;

import com.example.mkagent.tools.ToolResponseEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 单次写工具调用的幂等执行器（通用框架能力：售后、下单确认共用）。
 */
@Service
public class IdempotentWriteExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(IdempotentWriteExecutor.class);

    private final ToolIdempotencyStore store;

    public IdempotentWriteExecutor(ToolIdempotencyStore store) {
        this.store = store;
    }

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

    @Transactional
    public String execute(Call call) {
        store.beginBoundedWait();

        ToolIdempotencyStore.RunGuard guard = store.lockRun(call.runId().toString());
        if (guard == null) {
            throw new WriteRejectedException("任务状态无法确认，本次写操作已拒绝");
        }
        if (!call.userId().equals(guard.userId())) {
            throw new WriteRejectedException("任务归属不一致，本次写操作已拒绝");
        }
        if (!"RUNNING".equals(guard.state())) {
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
            throw new IdempotencyRecordConflictException(call.idempotencyKey());
        }

        String result = call.business().get();

        if (!ToolResponseEnvelope.isSuccess(result)) {
            throw new BusinessEnvelopeFailure(result);
        }

        int rows = store.saveResult(
                call.userId(),
                call.idempotencyKey(),
                call.requestHash(),
                result
        );
        if (rows != 1) {
            throw new IdempotencyRecordLostException(call.idempotencyKey(), rows);
        }

        return result;
    }

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

    public static class IdempotencyRecordLostException extends RuntimeException {

        public IdempotencyRecordLostException(String idempotencyKey, int rows) {
            super("幂等记录结果写入失败：key=" + idempotencyKey + ", rows=" + rows);
        }
    }

    public static class WriteRejectedException extends RuntimeException {

        public WriteRejectedException(String message) {
            super(message);
        }
    }
}
