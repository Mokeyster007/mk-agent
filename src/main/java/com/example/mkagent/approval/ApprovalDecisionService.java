package com.example.mkagent.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批决定（批次 2）：approve / reject / cancel。
 *
 * 三者**竞争同一个原子条件**：{@code WHERE status = 'PENDING' AND expires_at > now}。
 * 胜负只由受影响行数决定，不做"先查再改"——先查再改在两个请求同时到达时
 * 会让两边都认为自己赢了，结果是"批准了又取消"。
 *
 * 归属校验：
 * - 请求者不是审批所有者 → 一律 404（不区分"不存在"与"不是你的"，避免探测）；
 * - 这条规则只影响**请求**，绝不因为别人发来跨用户请求就把这条审批置为 INVALIDATED。
 *   只有"持久化记录之间的归属不一致"（审批的 userId 与检查点的 userId 不同）
 *   才算检查点损坏，由校验器处理。
 */
@Service
public class ApprovalDecisionService {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalDecisionService.class);

    private final JdbcTemplate jdbcTemplate;

    private final ApprovalClaimService claimService;

    public ApprovalDecisionService(
            JdbcTemplate jdbcTemplate,
            ApprovalClaimService claimService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.claimService = claimService;
    }

    /**
     * 决定结果：HTTP 状态 + 稳定原因码 + 记录快照。
     */
    public record DecisionResult(int httpStatus, String code, ApprovalRecord record) {

        public boolean ok() {
            return httpStatus == 200;
        }

        static DecisionResult ok(ApprovalRecord record) {
            return new DecisionResult(200, "OK", record);
        }

        static DecisionResult error(int status, String code, ApprovalRecord record) {
            return new DecisionResult(status, code, record);
        }
    }

    /**
     * 批准或拒绝。
     *
     * @param approve true=批准，false=拒绝
     */
    @Transactional
    public DecisionResult decide(String approvalId, String userId, boolean approve) {
        ApprovalRecord record = claimService.find(approvalId);

        if (record == null || !record.userId().equals(userId)) {
            // 不区分"不存在"与"不是你的"：跨用户探测拿不到任何信息
            return DecisionResult.error(404, "APPROVAL_NOT_FOUND", record);
        }

        if (record.isDecided()) {
            return DecisionResult.error(409,
                    record.isApproved() ? "ALREADY_APPROVED" : "ALREADY_REJECTED", record);
        }

        if (!ApprovalRecord.STATUS_PENDING.equals(record.status())) {
            return DecisionResult.error(409, "APPROVAL_NOT_PENDING", record);
        }

        String target = approve
                ? ApprovalRecord.STATUS_APPROVED
                : ApprovalRecord.STATUS_REJECTED;

        int rows = jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET status = ?, decided_at = CURRENT_TIMESTAMP, "
                        + "    updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? AND status = 'PENDING' "
                        + "  AND expires_at > CURRENT_TIMESTAMP",
                target, approvalId
        );

        if (rows == 1) {
            log.info("审批决定已持久化：approvalId={}, status={}, userId={}",
                    approvalId, target, userId);
            return DecisionResult.ok(claimService.find(approvalId));
        }

        /*
         * 0 行：说明在"读"和"改"之间被别人抢先了。
         * 重新读一次，把真实状态如实返回，让调用方知道输给了谁。
         */
        ApprovalRecord current = claimService.find(approvalId);
        if (current == null) {
            return DecisionResult.error(404, "APPROVAL_NOT_FOUND", null);
        }
        if (ApprovalRecord.STATUS_PENDING.equals(current.status())) {
            // 仍是 PENDING 却改不动，只可能是过期
            expire(current);
            return DecisionResult.error(410, "APPROVAL_EXPIRED", current);
        }
        return DecisionResult.error(409,
                "ALREADY_" + current.status(), current);
    }

    /**
     * 取消一条待审批（暂停态任务取消）。
     *
     * 与 approve/reject 竞争同一个条件：谁先命中 `status='PENDING'` 谁赢。
     * 赢家负责把 run 置为 CANCELLED；输家拿到 409 与真实状态。
     */
    @Transactional
    public DecisionResult cancel(String runId, String userId) {
        ApprovalRecord record = findPendingByRun(runId);

        if (record == null || !record.userId().equals(userId)) {
            return DecisionResult.error(404, "APPROVAL_NOT_FOUND", record);
        }

        int rows = jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET status = 'CANCELLED', resume_state = 'FAILED', "
                        + "    decided_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? AND status = 'PENDING'",
                record.approvalId()
        );

        if (rows != 1) {
            ApprovalRecord current = claimService.find(record.approvalId());
            return DecisionResult.error(409,
                    "ALREADY_" + (current == null ? "DECIDED" : current.status()), current);
        }

        int runRows = jdbcTemplate.update(
                "UPDATE agent_run "
                        + "SET state = 'CANCELLED', error_code = 'USER_CANCELLED', "
                        + "    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE run_id = ? AND state = 'WAITING_APPROVAL'",
                runId
        );

        if (runRows != 1) {
            /*
             * 审批被取消但任务已不在等待态：任务可能已被别的路径推进。
             * 不回滚取消（用户的取消意图应当生效），但要留痕。
             */
            log.warn("审批已取消但任务不在等待审批状态：runId={}, rows={}", runId, runRows);
        }

        log.info("暂停任务已取消：runId={}, approvalId={}", runId, record.approvalId());
        return DecisionResult.ok(claimService.find(record.approvalId()));
    }

    /**
     * 过期收尾：审批 EXPIRED，任务 FAILED + APPROVAL_EXPIRED。
     */
    @Transactional
    public void expire(ApprovalRecord record) {
        claimService.fail(record.approvalId(), record.runId(),
                ApprovalRecord.STATUS_EXPIRED, "APPROVAL_EXPIRED",
                "审批已过期，任务未执行");
    }

    private ApprovalRecord findPendingByRun(String runId) {
        var rows = jdbcTemplate.query(
                "SELECT approval_id, run_id, user_id, agent_type, step, primary_tool, "
                        + "tool_count, args_hash, summary_text, status, resume_state, "
                        + "resume_attempts, claimed_epoch, decided_at, expires_at "
                        + "FROM agent_approval WHERE run_id = ? AND status = 'PENDING' "
                        + "ORDER BY created_at DESC LIMIT 1",
                ApprovalRecord.rowMapper(),
                runId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }
}
