package com.example.mkagent.approval;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动收尾扫描的行为证据（M3 批次 4B）。
 *
 * <p>全部走真实 H2 + 真实事务，断言的是**提交之后的最终状态**，不 mock 事务。
 *
 * <p>覆盖：
 * <ul>
 *   <li>R1：CLAIMED 回收，决定/代次/检查点保持不变；</li>
 *   <li>R1 与 markStarted 的两个顺序；</li>
 *   <li>R3：RUNNING 中断收尾，不动业务与幂等记录；</li>
 *   <li>正常等待、已终态、旧代次、不一致组合；</li>
 *   <li>两个扫描者重复处理；</li>
 *   <li>游标推进（坏记录不占满批次）与预算耗尽（未完成）。</li>
 * </ul>
 */
@HermeticSpringBootTest
@Timeout(120)
class RecoverySweeperTest {

    private static final String USER = "recovery-sweep-user";

    private static final String AGENT_TYPE = "MUSIC_USER";

    private static final AtomicLong IDS = new AtomicLong(9_500_000_000_000_000L);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecoverySweeper sweeper;

    @Autowired
    private ApprovalClaimService claimService;

    @Autowired
    private ApprovalProperties approvalProperties;

    @Autowired
    private AgentTaskRegistry taskRegistry;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private javax.sql.DataSource dataSource;

    private final List<String> createdRuns = new ArrayList<>();

    private final List<String> createdApprovals = new ArrayList<>();

    private int originalBatchSize;

    private int originalMaxRows;

    @BeforeEach
    void setUp() {
        originalBatchSize = approvalProperties.getRecoveryBatchSize();
        originalMaxRows = approvalProperties.getRecoveryMaxRows();
    }

    @AfterEach
    void tearDown() {
        approvalProperties.setRecoveryBatchSize(originalBatchSize);
        approvalProperties.setRecoveryMaxRows(originalMaxRows);

        for (String approvalId : createdApprovals) {
            jdbcTemplate.update("DELETE FROM agent_approval WHERE approval_id = ?", approvalId);
        }
        for (String runId : createdRuns) {
            taskRegistry.remove(runId, 0);
            taskRegistry.remove(runId, 1);
            taskRegistry.remove(runId, 2);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdApprovals.clear();
        createdRuns.clear();
    }

    // ==================== R1 ====================

    @Test
    @DisplayName("R1：CLAIMED 退回 PENDING_RESUME，用户决定/代次/检查点全部保持")
    void reclaimsClaimedAndPreservesDecision() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "CLAIMED", 1);
        insertCheckpoint(runId, "config-v1", "{\"marker\":\"keep-me\"}");

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(1, summary.reclaimed(), "应恰好回收一条，实际摘要：" + summary);
        assertTrue(summary.completed(), "没有遗留候选时应报告完成：" + summary);

        Map<String, Object> approval = approvalRow(approvalId);
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertNull(approval.get("claimed_epoch"), "回收必须清空 claimed_epoch");
        assertEquals("APPROVED", approval.get("status"), "用户决定不得被撤销");
        assertNotNull(approval.get("decided_at"));

        Map<String, Object> run = runRow(runId);
        assertEquals("WAITING_APPROVAL", run.get("state"));
        assertEquals(1, ((Number) run.get("execution_epoch")).intValue(),
                "回收不得回退代次（领取时已经 +1）");

