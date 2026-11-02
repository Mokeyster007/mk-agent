package com.example.mkagent.approval;

import com.example.mkagent.model.AgentRunContext;

/**
 * 暂停写入的入口（接口而非具体类，便于单测替换）。
 *
 * 真实实现 {@link ApprovalPauseService} 在**一个事务**里完成三件事：
 * 1. `agent_run` 置 WAITING_APPROVAL（CAS：state=RUNNING 且代次匹配）；
 * 2. 写检查点；
 * 3. 写审批记录（PENDING）。
 *
 * 三者必须同事务：只写审批不写检查点，恢复时无米下锅；
 * 只写检查点不写审批，用户永远看不到待确认项。
 */
public interface ApprovalPauseGateway {

    /**
     * 持久化暂停。
     *
     * @throws PauseRejectedException 状态已被他人改变（取消/重复暂停），调用方应放弃暂停
     */
    PauseResult persist(
            AgentRunContext ctx,
            ApprovalCheckpoint checkpoint,
            ApprovalSummary summary
    );

    /**
     * 放弃一次暂停（暂停后发现任务已被取消）。
     *
     * 只清理自己刚写的记录，不触碰任务状态。
     */
    void abandon(String approvalId, String reason);
}
