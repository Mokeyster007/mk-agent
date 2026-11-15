package com.example.mkagent.approval;

import com.example.mkagent.agent.AgentTaskCanceller;
import com.example.mkagent.agent.MkManus;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.service.AgentRunService;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动收尾门禁必须覆盖**真实执行入口**（M3 批次 4B）。
 *
 * <p>只在 Controller 上加判断是不够的：同步入口、内部 runner、服务层调用都能绕过去。
 * 这里逐个打真实的 Bean 方法，断言收尾期间一律 503，且**一行数据都不写**。
 *
 * <p>收尾完成后要能恢复：用"不存在的审批/任务"验证门禁已放行——
 * 此时得到的是 404 而不是 503，说明请求确实穿过了门禁。
 */
@HermeticSpringBootTest
@Timeout(60)
class RecoveryGateEntryPointTest {

    private static final String USER = "gate-entry-user";

    private static final String AGENT_TYPE = "MUSIC_USER";

    private static final AtomicLong IDS = new AtomicLong(9_600_000_000_000_000L);

    @Autowired
    private RecoveryGate gate;

    @Autowired
    private MkManus mkManus;

    @Autowired
    private ApprovalDecisionService decisionService;

    @Autowired
    private ApprovalResumeService resumeService;

    @Autowired
    private AgentTaskCanceller canceller;

    @Autowired
    private AgentRunService runService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdRuns = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // 门禁是单例：一定要还回去，否则同上下文的其它用例会被连坐
        gate.open("TEST_TEARDOWN");

        for (String runId : createdRuns) {
            jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdRuns.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("收尾期间：同步/异步入口都不能创建任务，且不落任何记录")
    void creationEntriesAreBlocked() {
        UserContextHolder.set(USER);
        long before = countRuns();

        gate.close("STARTUP_SWEEP_PENDING");

        BusinessException streamError = assertThrows(BusinessException.class,
                () -> mkManus.runStream("创建一个歌单"));
        assertEquals(503, streamError.getHttpStatus());
        assertEquals("RECOVERY_IN_PROGRESS", streamError.getCode());

        BusinessException syncError = assertThrows(BusinessException.class,
                () -> mkManus.run("创建一个歌单"));
        assertEquals(503, syncError.getHttpStatus());

        assertEquals(before, countRuns(), "被拒绝的请求不得创建 agent_run 记录");
    }

    @Test
    @DisplayName("收尾期间：approve/reject 在保存决定之前就被拒绝")
    void decisionIsRejectedBeforeAnyWrite() {
        String runId = insertRun("WAITING_APPROVAL", 0);
        String approvalId = insertApproval(runId, "PENDING", "PENDING_RESUME", null);

        gate.close("STARTUP_SWEEP_PENDING");

        BusinessException error = assertThrows(BusinessException.class,
                () -> decisionService.decide(approvalId, USER, true));

        assertEquals(503, error.getHttpStatus());
        assertEquals("RECOVERY_IN_PROGRESS", error.getCode());

        var row = jdbcTemplate.queryForMap(
                "SELECT status, resume_state FROM agent_approval WHERE approval_id = ?", approvalId);
        assertEquals("PENDING", row.get("status"), "门禁必须先于决定写入");
        assertEquals("PENDING_RESUME", row.get("resume_state"));
    }

    @Test
    @DisplayName("收尾期间：/resume 不能领取执行权")
    void resumeIsBlocked() {
        String runId = insertRun("WAITING_APPROVAL", 0);
        String approvalId = insertApproval(runId, "APPROVED", "PENDING_RESUME", null);

        gate.close("STARTUP_SWEEP_PENDING");

        BusinessException error = assertThrows(BusinessException.class,
                () -> resumeService.resume(approvalId, USER));

        assertEquals(503, error.getHttpStatus());
        assertEquals("PENDING_RESUME", jdbcTemplate.queryForObject(
                "SELECT resume_state FROM agent_approval WHERE approval_id = ?",
                String.class, approvalId), "被拒绝的恢复不得留下 CLAIMED");
    }

    @Test
    @DisplayName("收尾期间：取消入口同样被拒绝（两个 Controller 都走这里）")
    void cancelIsBlocked() {
        String runId = insertRun("WAITING_APPROVAL", 0);
        String approvalId = insertApproval(runId, "PENDING", "PENDING_RESUME", null);

        AgentRunContext ctx = new AgentRunContext(UUID.fromString(runId));
        ctx.setUserId(USER);
        ctx.setState(AgentState.RUNNING);
        RunningAgentTask task = new RunningAgentTask(ctx, new CompletableFuture<>(), 0);

        gate.close("STARTUP_SWEEP_PENDING");

        BusinessException liveCancel = assertThrows(BusinessException.class,
                () -> canceller.cancel(task));
        assertEquals(503, liveCancel.getHttpStatus());

        BusinessException pausedCancel = assertThrows(BusinessException.class,
                () -> runService.cancelRun(runId, USER));
        assertEquals(503, pausedCancel.getHttpStatus());

        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM agent_approval WHERE approval_id = ?",
                String.class, approvalId));
    }

    @Test
    @DisplayName("收尾完成后：请求恢复正常（得到 404 而不是 503）")
    void entriesWorkAfterSweepCompleted() {
        gate.close("STARTUP_SWEEP_PENDING");
        gate.open("STARTUP_SWEEP_COMPLETED");

        BusinessException decisionError = assertThrows(BusinessException.class,
                () -> resumeService.resume("not-exist-" + UUID.randomUUID(), USER));
        assertEquals(404, decisionError.getHttpStatus(),
                "门禁已开，请求应当走到归属校验而不是被 503 拦住");

        BusinessException cancelError = assertThrows(BusinessException.class,
                () -> runService.cancelRun("not-exist-" + UUID.randomUUID(), USER));
        assertEquals(404, cancelError.getHttpStatus());
    }

    @Test
    @DisplayName("recovery 关闭时：门禁不改变现有行为")
    void gateIsOpenWhenRecoveryDisabled() {
        assertTrue(gate.isOpen(), "默认配置下门禁必须是开的");
    }

    // ==================== 辅助 ====================

    private long countRuns() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_run", Long.class);
        return count == null ? 0L : count;
    }

    private String insertRun(String state, int epoch) {
        String runId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO agent_run (id, run_id, user_id, agent_type, user_prompt, state, "
                        + " current_step, tool_call_count, started_at, created_at, updated_at, "
                        + " execution_epoch, active_elapsed_millis, approval_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 0, CURRENT_TIMESTAMP, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0, 1)",
                IDS.incrementAndGet(), runId, USER, AGENT_TYPE, "门禁测试", state, epoch
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
                        + " '门禁摘要', ?, ?, 0, ?, CURRENT_TIMESTAMP, ?, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                approvalId, runId, USER, AGENT_TYPE, status, resumeState,
                claimedEpoch, LocalDateTime.now().plusHours(1)
        );

        return approvalId;
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
