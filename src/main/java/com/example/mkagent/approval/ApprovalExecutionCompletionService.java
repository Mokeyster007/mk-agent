package com.example.mkagent.approval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.service.AgentRunRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 恢复执行的终态收尾：agent_run 终态与 approval.resume_state 同一事务。
 *
 * 为什么必须同事务：
 * 两条写入如果分开提交，进程在中间退出就会留下
 * "审批已 FINISHED、run 还是 RUNNING"——审批说"执行阶段结束了"，
 * 任务却永远没有终态，既不是成功也不是失败，扫描器也只能猜。
 * 反过来（run 终态先落、审批后落）会留下"run 成功、审批仍在执行"的假执行中。
 * 两种中间态都不该存在，所以只能同生共死。
 *
 * 顺序固定为：先 run 终态、后审批收尾。
 * 若 run 的行数不是 1（旧代次的迟到写入），审批收尾一并跳过——
 * 那条审批此刻归新代次，不能被旧线程标成 FINISHED。
 *
 * 注意：本方法**不**吞异常。事务要能回滚，异常必须往外抛；
 * 调用方（BaseAgent.persistTerminal）负责记录日志并兜底重试一次。
 */
@Service
public class ApprovalExecutionCompletionService {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalExecutionCompletionService.class);

    private final AgentRunRecorder runRecorder;

    private final ApprovalClaimService claimService;

    public ApprovalExecutionCompletionService(
            AgentRunRecorder runRecorder,
            ApprovalClaimService claimService
    ) {
        this.runRecorder = runRecorder;
        this.claimService = claimService;
    }

    /**
     * 原子收尾一次恢复执行。
     *
     * 两步写入都在本方法的事务里（{@code claimService.finish} 是
     * {@code REQUIRED} 传播，加入当前事务而不是另开一个），因此：
     * - 第一步成功、第二步抛异常 → 整个事务回滚，run 不会留下终态；
     * - 第一步成功、第二步 0 行 → 只有确认审批已是 FINISHED（重复收尾）
     *   才按幂等接受，其余一律抛异常回滚，绝不提交"run 终态、审批未收尾"。
     *
     * @return true 表示两条写入都已提交（或本次代次已不是当前代次、按规则不写）；
     *         false 表示 run 终态未命中当前代次，本次没有做任何写入
     * @throws IllegalStateException 审批收尾未命中且不是合法的重复收尾（触发回滚）
     */
    @Transactional
    public boolean complete(
            AgentRunContext ctx,
            String errorMessage,
            long totalCostMillis
    ) {
        int rows = runRecorder.recordFinishStrict(ctx, errorMessage, totalCostMillis);

        if (rows != 1) {
            /*
             * 代次不符（或任务仍是 WAITING_APPROVAL）：
             * 本次执行没有资格写终态，也就没有资格收尾审批。
             * 不抛异常——这不是故障，是"旧执行迟到"的正常结果。
             */
            log.warn("终态写入未命中当前代次，跳过审批收尾：runId={}, epoch={}, state={}, rows={}",
                    ctx.getRunId(), ctx.getExecutionEpoch(), ctx.getState(), rows);
            return false;
        }

        String approvalId = ctx.getResumeApprovalId();

        if (approvalId == null) {
            throw new IllegalStateException(
                    "终态已写入，但缺少审批标识，无法在同一事务里收尾：runId=" + ctx.getRunId());
        }

        int approvalRows = claimService.finish(approvalId);

        if (approvalRows != 1) {
            /*
             * 唯一合法的 0 行：这条审批已经是 FINISHED。
             * 那说明上一次收尾已经完整提交（run 终态 + FINISHED 同事务），
             * 这次只是重复收尾，重写终态没有副作用，按幂等接受。
             *
             * 其它状态（PENDING_RESUME / FAILED / 行不存在）意味着
             * 审批已经不归本次执行管，继续提交就会留下
             * "run 已终态、审批还在执行/已退回"的矛盾，必须回滚。
             */
            ApprovalRecord record = claimService.find(approvalId);
            String resumeState = record == null ? null : record.resumeState();

            if (ApprovalRecord.RESUME_FINISHED.equals(resumeState)) {
                log.warn("审批已是 FINISHED（重复收尾），按幂等处理：runId={}, approvalId={}",
                        ctx.getRunId(), approvalId);
                return true;
            }

            throw new IllegalStateException(
                    "审批收尾未命中，回滚 run 终态：runId=" + ctx.getRunId()
                            + ", approvalId=" + approvalId
                            + ", resumeState=" + resumeState
                            + ", rows=" + approvalRows);
        }

        log.info("恢复执行已收尾：runId={}, state={}, approvalId={}",
                ctx.getRunId(), ctx.getState(), approvalId);
        return true;
    }
}
