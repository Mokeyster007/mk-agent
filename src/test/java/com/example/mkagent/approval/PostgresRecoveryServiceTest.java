package com.example.mkagent.approval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 专项：**真实 Spring 事务 + 真实服务 Bean** 下的收尾与竞争行为（M3 批次 4B 补证）。
 *
 * <h2>与 {@link PostgresRecoveryConcurrencyTest} 的区别</h2>
 *
 * 那个类是**纯 JDBC 探针**：自建最小表，只证明 PG 的原语语义
 * （{@code SET LOCAL lock_timeout} 有界、被阻塞 UPDATE 重新求值）。
 * 本类走**真实 {@code RecoverySweeper} / {@code ApprovalClaimService} /
 * {@code ApprovalCompletionService} / {@code ApprovalPauseService}** 与 Spring 事务代理，
 * 断言提交之后的最终状态。两类证据不能互相替代：
 * 探针证明"数据库会这样"，本类证明"我们的代码在这套数据库上确实这样"。
 *
 * <h2>隔离</h2>
 *
 * 只连独立探针库（{@code MKAGENT_PG_URL}，默认 {@code .../mk_probe}），
 * {@link #requireProbeDatabase()} 会先校验库名以 {@code mk_probe} 开头，
 * 防止误连业务库；每个用例前后清空 {@code agent_*} 三张表。
 *
 * <h2>运行方式</h2>
 *
 * <pre>
 * MKAGENT_PG_URL=jdbc:postgresql://127.0.0.1:55432/mk_probe \
 * MKAGENT_PG_USER=postgres MKAGENT_PG_PASSWORD= \
 * ./mvnw -B test -Dmkagent.test.excluded-groups=live -Dgroups=pg \
 *     -Dtest=PostgresRecoveryServiceTest
 * </pre>
 */
@Tag("pg")
@HermeticSpringBootTest(properties = {
        // 把锁等待压到 400ms：用例要证明"有界"，不是证明"能等很久"
        "mkagent.approval.recovery-lock-timeout=400ms",
        "mkagent.approval.recovery-max-duration=60s"
})
@Timeout(180)
class PostgresRecoveryServiceTest {

    private static final String URL = System.getenv().getOrDefault(
            "MKAGENT_PG_URL", "jdbc:postgresql://127.0.0.1:55432/mk_probe");

    private static final String USER = System.getenv().getOrDefault(
            "MKAGENT_PG_USER", "postgres");

    private static final String PASSWORD = System.getenv().getOrDefault(
            "MKAGENT_PG_PASSWORD", "");

    private static final String PROBE_PREFIX = "mk_probe";

    private static final AtomicLong IDS = new AtomicLong(9_700_000_000_000_000L);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecoverySweeper sweeper;

    @Autowired
    private ApprovalClaimService claimService;

    @Autowired
    private ApprovalExecutionCompletionService completionService;

    @Autowired
    private ApprovalPauseService pauseService;

    @Autowired
    private AgentRunRecorder runRecorder;

    private final List<String> createdRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        requireProbeDatabase();
        cleanAll();
    }

    @AfterEach
    void tearDown() {
        cleanAll();
        createdRuns.clear();
    }

    // ==================== 1. 正常完成先提交，扫描不覆盖 ====================

    @Test
    @DisplayName("正常完成先提交 SUCCEEDED：随后的启动扫描不产生任何改写")
    void normalCompletionWinsThenSweepCannotOverwrite() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        assertTrue(completionService.complete(succeededCtx(runId, approvalId, 1), null, 5L),
                "正常收尾应当提交成功");

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals("SUCCEEDED", stateOf(runId), "扫描改写了已提交的成功终态");
        assertNull(errorCodeOf(runId), "成功记录不应被写上中断原因码");
        assertEquals("FINISHED", resumeStateOf(approvalId), "扫描改写了已收尾的审批");
        assertEquals(0, summary.interrupted(), "已终态记录不应进入中断收尾");
        assertTrue(summary.completed(), "干净库上的扫描应当报告完成：" + summary);
    }

    // ==================== 2. 扫描先收尾，迟到成功不得覆盖/不得报成功 ====================

    @Test
    @DisplayName("扫描先判 FAILED：迟到执行写成功 0 行，complete 返回 false（不发成功事件）")
    void interruptWinsThenLateSuccessCannotOverwrite() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(1, summary.interrupted());
        assertEquals("FAILED", stateOf(runId));
        assertEquals("PROCESS_RESTART", errorCodeOf(runId));
        assertNotNull(finishedAtOf(runId), "中断收尾必须写 finished_at");
        assertEquals("FAILED", resumeStateOf(approvalId), "对应审批必须进入既有失败子状态");

        /*
         * 迟到执行：同一代次、ctx 仍是 SUCCEEDED。
         * complete 必须返回 false——BaseAgent.persistTerminal 正是据此
         * 决定不发 FINAL_ANSWER / DONE（H2 侧由 StaleExecutionTerminalEventTest 断言事件集合）。
         */
        assertFalse(completionService.complete(succeededCtx(runId, approvalId, 1), null, 10L),
                "终态已被中断收尾，迟到执行不得报告成功完成");

        assertEquals("FAILED", stateOf(runId), "终态被迟到的成功写覆盖了");
        assertNull(finalAnswerOf(runId), "中断收尾后不得凭空出现成功答案");

        assertEquals(0, runRecorder.recordFinishStrict(succeededCtx(runId, approvalId, 1), null, 10L),
                "recordFinishStrict 必须 0 行，而不是覆盖 FAILED");
    }

    // ==================== 3. R1 与 markStarted 的两个顺序 ====================

    @Test
    @DisplayName("R1 先赢：退回 PENDING_RESUME，随后 markStarted 失效")
    void reclaimBeatsMarkStarted() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "CLAIMED", 1);

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(1, summary.reclaimed());
        assertEquals("WAITING_APPROVAL", stateOf(runId));
        assertEquals(1, epochOf(runId), "R1 不得回退 execution_epoch");
        assertEquals("PENDING_RESUME", resumeStateOf(approvalId));
        assertNull(claimedEpochOf(approvalId), "回收必须清空 claimed_epoch");
        assertEquals("APPROVED", statusOf(approvalId), "回收不得撤销用户决定");

        assertFalse(claimService.markStarted(approvalId, 1),
                "回收之后旧执行者的启动标记必须失效，一个模型/工具都不能跑");
    }

    @Test
    @DisplayName("markStarted 先赢：R1 0 行，随后由 R3 明确判失败（不是退回等待）")
    void markStartedBeatsReclaim() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "CLAIMED", 1);

        assertTrue(claimService.markStarted(approvalId, 1));

        RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);

        assertEquals(0, summary.reclaimed(), "已开始的执行不能被退回等待");
        assertEquals("FAILED", stateOf(runId), "已开始的孤儿执行按中断收尾");
        assertEquals("FAILED", resumeStateOf(approvalId));
    }

    // ==================== 4. 多行失败共同回滚 ====================

    @Test
    @DisplayName("run 更新成功但 approval 更新失败：整个事务回滚，run 不留半截 FAILED")
    void runUpdatedButApprovalUpdateFailsRollsBackBoth() {
        String runId = insertRun("RUNNING", 1);
        // 组合不一致：run 在 RUNNING，审批却还在 PENDING_RESUME（没有 claimed_epoch）
        String approvalId = insertApproval(runId, "APPROVED", "PENDING_RESUME", 1);

        assertThrows(IllegalStateException.class,
                () -> claimService.failInterrupted(
                        approvalId, runId, 1, RecoverySweeper.PROCESS_RESTART_MESSAGE),
                "审批未命中且不是幂等结果时必须抛异常，让 run 终态一起回滚");

        assertEquals("RUNNING", stateOf(runId), "run 的 FAILED 必须被回滚");
        assertNull(errorCodeOf(runId));
        assertNull(finishedAtOf(runId), "回滚后不得留下 finished_at");
        assertEquals("PENDING_RESUME", resumeStateOf(approvalId));
    }

    // ==================== 5. 锁等待有界 + 失败分类 + 门禁不开放 ====================

    @Test
    @DisplayName("行锁被长时间持有：扫描按锁超时失败、整体回滚，门禁保持关闭")
    void lockWaitTimesOutRollsBackAndKeepsGateClosed() throws Exception {
        String runId = insertRun("RUNNING", 1);

        ApprovalProperties enabled = new ApprovalProperties();
        enabled.setRecoveryEnabled(true);
        // 构造即 CLOSED：与真实启动顺序一致（端口监听之前就已生效）
        RecoveryGate gate = new RecoveryGate(enabled);
        assertThrows(com.example.mkagent.exception.BusinessException.class, gate::requireOpen);

        try (Connection holder = DriverManager.getConnection(URL, USER, PASSWORD)) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "UPDATE agent_run SET updated_at = CURRENT_TIMESTAMP WHERE run_id = ?")) {
                lock.setString(1, runId);
                assertEquals(1, lock.executeUpdate(), "holder 必须真的持有行锁");
            }

            long start = System.currentTimeMillis();
            RecoverySweeper.Summary summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);
            long elapsed = System.currentTimeMillis() - start;

            assertFalse(summary.completed(), "有暂时失败时不得报告完成：" + summary);
            assertTrue(summary.transientFailed() >= 1,
                    "锁超时必须计入 transientFailed，而不是被当成可忽略的跳过：" + summary);
            assertTrue(elapsed >= 300,
                    "应当真的等过锁（配置 400ms），实际 " + elapsed + "ms");
            assertTrue(elapsed < 20_000, "等待必须有界，实际 " + elapsed + "ms");

            assertEquals("RUNNING", stateOf(runId), "锁超时必须整体回滚，不能留下半截 FAILED");
            assertNull(finishedAtOf(runId));

            // 收尾未完成 → 启动入口不得开放
            new RecoveryStartupRunner(sweeper, gate).run(null);
            assertThrows(com.example.mkagent.exception.BusinessException.class,
                    gate::requireOpen,
                    "扫描未完成时门禁必须保持关闭");

            holder.rollback();
        }

        // 锁释放后门禁也不会自己打开：本批没有周期扫描，只能重启重扫
        assertThrows(com.example.mkagent.exception.BusinessException.class, gate::requireOpen);
    }

    // ==================== 6. 反向持锁：等待与重新求值 ====================

    @Test
    @DisplayName("run→approval 方向：第二把锁被持有时等待，释放后继续，不产生死锁")
    void runThenApprovalWaitsAndProceeds() throws Exception {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DriverManager.getConnection(URL, USER, PASSWORD)) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "UPDATE agent_approval SET updated_at = CURRENT_TIMESTAMP "
                            + "WHERE approval_id = ?")) {
                lock.setString(1, approvalId);
                assertEquals(1, lock.executeUpdate(), "holder 必须真的持有审批行锁");
            }

            Future<Boolean> future = pool.submit(() -> claimService.failInterrupted(
                    approvalId, runId, 1, RecoverySweeper.PROCESS_RESTART_MESSAGE));

            Thread.sleep(400);
            assertFalse(future.isDone(),
                    "approval 是第二把锁：此时应当等待，而不是立刻失败或死锁");

            holder.rollback();

            assertTrue(future.get(30, TimeUnit.SECONDS), "锁释放后应当继续并完成收尾");
            assertEquals("FAILED", stateOf(runId));
            assertEquals("FAILED", resumeStateOf(approvalId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("approval→run 方向：claim 等锁后重新求值，0 行即退出，不再拿 run 锁")
    void claimWaitsThenReevaluatesWithoutTakingRunLock() throws Exception {
        String runId = insertRun("WAITING_APPROVAL", 0);
        String approvalId = insertApproval(runId, "APPROVED", "PENDING_RESUME", null);

        ApprovalRecord stale = claimService.find(approvalId);
        assertNotNull(stale, "前置：审批记录必须存在");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DriverManager.getConnection(URL, USER, PASSWORD)) {
            holder.setAutoCommit(false);
            // 持锁并把状态改走：模拟另一个领取者/取消者先赢
            try (PreparedStatement lock = holder.prepareStatement(
                    "UPDATE agent_approval SET resume_state = 'FINISHED' WHERE approval_id = ?")) {
                lock.setString(1, approvalId);
                assertEquals(1, lock.executeUpdate());
            }

            Future<Object> future = pool.submit(() -> {
                try {
                    return claimService.claim(stale);
                } catch (RuntimeException e) {
                    return e;
                }
            });

            Thread.sleep(400);
            assertFalse(future.isDone(), "claim 应当等待 approval 行锁");

            holder.commit();

            Object result = future.get(30, TimeUnit.SECONDS);
            assertTrue(result instanceof IllegalStateException,
                    "锁释放后重新求值必须 0 行并抛异常，实际：" + result);
            assertEquals("WAITING_APPROVAL", stateOf(runId),
                    "第一条 CAS 0 行即退出，绝不能再去改 run");
            assertEquals(0, epochOf(runId), "run 的代次不得被推进");
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 7. 不变量：DB 不拦"不同 step 的两条未结束审批" ====================

    @Test
    @DisplayName("UNIQUE(run_id, step) 不阻止同一 run 在不同 step 上并存两条未结束审批")
    void uniqueRunStepIndexDoesNotPreventSecondUnfinishedApproval() {
        String runId = insertRun("WAITING_APPROVAL", 1);

        insertApprovalAtStep(runId, 1, "PENDING", "PENDING_RESUME", null);
        insertApprovalAtStep(runId, 2, "PENDING", "PENDING_RESUME", null);

        assertEquals(2, countUnfinishedApprovals(runId),
                "这条断言记录的是**事实**：数据库层不保证'一个 run 只有一条未结束审批'");

        // 同一个 step 才会被索引拦住
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> insertApprovalAtStep(runId, 1, "PENDING", "PENDING_RESUME", null));
    }

    // ==================== 7. 不变量：并发暂停只可能插入一条 ====================

    @Test
    @DisplayName("同一 run 同一代次并发暂停（不同 step）：run 状态 CAS 只放行一个，只插入一条审批")
    void concurrentPauseInsertsAtMostOneApproval() throws Exception {
        String runId = insertRun("RUNNING", 1);
        ApprovalCheckpoint checkpoint = checkpoint(runId);
        ApprovalSummary summary = summary();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> first = pool.submit(() -> pauseOrError(
                    start, ctx(runId, 1, 1), checkpoint, summary));
            Future<Object> second = pool.submit(() -> pauseOrError(
                    start, ctx(runId, 2, 1), checkpoint, summary));

            start.countDown();

            Object a = first.get(60, TimeUnit.SECONDS);
            Object b = second.get(60, TimeUnit.SECONDS);

            long succeeded = (a instanceof PauseResult ? 1 : 0)
                    + (b instanceof PauseResult ? 1 : 0);

            assertEquals(1, succeeded,
                    "同一 run 同一代次只能有一个暂停成功，实际成功数=" + succeeded
                            + "，结果=" + a + " / " + b);
            assertEquals(1, countApprovals(runId), "数据库里只应有一条审批");
            assertEquals("WAITING_APPROVAL", stateOf(runId));
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 辅助 ====================

    private Object pauseOrError(
            CountDownLatch start,
            AgentRunContext ctx,
            ApprovalCheckpoint checkpoint,
            ApprovalSummary summary
    ) throws InterruptedException {
        start.await();
        try {
            return pauseService.persist(ctx, checkpoint, summary);
        } catch (RuntimeException e) {
            return e;
        }
    }

    private void requireProbeDatabase() {
        String database = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        assertTrue(database != null && database.startsWith(PROBE_PREFIX),
                "本测试只允许在独立探针库上运行（库名以 " + PROBE_PREFIX + " 开头），当前库=" + database);
    }

    private void cleanAll() {
        jdbcTemplate.update("DELETE FROM agent_approval");
        jdbcTemplate.update("DELETE FROM agent_checkpoint");
        jdbcTemplate.update("DELETE FROM agent_run");
    }

    private AgentRunContext succeededCtx(String runId, int epoch) {
        AgentRunContext ctx = new AgentRunContext(UUID.fromString(runId));
        ctx.setUserId("pg-recovery-user");
        ctx.setExecutionEpoch(epoch);
        ctx.setState(AgentState.SUCCEEDED);
        ctx.setFinalAnswer("已创建歌单");
        return ctx;
    }

    private AgentRunContext succeededCtx(String runId, String approvalId, int epoch) {
        AgentRunContext ctx = succeededCtx(runId, epoch);
        ctx.setResumeApprovalId(approvalId);
        return ctx;
    }

    private AgentRunContext ctx(String runId, int step, int epoch) {
        AgentRunContext ctx = new AgentRunContext(UUID.fromString(runId));
        ctx.setUserId("pg-recovery-user");
        ctx.setAgentType(AgentType.MUSIC_USER);
        for (int i = 0; i < step; i++) {
            ctx.nextStep();
        }
        ctx.setExecutionEpoch(epoch);
        return ctx;
    }

    private ApprovalCheckpoint checkpoint(String runId) {
        String arguments = "{\"name\":\"夜曲\",\"songIds\":[1]}";

        return new ApprovalCheckpoint(
                ApprovalCheckpoint.CURRENT_SCHEMA_VERSION,
                "cfg-pg",
                runId,
                "pg-recovery-user",
                AgentType.MUSIC_USER.name(),
                1,
                0,
                List.of(),
                new ApprovalCheckpoint.CheckpointMessage(
                        "assistant", "",
                        List.of(new ApprovalCheckpoint.PendingToolCall(
                                "call-pg", "function", "create_playlist",
                                arguments, ApprovalHashes.argsHash(arguments))),
                        null),
                List.of(new ApprovalCheckpoint.PendingToolCall(
                        "call-pg", "function", "create_playlist",
                        arguments, ApprovalHashes.argsHash(arguments))),
                new ApprovalCheckpoint.BudgetSnapshot(4, 10, 30),
                new ApprovalCheckpoint.UsageSnapshot("stub", 1, 1, 2, 1, 5),
                new ApprovalCheckpoint.TimingSnapshot(
                        LocalDateTime.now().atZone(ZoneId.systemDefault()).toString(), 0),
                "whitelist-pg"
        );
    }

    private ApprovalSummary summary() {
        return new ApprovalSummary(
                "create_playlist", "创建歌单", "夜曲", 1,
                List.of("城市霓虹"), 1, "创建歌单《夜曲》，共 1 首。");
    }

    private String insertRun(String state, int epoch) {
        String runId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO agent_run (id, run_id, user_id, agent_type, user_prompt, state, "
                        + " current_step, tool_call_count, started_at, created_at, updated_at, "
                        + " execution_epoch, active_elapsed_millis, approval_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 1, CURRENT_TIMESTAMP, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0, 1)",
                IDS.incrementAndGet(), runId, "pg-recovery-user",
                AgentType.MUSIC_USER.name(), "PG 收尾补证", state, epoch
        );

        createdRuns.add(runId);
        return runId;
    }

    private String insertApproval(
            String runId, String status, String resumeState, Integer claimedEpoch) {
        return insertApprovalAtStep(runId, 1, status, resumeState, claimedEpoch);
    }

    private String insertApprovalAtStep(
            String runId, int step, String status, String resumeState, Integer claimedEpoch) {
        String approvalId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO agent_approval (approval_id, run_id, user_id, agent_type, step, "
                        + " primary_tool, tool_count, args_hash, summary_text, status, "
                        + " resume_state, resume_attempts, claimed_epoch, decided_at, expires_at, "
                        + " created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'create_playlist', 1, 'test-hash', "
                        + " 'PG 收尾补证摘要', ?, ?, 0, ?, CURRENT_TIMESTAMP, ?, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                approvalId, runId, "pg-recovery-user", AgentType.MUSIC_USER.name(), step,
                status, resumeState, claimedEpoch, LocalDateTime.now().plusHours(1)
        );

        return approvalId;
    }

    private String stateOf(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM agent_run WHERE run_id = ?", String.class, runId);
    }

    private Integer epochOf(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT execution_epoch FROM agent_run WHERE run_id = ?", Integer.class, runId);
    }

    private String errorCodeOf(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT error_code FROM agent_run WHERE run_id = ?", String.class, runId);
    }

    private Object finishedAtOf(String runId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT finished_at FROM agent_run WHERE run_id = ?", runId);
        return row.get("finished_at");
    }

    private String finalAnswerOf(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT final_answer FROM agent_run WHERE run_id = ?", String.class, runId);
    }

    private String statusOf(String approvalId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM agent_approval WHERE approval_id = ?", String.class, approvalId);
    }

    private String resumeStateOf(String approvalId) {
        return jdbcTemplate.queryForObject(
                "SELECT resume_state FROM agent_approval WHERE approval_id = ?",
                String.class, approvalId);
    }

    private Integer claimedEpochOf(String approvalId) {
        return jdbcTemplate.queryForObject(
                "SELECT claimed_epoch FROM agent_approval WHERE approval_id = ?",
                Integer.class, approvalId);
    }

    private long countApprovals(String runId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_approval WHERE run_id = ?", Long.class, runId);
        return value == null ? 0L : value;
    }

    private long countUnfinishedApprovals(String runId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_approval WHERE run_id = ? "
                        + "AND resume_state IN ('PENDING_RESUME', 'CLAIMED', 'RUNNING')",
                Long.class, runId);
        return value == null ? 0L : value;
    }

    /**
     * MCP 自动装配在 hermetic 测试里被关掉，补一个空工具提供者（与其它上下文测试一致）。
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class EmptyToolCallbackProvider {

        @org.springframework.context.annotation.Bean
        org.springframework.ai.tool.ToolCallbackProvider toolCallbackProvider() {
            return () -> new org.springframework.ai.tool.ToolCallback[0];
        }
    }
}
