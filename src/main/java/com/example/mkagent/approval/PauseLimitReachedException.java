package com.example.mkagent.approval;

/**
 * 单任务审批次数触顶（批次 2）。
 *
 * 与 {@link PauseRejectedException} 的区别在于**原因**：
 * - PauseRejectedException：CAS 未命中（任务被取消 / 代次已变），暂停根本没成立；
 * - PauseLimitReachedException：任务状态没问题，但这次暂停已经越界，
 *   必须让任务以 APPROVAL_LIMIT_REACHED 失败，而不是继续等人工确认。
 *
 * 两者都在 {@link ApprovalPauseService#persist} 的事务内抛出，
 * 抛出即回滚：不会留下审批记录、不会写检查点、approval_count 复原。
 */
public class PauseLimitReachedException extends RuntimeException {

    public PauseLimitReachedException(String message) {
        super(message);
    }
}
