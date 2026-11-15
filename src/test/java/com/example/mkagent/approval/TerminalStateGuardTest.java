package com.example.mkagent.approval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终态不可被失效执行覆盖（M3 批次 4B，G7 定点核验）。
 *
 * <h2>要回答的矛盾</h2>
 *
 * 旧代码里 {@code doRecordFinish} 只按 {@code run_id + execution_epoch} 更新，
 * 不含任何状态条件；而并发推演却说"扫描写 FAILED 之后，旧执行写成功必然 0 行"。
 * 两者不可能同时成立——**代次相同**时，旧执行会把 FAILED 改写成 SUCCEEDED。
 *
 * <p>这里用真实 H2 + 真实事务确定行为，不靠推演：
 * 先复现"覆盖"，再断言修正后的"不覆盖"，同时确认正常路径没被这条条件破坏。
 */
@HermeticSpringBootTest
@Timeout(60)
class TerminalStateGuardTest {

    private static final String USER = "terminal-guard-user";

    private static final String AGENT_TYPE = "MUSIC_USER";

    private static final AtomicLong IDS = new AtomicLong(9_300_000_000_000_000L);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentRunRecorder runRecorder;

    @Autowired
    private ApprovalClaimService claimService;

    @Autowired
    private ApprovalExecutionCompletionService completionService;

