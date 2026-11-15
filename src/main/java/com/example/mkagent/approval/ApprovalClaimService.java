package com.example.mkagent.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 审批记录的"执行权"状态机（批次 2）。
 *
 * 只管三件事：领取、启动、收尾（含失败与补偿），**不依赖任何 Agent 实例**。
 * 这样 BaseAgent 可以在任务线程里安全地调用它做启动标记，
 * 而不会和"负责拉起 Agent 的编排器"形成循环依赖。
 *
 * 所有方法都是条件更新，返回受影响行数：
 * 0 行 = 别人先改了状态，调用方必须按失败处理，不能假设自己赢了。
 */
@Service
public class ApprovalClaimService {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalClaimService.class);

    private final JdbcTemplate jdbcTemplate;

    public ApprovalClaimService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取一条审批记录，不存在返回 null。
     */
    public ApprovalRecord find(String approvalId) {
        List<ApprovalRecord> rows = jdbcTemplate.query(
                "SELECT approval_id, run_id, user_id, agent_type, step, primary_tool, "
                        + "tool_count, args_hash, summary_text, status, resume_state, "
                        + "resume_attempts, claimed_epoch, decided_at, expires_at "
                        + "FROM agent_approval WHERE approval_id = ?",
                ApprovalRecord.rowMapper(),
                approvalId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 读取某任务的检查点 JSON，不存在返回 null。
     */
    public String findCheckpoint(String runId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT payload_json FROM agent_checkpoint WHERE run_id = ?",
                String.class, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 读取任务的当前执行代次，不存在返回 null。
     */
    public Integer findRunEpoch(String runId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT execution_epoch FROM agent_run WHERE run_id = ?",
                Integer.class, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 读取审批摘要 JSON（查询接口展示用），不存在返回 null。
     */
    public String findSummaryJson(String approvalId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT summary_json FROM agent_approval WHERE approval_id = ?",
                String.class, approvalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 领取：把"已决定但未启动"变成"已领取"。
     *
     * 两条 CAS 在同一事务：
     * 1. approval `PENDING_RESUME → CLAIMED`（同时绑定本次代次）；
     * 2. run `WAITING_APPROVAL → RUNNING`，代次 +1。
     *
     * 任一 0 行就抛异常回滚——不允许出现"approval 已领取、run 还没动"的半截状态。
     *
     * @return 本次领取绑定的执行代次
     */
    @Transactional
    public int claim(ApprovalRecord record) {
        Integer currentEpoch = findRunEpoch(record.runId());
        if (currentEpoch == null) {
            throw new IllegalStateException("领取失败：任务不存在，runId=" + record.runId());
        }
        int nextEpoch = currentEpoch + 1;

        int approvalRows = jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET resume_state = 'CLAIMED', claimed_epoch = ?, "
                        + "    updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? "
                        + "  AND status IN ('APPROVED', 'REJECTED') "
                        + "  AND resume_state = 'PENDING_RESUME'",
                nextEpoch, record.approvalId()
        );

        if (approvalRows != 1) {
            throw new IllegalStateException(
                    "领取失败：审批状态已变化，approvalId=" + record.approvalId());
        }

        int runRows = jdbcTemplate.update(
                "UPDATE agent_run "
                        + "SET state = 'RUNNING', execution_epoch = ?, "
                        + "    updated_at = CURRENT_TIMESTAMP "
                        + "WHERE run_id = ? AND state = 'WAITING_APPROVAL' "
                        + "  AND execution_epoch = ?",
                nextEpoch, record.runId(), currentEpoch
        );

        if (runRows != 1) {
            throw new IllegalStateException(
                    "领取失败：任务不在等待审批状态，runId=" + record.runId());
        }

        log.info("审批已领取：approvalId={}, runId={}, epoch={}",
                record.approvalId(), record.runId(), nextEpoch);

        return nextEpoch;
    }

    /**
     * 启动标记：后台线程执行任何模型/工具之前的**第一件事**。
     *
     * 条件带上 approvalId + resume_state + claimed_epoch：
     * - 0 行说明这次领取已失效（被补偿回 PENDING_RESUME、或已被别人启动）；
     * - 调用方必须立刻退出，一个模型调用、一个工具都不能执行。
     *
     * 本方法独立事务：返回即已提交，之后才开始执行。
     *
     * <b>异常语义（不要把结论说过头）</b>：
     * 抛出异常只说明"应用没有拿到这次写入的确认"，**不**等于
     * "库里一定还是 CLAIMED"。提交可能已经生效而确认在网络上丢失，
     * 也可能连接在提交前就断了——调用方无法从异常本身分辨这两种情况。
     * 因此异常后的处理边界是：
     * 1. 本次执行不继续（不调用模型、不执行工具）；
     * 2. 补偿只对"仍是 CLAIMED 且代次匹配"的记录生效；
     * 3. 若实际已变成 RUNNING，补偿影响 0 行并整体放弃；
     * 4. 状态不确定的记录不伪造完成，留给后续恢复机制按实际状态收尾。
     */
    @Transactional
    public boolean markStarted(String approvalId, int epoch) {
        int rows = jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET resume_state = 'RUNNING', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? AND resume_state = 'CLAIMED' "
                        + "  AND claimed_epoch = ?",
                approvalId, epoch
        );

        if (rows == 1) {
            log.info("审批执行已启动：approvalId={}, epoch={}", approvalId, epoch);
        } else {
            log.warn("启动标记未命中（领取已失效）：approvalId={}, epoch={}", approvalId, epoch);
        }
        return rows == 1;
    }

    /**
     * 补偿：启动失败后回退到可重试状态。
     *
     * <b>只吃 CLAIMED，不吃 RUNNING</b>。
     *
     * 为什么这样就能证明"尚未开始业务执行"：
     * 启动标记（CLAIMED→RUNNING）是任务体的**第一条语句**，
     * 而所有模型调用与业务工具都在它之后。因此
     * {@code resume_state='CLAIMED' AND claimed_epoch=本次代次}
     * 在数据库层面等价于"本次领取还没有越过执行起点"。
     * 一旦标记提交（RUNNING），本条 CAS 影响 0 行，补偿整体放弃——
     * 已经开始执行的任务绝不会被回退成"等待审批"。
     *
     * 顺序也不能反：先 CAS 审批、确认赢了再回退 run。
     * 若反过来先回退 run，审批那条 CAS 失败时就会留下
     * "run 等待审批、审批还在执行"的错位状态。
     *
     * <b>两步必须同生共死</b>：第二步没命中时不能"记一条日志就提交"。
     * 只有一种 0 行是合法的——run 已经处于本次补偿想达成的目标状态
     * （{@code WAITING_APPROVAL} 且代次仍是本次代次），说明别人已经补偿过，
     * 重复执行没有副作用，按幂等接受。其余 0 行（run 已终态、代次已前进、
     * 行已不存在）说明执行阶段已越过本次领取，此时抛异常回滚第一步，
     * 让两条记录一起停在"执行中"，交给后续恢复机制按实际状态收尾——
     * 绝不提交"审批退回、run 没退"的半截补偿。
     *
     * 代次**不回退**：epoch 只增不减，回退会让"旧代次迟到的写入"重新变得有效。
     *
     * @return 本次补偿的实际结果。调用方（启动收尾）必须据此区分
     *         "真的退回去了"、"别人已经退过（等价态）"与"这次领取已经越过了执行起点"，
     *         不能只看第一条更新是否 0 行就宣称状态一致
     */
    @Transactional
    public CompensateResult compensate(String approvalId, String runId, int epoch) {
        int approvalRows = jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET resume_state = 'PENDING_RESUME', claimed_epoch = NULL, "
                        + "    updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? AND resume_state = 'CLAIMED' "
                        + "  AND claimed_epoch = ?",
                approvalId, epoch
        );

        if (approvalRows != 1) {
            log.warn("审批补偿已跳过：执行阶段已越过 CLAIMED，不回退运行状态。"
                            + "approvalId={}, runId={}, epoch={}",
                    approvalId, runId, epoch);
            return CompensateResult.NOT_CLAIMED;
        }

        int runRows = jdbcTemplate.update(
                "UPDATE agent_run "
                        + "SET state = 'WAITING_APPROVAL', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE run_id = ? AND state = 'RUNNING' AND execution_epoch = ?",
                runId, epoch
        );

        if (runRows == 1) {
            log.warn("审批恢复已补偿：approvalId={}, runId={}, epoch={}, runRows={}",
                    approvalId, runId, epoch, runRows);
            return CompensateResult.RECLAIMED;
        }

        RunState run = findRunState(runId);

        if (run != null
                && "WAITING_APPROVAL".equals(run.state())
                && run.epoch() == epoch) {
            /*
             * 等价目标状态：另一个补偿已经先一步把 run 退回等待审批。
             * 本次再退一次没有任何副作用，按幂等接受。
             */
            log.warn("审批补偿：任务已处于等价目标状态，按幂等处理。"
                            + "approvalId={}, runId={}, epoch={}",
                    approvalId, runId, epoch);
            return CompensateResult.ALREADY_RECLAIMED;
        }

        /*
         * 其余情况一律视为"状态已冲突"：run 已终态 / 代次已前进 / 行已不存在。
         * 抛异常回滚上面的审批 CAS，绝不提交半截补偿。
         */
        throw new IllegalStateException(
                "补偿失败：任务状态已变化，不能单独回退审批。approvalId=" + approvalId
                        + ", runId=" + runId + ", epoch=" + epoch
                        + ", run=" + run);
    }

    /**
     * 补偿的实际结果（批次 4B：启动收尾需要区分，不能只看行数猜）。
     */
    public enum CompensateResult {

        /** 本次把审批与任务都退回了可重试状态。 */
        RECLAIMED,

        /** 审批已退回，任务本来就处于等价目标状态（别人先补偿过），按幂等接受。 */
        ALREADY_RECLAIMED,

        /** 审批已不在"本次代次的 CLAIMED"，本次什么都没改。 */
        NOT_CLAIMED
    }

    /**
     * 中断收尾：把"已开始执行但执行者已消失"的记录明确判为失败（批次 4B）。
     *
     * <h2>它证明什么、不证明什么</h2>
     *
     * 只证明"这次执行不会再继续"：写 `FAILED + PROCESS_RESTART + finished_at`。
     * **不**证明此前的业务写入不存在——同库事务可能已经提交过工具副作用，
     * 因此不删幂等凭证、不删业务数据、不写 `final_answer`、也不声称回滚。
     * 用户文案必须写成"可能已生效，请核对"。
     *
     * <h2>顺序与 0 行语义</h2>
     *
     * 与 {@link ApprovalExecutionCompletionService} 保持一致：**先 run、后 approval**。
     * 两条写入同事务，因此不会留下"run 已 FAILED、审批还在 RUNNING"的中间态。
     *
     * - run 0 行：状态或代次已变（别人先收尾了），本次什么都不写，返回 false；
     * - approval 0 行：只有"已经是 FAILED"按幂等接受，其余抛异常回滚 run 终态。
     *
     * @param approvalId 关联的恢复审批；普通首次执行没有审批，传 {@code null}
     * @return true 表示本次确实收尾了这条任务
     */
    @Transactional
    public boolean failInterrupted(
            String approvalId, String runId, int epoch, String message) {
        int runRows = jdbcTemplate.update(
                "UPDATE agent_run "
                        + "SET state = 'FAILED', error_code = 'PROCESS_RESTART', "
                        + "    error_message = ?, finished_at = CURRENT_TIMESTAMP, "
                        + "    updated_at = CURRENT_TIMESTAMP "
                        + "WHERE run_id = ? AND state = 'RUNNING' AND execution_epoch = ?",
                message, runId, epoch
        );

        if (runRows != 1) {
            log.warn("中断收尾未命中（状态或代次已变化）：runId={}, epoch={}, rows={}",
                    runId, epoch, runRows);
            return false;
        }

        if (approvalId == null) {
            log.warn("任务已按中断收尾（无关联审批）：runId={}, epoch={}", runId, epoch);
            return true;
        }

        int approvalRows = jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET resume_state = 'FAILED', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? AND resume_state = 'RUNNING' "
                        + "  AND claimed_epoch = ?",
                approvalId, epoch
        );

        if (approvalRows != 1) {
            ApprovalRecord record = find(approvalId);
            String resumeState = record == null ? null : record.resumeState();

            if (ApprovalRecord.RESUME_FAILED.equals(resumeState)) {
                log.warn("审批已是 FAILED（重复收尾），按幂等处理：runId={}, approvalId={}",
                        runId, approvalId);
                return true;
            }

            throw new IllegalStateException(
                    "中断收尾未命中审批，回滚 run 终态：runId=" + runId
                            + ", approvalId=" + approvalId
                            + ", resumeState=" + resumeState
                            + ", rows=" + approvalRows);
        }

        log.warn("任务已按中断收尾：runId={}, approvalId={}, epoch={}", runId, approvalId, epoch);
        return true;
    }

    /**
     * 收尾：本次审批的执行阶段结束（正常完成、再次暂停、或失败）。
     *
     * FINISHED 只表示"这条审批不再有执行权"，不代表业务一定成功——
     * 业务结果由工具结果与 agent_run 状态表达。
     *
     * @return 受影响行数。调用方在"run 终态已写成功"的事务里必须检查它：
     *         0 行意味着审批没有进入 FINISHED，若直接提交就会留下
     *         "run 已终态、审批还在执行"的矛盾，因此要由调用方决定回滚还是按幂等接受
     */
    @Transactional
    public int finish(String approvalId) {
        if (approvalId == null) {
            return 0;
        }
        int rows = jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET resume_state = 'FINISHED', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? AND resume_state IN ('RUNNING', 'CLAIMED')",
                approvalId
        );
        if (rows == 1) {
            log.info("审批执行阶段已收尾：approvalId={}", approvalId);
        }
        return rows;
    }

    /**
     * 读取任务的运行状态与代次（补偿/收尾时判断"是否已是等价目标状态"用）。
     *
     * 不存在返回 null。用行映射而不是 queryForMap：
     * H2 的列名大小写与 PostgreSQL 不一致，按列名取值才不会在测试库里取空。
     */
    public RunState findRunState(String runId) {
        List<RunState> rows = jdbcTemplate.query(
                "SELECT state, execution_epoch FROM agent_run WHERE run_id = ?",
                (rs, index) -> new RunState(rs.getString("state"), rs.getInt("execution_epoch")),
                runId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 任务的运行状态快照。
     */
    public record RunState(String state, int epoch) {
    }

    /**
     * 明确失败：启动期限到期 / 检查点校验不通过。
     *
     * 同时把 run 置为 FAILED 并写稳定原因码，
     * 避免出现"审批已失败、任务还挂在 WAITING_APPROVAL"的僵尸。
     */
    @Transactional
    public void fail(String approvalId, String runId, String approvalStatus,
                     String runErrorCode, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET status = ?, resume_state = 'FAILED', "
                        + "    updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? AND status IN ('PENDING', 'APPROVED', 'REJECTED')",
                approvalStatus, approvalId
        );

        jdbcTemplate.update(
                "UPDATE agent_run "
                        + "SET state = 'FAILED', error_code = ?, error_message = ?, "
                        + "    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE run_id = ? AND state = 'WAITING_APPROVAL'",
                runErrorCode, errorMessage, runId
        );

        log.warn("审批失败收尾：approvalId={}, runId={}, approvalStatus={}, runErrorCode={}",
                approvalId, runId, approvalStatus, runErrorCode);
    }
}
