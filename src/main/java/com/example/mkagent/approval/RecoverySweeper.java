package com.example.mkagent.approval;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * 启动收尾扫描（M3 批次 4B）：把上一次进程遗留的执行记录收成**可解释**的状态。
 *
 * <h2>只做两件事</h2>
 *
 * <ul>
 *   <li><b>R1</b>：{@code resume_state='CLAIMED'} → 退回 {@code PENDING_RESUME}（等用户手动
 *       {@code /resume}），<b>不</b>自动重执行；</li>
 *   <li><b>R3</b>：{@code agent_run.state='RUNNING'} 且没有本进程执行句柄 → {@code FAILED} +
 *       {@code PROCESS_RESTART}，并让对应审批进入既有失败子状态。</li>
 * </ul>
 *
 * 正常等待（{@code PENDING} / {@code PENDING_RESUME}）与已终态记录一律不碰；
 * 组合不符合不变量的记录**不猜修复值**，只隔离并计入摘要。
 *
 * <h2>为什么这样是安全的</h2>
 *
 * 前提不是"注册表里没有句柄"，而是**门禁**：{@link RecoveryGate} 在容器启动期就是关闭的，
 * 扫描期间本进程不可能创建新任务、领取或启动执行。因此"库里有 RUNNING、进程里没有句柄"
 * 在本进程内是自洽的；注册表检查只是第二道保险（且只对注册了句柄的 Agent 有效）。
 *
 * 这条推理**不跨进程**：本类不提供租约/心跳，也没有 owner 字段，
 * 所以多实例或部署约定不明确时不能启用（见 {@link ApprovalProperties#isRecoveryEnabled()}）。
 *
 * <h2>进展与结束</h2>
 *
 * 每遍用**主键游标**分页（{@code id > cursor ORDER BY id}），游标在处理每条之前推进，
 * 因此一条坏记录不会反复占满批次。结束条件只有两个：
 * 游标读到结尾，且没有暂时失败的记录。达到行数/墙钟预算即按"未完成"结束，
 * 调用方必须保持门禁关闭——本批没有周期扫描，不存在"下一轮"。
 */
@Component
public class RecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);

    /**
     * 中断收尾的固定文案：只说明"执行被中断"，不声称副作用不存在。
     */
    static final String PROCESS_RESTART_MESSAGE =
            "执行被进程重启中断，此前已提交的操作可能仍然有效，请核对";

    private static final String STATE_RUNNING = "RUNNING";

    private static final String RESUME_CLAIMED = "CLAIMED";

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate transactionTemplate;

    private final ApprovalClaimService claimService;

    private final ApprovalProperties properties;

    private final List<BaseAgent> agents;

    private final DataSource dataSource;

    private volatile Boolean postgres;

    public RecoverySweeper(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ApprovalClaimService claimService,
            ApprovalProperties properties,
            List<BaseAgent> agents,
            DataSource dataSource
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.claimService = claimService;
        this.properties = properties;
        this.agents = agents == null ? List.of() : agents;
        this.dataSource = dataSource;

        /*
         * 每条记录一个独立短事务：一条坏记录不能拖垮整批，也不能长时间持锁。
         * 用 TransactionTemplate 而不是给本类方法加 @Transactional——
         * 同类自调用不走代理，注解会静默失效。
         */
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 执行一次扫描。调用方（启动 runner）据 {@link Summary#completed()} 决定是否开放门禁。
     */
    public Summary sweepOnce(Trigger trigger) {
        long deadlineNanos = System.nanoTime()
                + Math.max(1L, properties.getRecoveryMaxDuration().toNanos());

        Counters counters = new Counters();

        boolean claimedDone = sweepClaimed(deadlineNanos, counters);

        /*
         * 第一遍没走完（预算/上限）就不再开第二遍：
         * 预算已经用尽，硬跑只会让"未完成"更不透明。
         */
        boolean runningDone = claimedDone && sweepRunning(deadlineNanos, counters);

        boolean completed = claimedDone && runningDone && counters.transientFailed == 0;

        String reason = completed
                ? "COMPLETED"
                : !claimedDone || !runningDone ? "BUDGET_OR_LIMIT_EXHAUSTED" : "TRANSIENT_FAILURES";

        Summary summary = new Summary(
                trigger,
                counters.visited,
                counters.reclaimed,
                counters.interrupted,
                counters.skippedLive,
                counters.skippedChanged,
                counters.isolated,
                counters.transientFailed,
                countRemaining(),
                completed,
                reason
        );

        if (completed) {
            log.info("[recovery] 启动收尾完成：{}", summary);
        } else {
            log.error("[recovery] 启动收尾未完成（执行入口保持关闭）：{}", summary);
        }
        if (summary.isolated() > 0) {
            log.warn("[recovery] 有 {} 条记录状态组合不一致，已隔离未修改，需要人工核对",
                    summary.isolated());
        }
        return summary;
    }

    // ==================== R1：CLAIMED 回收 ====================

    private boolean sweepClaimed(long deadlineNanos, Counters counters) {
        String cursor = "";

        while (true) {
            if (!hasBudget(deadlineNanos, counters)) {
                return false;
            }

            List<ClaimedRow> batch = jdbcTemplate.query(
                    "SELECT approval_id, run_id, claimed_epoch FROM agent_approval "
                            + "WHERE resume_state = 'CLAIMED' AND approval_id > ? "
                            + "ORDER BY approval_id LIMIT ?",
                    (rs, index) -> new ClaimedRow(
                            rs.getString("approval_id"),
                            rs.getString("run_id"),
                            (Integer) rs.getObject("claimed_epoch")),
                    cursor,
                    batchSize()
            );

            if (batch.isEmpty()) {
                return true;
            }

            for (ClaimedRow row : batch) {
                /*
                 * 游标先推进再处理：即使这一条抛异常，下一轮也不会从它重新开始，
                 * 否则一条坏记录会永远占住整个批次。
                 */
                cursor = row.approvalId();

                if (!hasBudget(deadlineNanos, counters)) {
                    return false;
                }

                processClaimed(row, counters);
                counters.visited++;
            }

            if (batch.size() < batchSize()) {
                return true;
            }
        }
    }

    private void processClaimed(ClaimedRow row, Counters counters) {
        if (row.claimedEpoch() == null) {
            counters.isolated++;
            log.warn("[recovery] CLAIMED 记录缺少 claimed_epoch，无法判定代次，隔离：approvalId={}",
                    row.approvalId());
            return;
        }

        if (hasLiveHandle(row.runId())) {
            counters.skippedLive++;
            log.info("[recovery] 本进程仍持有执行句柄，跳过：runId={}", row.runId());
            return;
        }

        try {
            ApprovalClaimService.CompensateResult result = claimService.compensate(
                    row.approvalId(), row.runId(), row.claimedEpoch());

            switch (result) {
                case RECLAIMED, ALREADY_RECLAIMED -> {
                    counters.reclaimed++;
                    log.info("[recovery] CLAIMED 已回收为 PENDING_RESUME：approvalId={}, runId={}, "
                                    + "epoch={}, result={}",
                            row.approvalId(), row.runId(), row.claimedEpoch(), result);
                }
                case NOT_CLAIMED -> {
                    counters.skippedChanged++;
                    log.info("[recovery] 领取已失效（已被启动或接管），不回退：approvalId={}, runId={}",
                            row.approvalId(), row.runId());
                }
            }
        } catch (IllegalStateException e) {
            /*
             * 状态组合冲突：审批退回了、run 却不是"同代次的 RUNNING"。
             * 补偿已整体回滚（同事务），这里只隔离，不猜修复值。
             */
            counters.isolated++;
            log.warn("[recovery] 回收遇到不一致状态，已回滚并隔离：approvalId={}, runId={}, reason={}",
                    row.approvalId(), row.runId(), e.getMessage());
        } catch (RuntimeException e) {
            counters.transientFailed++;
            log.error("[recovery] 回收暂时失败，保留候选：approvalId={}, runId={}",
                    row.approvalId(), row.runId(), e);
        }
    }

    // ==================== R3：RUNNING 中断收尾 ====================

    private boolean sweepRunning(long deadlineNanos, Counters counters) {
        String cursor = "";

        while (true) {
            if (!hasBudget(deadlineNanos, counters)) {
                return false;
            }

            List<RunningRow> batch = jdbcTemplate.query(
                    "SELECT run_id, execution_epoch FROM agent_run "
                            + "WHERE state = 'RUNNING' AND run_id > ? "
                            + "ORDER BY run_id LIMIT ?",
                    (rs, index) -> new RunningRow(
                            rs.getString("run_id"),
                            rs.getInt("execution_epoch")),
                    cursor,
                    batchSize()
            );

            if (batch.isEmpty()) {
                return true;
            }

            for (RunningRow row : batch) {
                cursor = row.runId();

                if (!hasBudget(deadlineNanos, counters)) {
                    return false;
                }

                processRunning(row, counters);
                counters.visited++;
            }

            if (batch.size() < batchSize()) {
                return true;
            }
        }
    }

    private void processRunning(RunningRow row, Counters counters) {
        if (hasLiveHandle(row.runId())) {
            counters.skippedLive++;
            log.info("[recovery] 本进程仍持有执行句柄，跳过：runId={}", row.runId());
            return;
        }

        Outcome outcome;
        try {
            outcome = transactionTemplate.execute(status -> closeOrphan(row, status));
        } catch (RuntimeException e) {
            counters.transientFailed++;
            log.error("[recovery] 中断收尾暂时失败，事务已回滚：runId={}", row.runId(), e);
            return;
        }

        if (outcome == null) {
            counters.transientFailed++;
            log.error("[recovery] 中断收尾没有返回结果，按暂时失败处理：runId={}", row.runId());
            return;
        }

        switch (outcome) {
            case INTERRUPTED -> {
                counters.interrupted++;
                log.warn("[recovery] 遗留 RUNNING 已按中断收尾：runId={}, epoch={}",
                        row.runId(), row.epoch());
            }
            case SKIPPED_CHANGED -> counters.skippedChanged++;
            case ISOLATED -> counters.isolated++;
        }
    }

    /**
     * 单条记录的事务体：重新校验候选 → 条件更新 → 同事务收尾审批。
     *
     * 不信任扫描快照：run 的状态与代次都在事务里重读一次，
     * 真正的裁决仍然交给条件更新（0 行即说明别人先改了）。
     */
    private Outcome closeOrphan(RunningRow row, TransactionStatus status) {
        applyLockTimeout();

        List<RunSnapshot> runs = jdbcTemplate.query(
                "SELECT state, execution_epoch FROM agent_run WHERE run_id = ?",
                (rs, index) -> new RunSnapshot(rs.getString("state"), rs.getInt("execution_epoch")),
                row.runId()
        );

        if (runs.isEmpty()) {
            return Outcome.SKIPPED_CHANGED;
        }

        RunSnapshot run = runs.get(0);
        if (!STATE_RUNNING.equals(run.state()) || run.epoch() != row.epoch()) {
            return Outcome.SKIPPED_CHANGED;
        }

        List<ApprovalSnapshot> approvals = jdbcTemplate.query(
                "SELECT approval_id, resume_state, claimed_epoch FROM agent_approval "
                        + "WHERE run_id = ? AND resume_state IN ('CLAIMED', 'RUNNING') "
                        + "ORDER BY created_at DESC LIMIT 1",
                (rs, index) -> new ApprovalSnapshot(
                        rs.getString("approval_id"),
                        rs.getString("resume_state"),
                        (Integer) rs.getObject("claimed_epoch")),
                row.runId()
        );

        ApprovalSnapshot approval = approvals.isEmpty() ? null : approvals.get(0);

        /*
         * CLAIMED 归 R1 管：任务还没越过启动门禁，不能当"已开始执行"收尾。
         * R1 已经跑过一遍，这里仍然可能是它跳过的记录（例如本进程有句柄）——
         * 无论如何都不该由 R3 动手。
         */
        if (approval != null && RESUME_CLAIMED.equals(approval.resumeState())) {
            return Outcome.SKIPPED_CHANGED;
        }

        if (approval != null) {
            /*
             * 审批在 RUNNING，但代次对不上：组合不满足不变量，不猜谁对。
             */
            if (approval.claimedEpoch() == null || approval.claimedEpoch() != run.epoch()) {
                log.warn("[recovery] 审批代次与任务代次不一致，隔离：runId={}, runEpoch={}, "
                                + "approvalId={}, claimedEpoch={}",
                        row.runId(), run.epoch(), approval.approvalId(), approval.claimedEpoch());
                return Outcome.ISOLATED;
            }
        }

        try {
            boolean interrupted = claimService.failInterrupted(
                    approval == null ? null : approval.approvalId(),
                    row.runId(),
                    run.epoch(),
                    PROCESS_RESTART_MESSAGE
            );
            return interrupted ? Outcome.INTERRUPTED : Outcome.SKIPPED_CHANGED;
        } catch (IllegalStateException e) {
            // 审批收尾未命中且不是幂等结果：回滚 run 终态，隔离并留痕
            status.setRollbackOnly();
            log.warn("[recovery] 中断收尾遇到不一致状态，已回滚并隔离：runId={}, reason={}",
                    row.runId(), e.getMessage());
            return Outcome.ISOLATED;
        }
    }

    // ==================== 公共设施 ====================

    /**
     * 本进程是否仍有该 run 的执行句柄。
     *
     * 只覆盖注册了句柄的 Agent（当前是 MkManus）。真正的保护是门禁：
     * 扫描期间不允许创建执行，因此"没有句柄"才成立；
     * 这个检查是第二道保险，不能单独作为"其他实例已退出"的证据。
     */
    private boolean hasLiveHandle(String runId) {
        for (BaseAgent agent : agents) {
            AgentTaskRegistry registry = agent.getTaskRegistry();
            if (registry != null && registry.get(runId) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBudget(long deadlineNanos, Counters counters) {
        if (counters.visited >= Math.max(1, properties.getRecoveryMaxRows())) {
            return false;
        }
        return System.nanoTime() < deadlineNanos;
    }

    private int batchSize() {
        return Math.max(1, properties.getRecoveryBatchSize());
    }

    /**
     * PostgreSQL 上给单条事务设锁等待上限；H2 没有 {@code SET LOCAL}，直接跳过。
     *
     * PG 默认 {@code lock_timeout=0} 表示无限等，扫描器撞上一条长时间持有
     * {@code agent_run} 行锁的写事务就会卡死整批，所以必须有界。
     *
     * <p>包级可见是为了能在没有真实 PG 的环境里直接断言"非 PG 不发这条语句"
     * 以及 PG 分支拼出的语句内容——这只证明语句形状，不证明 PG 的锁等待语义。
     */
    void applyLockTimeout() {
        if (!isPostgres()) {
            return;
        }
        long millis = Math.max(1L, properties.getRecoveryLockTimeout().toMillis());
        // 值来自配置且已解析为 long，不存在注入面；SET LOCAL 不支持占位符绑定。
        jdbcTemplate.execute("SET LOCAL lock_timeout = " + millis);
    }

    private boolean isPostgres() {
        Boolean cached = postgres;
        if (cached != null) {
            return cached;
        }

        boolean detected = false;
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            detected = product != null
                    && product.toLowerCase(Locale.ROOT).contains("postgresql");
        } catch (SQLException e) {
            log.warn("[recovery] 无法探测数据库类型，跳过事务级锁等待设置：{}", e.getMessage());
        }

        postgres = detected;
        return detected;
    }

    /**
     * 扫描结束后仍有候选的数量（诊断用；不一致记录会一直计入，这是有意的）。
     */
    private int countRemaining() {
        Integer claimed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_approval WHERE resume_state = 'CLAIMED'", Integer.class);
        Integer running = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE state = 'RUNNING'", Integer.class);
        return (claimed == null ? 0 : claimed) + (running == null ? 0 : running);
    }

    /**
     * 扫描触发方式。本批只有启动一次，没有周期扫描。
     */
    public enum Trigger {
        STARTUP
    }

    private enum Outcome {
        INTERRUPTED,
        SKIPPED_CHANGED,
        ISOLATED
    }

    /**
     * 一次扫描的完整结果。{@code completed=false} 时调用方**不得**开放执行入口。
     */
    public record Summary(
            Trigger trigger,
            int visited,
            int reclaimed,
            int interrupted,
            int skippedLive,
            int skippedChanged,
            int isolated,
            int transientFailed,
            int remaining,
            boolean completed,
            String reason
    ) {
    }

    private static final class Counters {
        private int visited;
        private int reclaimed;
        private int interrupted;
        private int skippedLive;
        private int skippedChanged;
        private int isolated;
        private int transientFailed;
    }

    private record ClaimedRow(String approvalId, String runId, Integer claimedEpoch) {
    }

    private record RunningRow(String runId, int epoch) {
    }

    private record RunSnapshot(String state, int epoch) {
    }

    private record ApprovalSnapshot(String approvalId, String resumeState, Integer claimedEpoch) {
    }
}