    private final List<String> createdRuns = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String runId : createdRuns) {
            jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdRuns.clear();
    }

    // ==================== A. 收尾先成功，旧执行后写终态 ====================

    @Test
    @DisplayName("A. 启动收尾先判 FAILED：同一代次的旧执行写成功终态影响 0 行，终态不被覆盖")
    void staleSuccessCannotOverwriteInterruptedTerminal() {
        String runId = insertRun("RUNNING", 1);

        assertTrue(claimService.failInterrupted(
                        null, runId, 1, RecoverySweeper.PROCESS_RESTART_MESSAGE),
                "收尾应当命中这条 RUNNING 记录");

        Map<String, Object> afterSweep = runRow(runId);
        assertEquals("FAILED", afterSweep.get("state"));
        assertEquals("PROCESS_RESTART", afterSweep.get("error_code"));
        assertNotNull(afterSweep.get("finished_at"));

        int rows = runRecorder.recordFinishStrict(succeededCtx(runId, 1), null, 10L);

        assertEquals(0, rows,
                "已成立的终态不能被同一代次的另一次执行改写；实际影响行数=" + rows);
        Map<String, Object> afterStale = runRow(runId);
        assertEquals("FAILED", afterStale.get("state"), "终态被覆盖了：" + afterStale);
        assertEquals("PROCESS_RESTART", afterStale.get("error_code"));
        assertNull(afterStale.get("final_answer"), "中断收尾后不得凭空出现成功答案");
    }

    // ==================== B. 正常完成先成功，扫描后收尾 ====================

    @Test
    @DisplayName("B. 正常完成先提交 SUCCEEDED：随后扫描的中断收尾影响 0 行，成功终态保持")
    void interruptCannotOverwriteCommittedSuccess() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        assertTrue(completionService.complete(succeededCtx(runId, approvalId, 1), null, 5L),
                "正常收尾应当提交成功");

        Map<String, Object> afterComplete = runRow(runId);
        assertEquals("SUCCEEDED", afterComplete.get("state"));
        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));

        assertFalse(claimService.failInterrupted(
                        approvalId, runId, 1, RecoverySweeper.PROCESS_RESTART_MESSAGE),
                "对已终态的任务做中断收尾必须 0 行，而不是改写");

        Map<String, Object> afterSweep = runRow(runId);
        assertEquals("SUCCEEDED", afterSweep.get("state"), "成功终态被中断收尾覆盖了：" + afterSweep);
        assertNull(afterSweep.get("error_code"), "成功记录不应被写上中断原因码");
        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));
    }

    // ==================== 合法幂等 ====================

    @Test
    @DisplayName("重复完成（同一终态重写）仍然幂等：两次都提交，结果一致")
    void duplicateSameTerminalRewriteIsIdempotent() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        assertTrue(completionService.complete(succeededCtx(runId, approvalId, 1), null, 5L));
        assertTrue(completionService.complete(succeededCtx(runId, approvalId, 1), null, 6L),
                "提交确认丢失后的重试必须被接受（同一终态重写是幂等的）");

        Map<String, Object> run = runRow(runId);
        assertEquals("SUCCEEDED", run.get("state"));
        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));
    }

    @Test
    @DisplayName("C. 终态未命中本次代次：complete 返回 false，且不改动审批")
    void completeReturnsFalseWhenTerminalWriteMisses() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        /*
         * 模拟"已被新代次接管"：库里代次已经前进，而本次 ctx 仍停在 1。
         */
        jdbcTemplate.update(
                "UPDATE agent_run SET execution_epoch = 2 WHERE run_id = ?", runId);

        AgentRunContext ctx = succeededCtx(runId, approvalId, 1);

        assertFalse(completionService.complete(ctx, null, 5L),
                "代次不符时必须返回 false，调用方据此不发成功事件");

        Map<String, Object> run = runRow(runId);
        assertEquals("RUNNING", run.get("state"), "本次执行没有资格写终态：" + run);
        assertNull(run.get("finished_at"));
        assertEquals("RUNNING", approvalRow(approvalId).get("resume_state"),
                "run 终态没写成功，审批就不能被收尾");
    }

    // ==================== 条件不能破坏正常路径 ====================

    @Test
    @DisplayName("正常路径不受影响：RUNNING 仍可写 FAILED / CANCELLED / TIMED_OUT / MAX_STEPS")
    void normalTerminalPathsStillWork() {
        for (AgentState state : new AgentState[]{
                AgentState.FAILED, AgentState.CANCELLED,
                AgentState.TIMED_OUT, AgentState.MAX_STEPS_REACHED}) {

            String runId = insertRun("RUNNING", 0);
            AgentRunContext ctx = new AgentRunContext(UUID.fromString(runId));
            ctx.setUserId(USER);
            ctx.setExecutionEpoch(0);
            ctx.setState(state);

            assertEquals(1, runRecorder.recordFinishStrict(ctx, "原因", 3L),
                    "从 RUNNING 写终态 " + state + " 必须命中");
            assertEquals(state.name(), runRow(runId).get("state"));
        }
    }

    @Test
    @DisplayName("暂停中的执行仍然拒绝写终态（既有行为不变）")
    void waitingApprovalCtxStillRejectsTerminalWrite() {
        String runId = insertRun("WAITING_APPROVAL", 1);

        AgentRunContext ctx = new AgentRunContext(UUID.fromString(runId));
        ctx.setUserId(USER);
        ctx.setExecutionEpoch(1);
        ctx.setState(AgentState.WAITING_APPROVAL);

        assertEquals(0, runRecorder.recordFinishStrict(ctx, null, 3L),
                "ctx 处于 WAITING_APPROVAL 时终态写入必须被跳过（既有约定）");
        assertEquals("WAITING_APPROVAL", runRow(runId).get("state"));
    }

    // ==================== 辅助 ====================

    private AgentRunContext succeededCtx(String runId, int epoch) {
        AgentRunContext ctx = new AgentRunContext(UUID.fromString(runId));
        ctx.setUserId(USER);
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

    private String insertRun(String state, int epoch) {
        String runId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO agent_run (id, run_id, user_id, agent_type, user_prompt, state, "
                        + " current_step, tool_call_count, started_at, created_at, updated_at, "
                        + " execution_epoch, active_elapsed_millis, approval_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 1, CURRENT_TIMESTAMP, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0, 1)",
                IDS.incrementAndGet(), runId, USER, AGENT_TYPE, "终态守卫测试", state, epoch
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
                        + " '终态守卫摘要', ?, ?, 0, ?, CURRENT_TIMESTAMP, ?, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                approvalId, runId, USER, AGENT_TYPE, status, resumeState,
                claimedEpoch, LocalDateTime.now().plusHours(1)
        );

        return approvalId;
    }

    private Map<String, Object> runRow(String runId) {
        return jdbcTemplate.queryForMap(
                "SELECT state, finished_at, execution_epoch, error_code, final_answer "
                        + "FROM agent_run WHERE run_id = ?",
                runId);
    }

    private Map<String, Object> approvalRow(String approvalId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, resume_state, claimed_epoch FROM agent_approval "
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