        assertEquals(1, countCheckpoint(runId), "检查点不得被删除");
        assertEquals("{\"marker\":\"keep-me\"}",
                jdbcTemplate.queryForObject(
                        "SELECT payload_json FROM agent_checkpoint WHERE run_id = ?",
                        String.class, runId),
                "检查点内容不得被改写");
    }

    @Test
    @DisplayName("R1 回收后：旧执行者的 markStarted 影响 0 行，不会启动任何执行")
    void markStartedLosesAfterReclaim() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "CLAIMED", 1);

        sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertFalse(claimService.markStarted(approvalId, 1),
                "回收之后这次领取已经失效，启动门禁必须 0 行");
        assertEquals("PENDING_RESUME", approvalRow(approvalId).get("resume_state"));
        assertEquals("WAITING_APPROVAL", runRow(runId).get("state"));
    }

    @Test
    @DisplayName("markStarted 先赢：回收不把已启动的执行退回等待审批")
    void reclaimNeverRollsBackStartedExecution() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "CLAIMED", 1);

        assertTrue(claimService.markStarted(approvalId, 1), "启动门禁应当命中");

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(0, summary.reclaimed(), "已越过启动门禁的记录不得被回收：" + summary);
        assertFalse("PENDING_RESUME".equals(approvalRow(approvalId).get("resume_state")),
                "已启动的执行不得被退回 PENDING_RESUME");
        /*
         * 没有本地句柄 → R3 会把这条"已启动但执行者消失"的记录按中断收尾。
         * 这是正确语义：不是"回退",而是"明确判失败"。
         */
        assertEquals("FAILED", runRow(runId).get("state"));
        assertEquals("PROCESS_RESTART", runRow(runId).get("error_code"));
        assertEquals("FAILED", approvalRow(approvalId).get("resume_state"),
                "审批应进入既有失败子状态，而不是退回待恢复");
    }

    @Test
    @DisplayName("markStarted 先赢且本进程仍有句柄：扫描完全不碰它")
    void liveHandleIsNeverTouched() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);
        registerLiveHandle(runId, 1);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertTrue(summary.skippedLive() >= 1, "必须按'本进程仍在执行'跳过：" + summary);
        assertEquals("RUNNING", runRow(runId).get("state"));
        assertEquals("RUNNING", approvalRow(approvalId).get("resume_state"));
    }

    // ==================== R3 ====================

    @Test
    @DisplayName("R3：遗留 RUNNING 判 FAILED + PROCESS_RESTART，不动业务与幂等记录")
    void interruptedRunKeepsBusinessAndIdempotency() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        long playlistId = 9_500_000_000_000_777L;
        jdbcTemplate.update(
                "INSERT INTO music_playlist (id, user_id, name, description, created_at) "
                        + "VALUES (?, ?, ?, NULL, CURRENT_TIMESTAMP)",
                playlistId, USER, "收尾-已提交的歌单");

        String idemKey = "idem-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO music_tool_idempotency "
                        + "(user_id, idempotency_key, tool_name, request_hash, result_json, "
                        + " run_id, step, tool_call_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'create_playlist', 'hash', '{\"ok\":true}', ?, 1, 'c1', "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                USER, idemKey, runId);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(1, summary.interrupted(), "应恰好中断收尾一条：" + summary);

        Map<String, Object> run = runRow(runId);
        assertEquals("FAILED", run.get("state"));
        assertEquals("PROCESS_RESTART", run.get("error_code"));
        assertNotNull(run.get("finished_at"));
        assertNull(run.get("final_answer"), "不得伪造成功答案");
        String message = (String) run.get("error_message");
        assertNotNull(message);
        assertTrue(message.contains("可能仍然有效") && message.contains("核对"),
                "文案必须说明历史操作可能已生效，实际：" + message);

        assertEquals("FAILED", approvalRow(approvalId).get("resume_state"));
        assertEquals("APPROVED", approvalRow(approvalId).get("status"), "用户决定不得被改写");

        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM music_playlist WHERE id = ?", Integer.class, playlistId),
                "已提交的业务写入不得被删除");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM music_tool_idempotency WHERE idempotency_key = ?",
                        Integer.class, idemKey),
                "成功幂等凭证不得被删除");

        jdbcTemplate.update("DELETE FROM music_playlist WHERE id = ?", playlistId);
        jdbcTemplate.update("DELETE FROM music_tool_idempotency WHERE idempotency_key = ?", idemKey);
    }

    @Test
    @DisplayName("R3：普通首次执行（无审批）同样能被收尾")
    void interruptedRunWithoutApprovalIsClosed() {
        String runId = insertRun("RUNNING", 0);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(1, summary.interrupted(), "没有审批记录也要收尾：" + summary);
        assertEquals("FAILED", runRow(runId).get("state"));
        assertEquals("PROCESS_RESTART", runRow(runId).get("error_code"));
    }

    // ==================== 不误伤 ====================

    @Test
    @DisplayName("正常等待与已终态记录一律不动")
    void normalWaitingAndTerminalRowsUntouched() {
        String pendingRun = insertRun("WAITING_APPROVAL", 0);
        String pendingApproval = insertApproval(pendingRun, "PENDING", "PENDING_RESUME", null);

        String decidedRun = insertRun("WAITING_APPROVAL", 1);
        String decidedApproval = insertApproval(decidedRun, "APPROVED", "PENDING_RESUME", null);

        String succeededRun = insertRun("SUCCEEDED", 1);
        String finishedApproval = insertApproval(succeededRun, "APPROVED", "FINISHED", 1);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(0, summary.reclaimed() + summary.interrupted(),
                "正常等待与已终态都不该被处理：" + summary);

        assertEquals("WAITING_APPROVAL", runRow(pendingRun).get("state"));
        assertEquals("PENDING_RESUME", approvalRow(pendingApproval).get("resume_state"));
        assertEquals("WAITING_APPROVAL", runRow(decidedRun).get("state"));
        assertEquals("PENDING_RESUME", approvalRow(decidedApproval).get("resume_state"));
        assertEquals("SUCCEEDED", runRow(succeededRun).get("state"));
        assertEquals("FINISHED", approvalRow(finishedApproval).get("resume_state"));
    }

    @Test
    @DisplayName("不一致组合：隔离且不做任何修改")
    void inconsistentCombinationIsIsolated() {
        String runId = insertRun("RUNNING", 1);
        // 审批 claimed_epoch=2，run 是 1：谁对谁错无法判断，只能隔离
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 2);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(1, summary.isolated(), "应隔离一条不一致记录：" + summary);
        assertEquals("RUNNING", runRow(runId).get("state"), "不一致时不得猜修复值");
        assertEquals("RUNNING", approvalRow(approvalId).get("resume_state"));
    }

    @Test
    @DisplayName("旧代次：回收不修改已经前进代次的新执行")
    void oldEpochCannotTouchNewerExecution() {
        String runId = insertRun("RUNNING", 2);
        String approvalId = insertApproval(runId, "APPROVED", "CLAIMED", 1);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(0, summary.reclaimed(), "代次对不上时不得回收：" + summary);
        assertEquals(1, summary.isolated(), "应作为不一致隔离：" + summary);

        Map<String, Object> run = runRow(runId);
        assertEquals("RUNNING", run.get("state"));
        assertEquals(2, ((Number) run.get("execution_epoch")).intValue());
        assertEquals("CLAIMED", approvalRow(approvalId).get("resume_state"),
                "隔离时第一条更新必须回滚");
    }

    // ==================== 并发与进展 ====================

    @Test
    @DisplayName("两个扫描者同时处理同一条记录：只产生一次效果")
    void twoSweepersProduceSingleEffect() throws Exception {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "CLAIMED", 1);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<RecoverySweeper.Summary>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);
                }));
            }

            start.countDown();

            int reclaimed = 0;
            for (Future<RecoverySweeper.Summary> future : futures) {
                RecoverySweeper.Summary recovered = future.get(30, TimeUnit.SECONDS);
                reclaimed += recovered.reclaimed();
            }

            assertEquals(1, reclaimed,
                    "两条扫描者都自认为回收成功，说明存在重复处理；实际合计=" + reclaimed);
        } finally {
            pool.shutdownNow();
        }

        assertEquals("PENDING_RESUME", approvalRow(approvalId).get("resume_state"));
        assertEquals("WAITING_APPROVAL", runRow(runId).get("state"));
    }

    @Test
    @DisplayName("游标推进：一条坏记录不会占满批次，后面的记录仍被处理")
    void cursorAdvancesPastBadRecord() {
        approvalProperties.setRecoveryBatchSize(1);

        String badRun = insertRun("RUNNING", 2);
        String badApproval = insertApproval(badRun, "APPROVED", "CLAIMED", 1);

        String goodRunA = insertRun("RUNNING", 1);
        String goodApprovalA = insertApproval(goodRunA, "APPROVED", "CLAIMED", 1);

        String goodRunB = insertRun("RUNNING", 1);
        String goodApprovalB = insertApproval(goodRunB, "APPROVED", "CLAIMED", 1);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertTrue(summary.visited() >= 3, "三条都应被访问过：" + summary);
        assertEquals(2, summary.reclaimed(), "两条有效记录都要被回收：" + summary);
        assertEquals(1, summary.isolated(), "一条坏记录被隔离：" + summary);

        assertEquals("PENDING_RESUME", approvalRow(goodApprovalA).get("resume_state"));
        assertEquals("PENDING_RESUME", approvalRow(goodApprovalB).get("resume_state"));
        assertEquals("CLAIMED", approvalRow(badApproval).get("resume_state"),
                "坏记录保持原样，等人工核对");
    }

    @Test
    @DisplayName("预算耗尽：明确报告未完成，而不是假装扫完")
    void budgetExhaustionReportsIncomplete() {
        approvalProperties.setRecoveryMaxRows(1);

        String firstRun = insertRun("RUNNING", 1);
        insertApproval(firstRun, "APPROVED", "CLAIMED", 1);

        String secondRun = insertRun("RUNNING", 1);
        insertApproval(secondRun, "APPROVED", "CLAIMED", 1);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertFalse(summary.completed(), "还有候选没处理，不能报告完成：" + summary);
        assertEquals("BUDGET_OR_LIMIT_EXHAUSTED", summary.reason());
        assertTrue(summary.remaining() >= 1, "摘要必须显示仍有剩余候选：" + summary);
    }

    // ==================== 不调用模型/工具 ====================

    @Test
    @DisplayName("扫描本身不推进执行：步数、工具次数、轨迹都不变")
    void sweepDoesNotAdvanceExecution() {
        String runId = insertRun("RUNNING", 1);
        insertApproval(runId, "APPROVED", "RUNNING", 1);

        jdbcTemplate.update(
                "UPDATE agent_run SET current_step = 3, tool_call_count = 2, tool_trace = 'web_search' "
                        + "WHERE run_id = ?", runId);

        sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT current_step, tool_call_count, tool_trace FROM agent_run WHERE run_id = ?",
                runId);
        assertEquals(3, ((Number) run.get("current_step")).intValue());
        assertEquals(2, ((Number) run.get("tool_call_count")).intValue());
        assertEquals("web_search", run.get("tool_trace"));
    }

    // ==================== 单行事务边界 ====================

    @Test
    @DisplayName("多行：后一行抛非状态类异常 → 只回滚该行，前一行结果保留，摘要报未完成")
    void secondRowFailureRollsBackOnlyThatRow() {
        String first = insertRun("RUNNING", 1);
        String second = insertRun("RUNNING", 1);

        /*
         * 扫描按 run_id 排序（UUID 字典序），所以先确定处理顺序，
         * 再让"后处理的那一行"失败——否则断言会依赖随机 UUID。
         */
        List<String> ordered = new ArrayList<>(List.of(first, second));
        java.util.Collections.sort(ordered);
        String ok = ordered.get(0);
        String failing = ordered.get(1);

        /*
         * 不用 Mockito：容器里的 claimService 是 @Transactional 代理，spy 它会在
         * doThrow().when(...) 上报 NotAMock。这里用子类只改一个方法，
         * 其余行为仍走真实 SQL（事务由扫描器的 TransactionTemplate 提供）。
         */
        ApprovalClaimService sabotaged = new ApprovalClaimService(jdbcTemplate) {
            @Override
            public boolean failInterrupted(
                    String approvalId, String runId, int epoch, String message) {
                if (failing.equals(runId)) {
                    throw new org.springframework.dao.DataAccessResourceFailureException(
                            "注入的数据库故障");
                }
                return super.failInterrupted(approvalId, runId, epoch, message);
            }
        };

        RecoverySweeper local = new RecoverySweeper(
                jdbcTemplate, transactionManager, sabotaged, approvalProperties,
                List.of(), dataSource);

        RecoverySweeper.Summary summary = local.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals("FAILED", runRow(ok).get("state"), "先处理的那一行必须生效");
        assertEquals("PROCESS_RESTART", runRow(ok).get("error_code"));

        assertEquals("RUNNING", runRow(failing).get("state"),
                "失败行必须整体回滚，不能留下半截终态");
        assertNull(runRow(failing).get("finished_at"));

        assertEquals(1, summary.interrupted());
        assertEquals(1, summary.transientFailed());
        assertFalse(summary.completed(), "有暂时失败时不能报完成：" + summary);
        assertEquals("TRANSIENT_FAILURES", summary.reason());
    }

    // ==================== 辅助 ====================

    private void registerLiveHandle(String runId, int epoch) {
        AgentRunContext ctx = new AgentRunContext(UUID.fromString(runId));
        ctx.setUserId(USER);
        ctx.setExecutionEpoch(epoch);
        taskRegistry.register(runId, new RunningAgentTask(ctx, new CompletableFuture<>(), epoch));
    }

    private String insertRun(String state, int epoch) {
        String runId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO agent_run (id, run_id, user_id, agent_type, user_prompt, state, "
                        + " current_step, tool_call_count, started_at, created_at, updated_at, "
                        + " execution_epoch, active_elapsed_millis, approval_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 0, CURRENT_TIMESTAMP, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0, 1)",
                IDS.incrementAndGet(), runId, USER, AGENT_TYPE, "收尾测试", state, epoch
        );

        createdRuns.add(runId);
        return runId;
    }

    private String insertApproval(
            String runId, String status, String resumeState, Integer claimedEpoch) {
        String approvalId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO agent_approval (approval_id, run_id, user_id, agent_type, step, "
                        + " primary_tool, tool_count, args_hash, summary_text, status, "
                        + " resume_state, resume_attempts, claimed_epoch, decided_at, expires_at, "
                        + " created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 1, 'create_playlist', 1, 'test-hash', "
                        + " '收尾摘要', ?, ?, 0, ?, CURRENT_TIMESTAMP, ?, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                approvalId, runId, USER, AGENT_TYPE, status, resumeState,
                claimedEpoch, LocalDateTime.now().plusHours(1)
        );

        createdApprovals.add(approvalId);
        return approvalId;
    }

    private void insertCheckpoint(String runId, String configVersion, String payload) {
        jdbcTemplate.update(
                "INSERT INTO agent_checkpoint (run_id, schema_version, config_version, payload_json, "
                        + " created_at, updated_at) "
                        + "VALUES (?, 1, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                runId, configVersion, payload);
    }

    private int countCheckpoint(String runId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_checkpoint WHERE run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> runRow(String runId) {
        return jdbcTemplate.queryForMap(
                "SELECT state, finished_at, execution_epoch, error_code, error_message, final_answer "
                        + "FROM agent_run WHERE run_id = ?", runId);
    }

    private Map<String, Object> approvalRow(String approvalId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, resume_state, claimed_epoch, decided_at FROM agent_approval "
                        + "WHERE approval_id = ?", approvalId);
    }

    /**
     * MCP 自动装配在 hermetic 测试里被关掉，这里补一个空工具提供者，
     * 否则 chatApp 无法注入（与其它上下文测试一致）。
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class EmptyToolCallbackProvider {

        @org.springframework.context.annotation.Bean
        org.springframework.ai.tool.ToolCallbackProvider toolCallbackProvider() {
            return () -> new org.springframework.ai.tool.ToolCallback[0];
        }
    }
}
