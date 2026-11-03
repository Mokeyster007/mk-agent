package com.example.mkagent.approval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 收尾事务与补偿事务的原子性证据（M3 批次 2 定点核验）。
 *
 * 这里**不用** mock 的 verify 次数冒充原子性：
 * 全部走真实的 Spring 事务代理 + 真实 H2，断言的是"提交之后库里的最终状态"。
 *
 * 覆盖两条写入路径：
 * 1. {@link ApprovalExecutionCompletionService#complete}：
 *    run 终态 + 审批 FINISHED 同事务；
 * 2. {@link ApprovalClaimService#compensate}：
 *    审批退回 + run 退回同事务。
 *
 * 两条路径的共同要求：不允许任何"一半生效"的提交结果。
 */
@HermeticSpringBootTest
@Timeout(60)
class ApprovalCompletionTransactionTest {

    private static final String USER = "tx-test-user";

    private static final String AGENT_TYPE = "MUSIC_USER";

    private static final AtomicLong IDS = new AtomicLong(9_100_000_000_000_000L);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApprovalExecutionCompletionService completionService;

    /**
     * 真实服务外面套一层 spy：只为制造"审批收尾抛异常"，
     * 其余方法仍然委托给真实实现（代理因此照常开事务）。
     */
    @MockitoSpyBean
    private ApprovalClaimService claimService;

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

    // ==================== 1. 完成事务 ====================

    @Test
    @DisplayName("run 终态已写入、审批收尾抛异常：两条写入都不提交")
    void approvalFinishFailureRollsBackRunTerminal() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        doThrow(new IllegalStateException("模拟审批收尾写入故障"))
                .when(claimService).finish(anyString());

        AgentRunContext ctx = succeededCtx(runId, approvalId, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> completionService.complete(ctx, null, 12L));

        assertTrue(error.getMessage().contains("模拟审批收尾写入故障"),
                "异常必须原样抛出，事务才有机会回滚，实际：" + error.getMessage());

        // 关键断言：run 终态没有落库（第一条写入被回滚）
        Map<String, Object> run = runRow(runId);
        assertEquals("RUNNING", run.get("state"), "run 终态必须随事务回滚，实际：" + run);
        assertNull(run.get("finished_at"), "finished_at 不得写入，实际：" + run);

        // 审批也没变
        assertEquals("RUNNING", approvalRow(approvalId).get("resume_state"));
    }

    @Test
    @DisplayName("run 终态已写入、审批收尾 0 行（非 FINISHED）：回滚 run 终态，不提交半截结果")
    void unexpectedZeroRowsOnFinishRollsBackRunTerminal() {
        String runId = insertRun("RUNNING", 1);
        /*
         * 人为制造冲突：审批已被退回 PENDING_RESUME（claimed_epoch 清空），
         * 但 run 仍停在本次代次的 RUNNING。
         * 此时 finish 的 WHERE 条件必然 0 行，且不是"重复收尾"。
         */
        String approvalId = insertApproval(runId, "APPROVED", "PENDING_RESUME", null);

        AgentRunContext ctx = succeededCtx(runId, approvalId, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> completionService.complete(ctx, null, 8L));

        assertTrue(error.getMessage().contains("审批收尾未命中"),
                "必须显式识别为状态冲突，实际：" + error.getMessage());

        Map<String, Object> run = runRow(runId);
        assertEquals("RUNNING", run.get("state"), "run 终态必须回滚，实际：" + run);
        assertNull(run.get("finished_at"), "finished_at 不得写入，实际：" + run);
        assertEquals("PENDING_RESUME", approvalRow(approvalId).get("resume_state"));
    }

    @Test
    @DisplayName("审批已是 FINISHED：重复收尾按幂等接受，run 终态正常提交")
    void duplicateCompletionIsIdempotent() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "FINISHED", 1);

        AgentRunContext ctx = succeededCtx(runId, approvalId, 1);

        assertTrue(completionService.complete(ctx, null, 5L),
                "重复收尾应返回 true，而不是抛异常");

        Map<String, Object> run = runRow(runId);
        assertEquals("SUCCEEDED", run.get("state"), "实际：" + run);
        assertNotNull(run.get("finished_at"));
        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));
    }

    @Test
    @DisplayName("审批收尾已写入后事务失败：FINISHED 与 run 终态一起回滚（finish 加入外层事务）")
    void finishJoinsOuterTransaction() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "RUNNING", 1);

        /*
         * 先真正执行 finish（这一步会把审批写成 FINISHED），再抛异常。
         * 如果 finish 自己开了独立事务，FINISHED 会留下来，
         * 库里就会出现"审批已收尾、run 还没终态"的假完成。
         */
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("收尾已写入，但事务随后失败");
        }).when(claimService).finish(anyString());

        AgentRunContext ctx = succeededCtx(runId, approvalId, 1);

        assertThrows(IllegalStateException.class,
                () -> completionService.complete(ctx, null, 3L));

        Map<String, Object> run = runRow(runId);
        assertEquals("RUNNING", run.get("state"), "run 终态必须回滚，实际：" + run);
        assertNull(run.get("finished_at"), "实际：" + run);
        assertEquals("RUNNING", approvalRow(approvalId).get("resume_state"),
                "finish 的写入必须随外层事务回滚，而不是自己提交");
    }

    // ==================== 2. 补偿事务 ====================

    @Test
    @DisplayName("补偿第二步 0 行（run 已越过本次领取）：第一步审批 CAS 必须回滚")
    void compensateRollsBackApprovalWhenRunMovedOn() {
        String runId = insertRun("WAITING_APPROVAL", 0);
        String approvalId = insertApproval(runId, "APPROVED", "PENDING_RESUME", null);

        int epoch = claimService.claim(claimService.find(approvalId));
        assertEquals("CLAIMED", approvalRow(approvalId).get("resume_state"));

        /*
         * 模拟"执行阶段已越过本次领取"：run 被别的路径判成终态。
         * 此时补偿不能把审批单独退回 PENDING_RESUME——
         * 那会让一条终态任务重新出现"待恢复"的审批。
         */
        jdbcTemplate.update(
                "UPDATE agent_run SET state = 'FAILED', finished_at = CURRENT_TIMESTAMP "
                        + "WHERE run_id = ?", runId);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> claimService.compensate(approvalId, runId, epoch));

        assertTrue(error.getMessage().contains("补偿失败"), "实际：" + error.getMessage());

        // 关键断言：第一步（审批 CAS）被回滚，审批仍归本次领取
        Map<String, Object> approval = approvalRow(approvalId);
        assertEquals("CLAIMED", approval.get("resume_state"),
                "审批不得被单独退回，实际：" + approval);
        assertEquals(epoch, ((Number) approval.get("claimed_epoch")).intValue(),
                "领取代次必须保持不变，实际：" + approval);
        assertEquals("FAILED", runRow(runId).get("state"), "run 状态不应被补偿改写");
    }

    @Test
    @DisplayName("补偿第二步 0 行但 run 已是等价目标状态：按幂等接受，不抛异常")
    void compensateAcceptsEquivalentTargetState() {
        String runId = insertRun("WAITING_APPROVAL", 0);
        String approvalId = insertApproval(runId, "APPROVED", "PENDING_RESUME", null);

        int epoch = claimService.claim(claimService.find(approvalId));

        // 另一个补偿者已经先把 run 退回等待审批（代次不变）
        jdbcTemplate.update(
                "UPDATE agent_run SET state = 'WAITING_APPROVAL' WHERE run_id = ?", runId);

        claimService.compensate(approvalId, runId, epoch);

        Map<String, Object> approval = approvalRow(approvalId);
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertNull(approval.get("claimed_epoch"), "补偿必须清空领取代次");
        assertEquals("WAITING_APPROVAL", runRow(runId).get("state"));
    }

    // ==================== 辅助 ====================

    private AgentRunContext succeededCtx(String runId, String approvalId, int epoch) {
        AgentRunContext ctx = new AgentRunContext(UUID.fromString(runId));
        ctx.setUserId(USER);
        ctx.setExecutionEpoch(epoch);
        ctx.setState(AgentState.SUCCEEDED);
        ctx.setFinalAnswer("已创建歌单");
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
                IDS.incrementAndGet(), runId, USER, AGENT_TYPE, "事务测试", state, epoch
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
                        + " '事务测试摘要', ?, ?, 0, ?, CURRENT_TIMESTAMP, ?, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                approvalId, runId, USER, AGENT_TYPE, status, resumeState,
                claimedEpoch, LocalDateTime.now().plusHours(1)
        );

        return approvalId;
    }

    private Map<String, Object> runRow(String runId) {
        return jdbcTemplate.queryForMap(
                "SELECT state, finished_at, execution_epoch FROM agent_run WHERE run_id = ?",
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
