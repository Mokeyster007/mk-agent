package com.example.mkagent.approval;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.BaseAgent;
import com.example.mkagent.model.AgentEvent;
import com.example.mkagent.model.AgentEventType;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 终态未提交时**不得**发送成功事件（M3 批次 4B，G7 的 C/D 两个验证点）。
 *
 * <p>用真实 Agent 循环 + 真实事务服务复现：
 * 本次执行走到成功分支，但库里那条 run 已经被"新代次接管"（这里由 step 自己推进代次模拟），
 * 于是终态写入命中 0 行。
 *
 * <p>要断言的三件事：
 * <ol>
 *   <li>不发 {@code final_answer}，也不发 {@code done}（它们等于"执行已成功提交"）；</li>
 *   <li>只发一条脱敏 error；</li>
 *   <li>finally 不会迟到补写一次，也不会把成功事件补出来。</li>
 * </ol>
 */
@HermeticSpringBootTest
@Timeout(60)
class StaleExecutionTerminalEventTest {

    private static final String USER = "stale-exec-user";

    private static final AtomicLong IDS = new AtomicLong(9_400_000_000_000_000L);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApprovalClaimService claimService;

    @Autowired
    private ApprovalExecutionCompletionService completionService;

    @Autowired
    private AgentTaskRegistry taskRegistry;

    /**
     * spy 真实记录器：只为了数"终态写入被调用了几次"，
     * 不用它替换行为，事务与 SQL 仍然是真的。
     */
    @MockitoSpyBean
    private AgentRunRecorder runRecorder;

    private final List<String> createdRuns = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String runId : createdRuns) {
            taskRegistry.remove(runId, 0);
            taskRegistry.remove(runId, 1);
            taskRegistry.remove(runId, 2);
            jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdRuns.clear();
    }

    @Test
    @DisplayName("C/D. 终态 0 行：不发 final_answer / done，只发脱敏 error，finally 不补写")
    void staleExecutionSendsNoSuccessEvent() {
        String runId = insertRun("RUNNING", 1);
        String approvalId = insertApproval(runId, "APPROVED", "CLAIMED", 1);

        AgentRunContext ctx = AgentRunContext.restore(
                UUID.fromString(runId), Instant.now(), Duration.ZERO, null, 1);
        ctx.setUserId(USER);
        ctx.setAgentType(AgentType.MUSIC_USER);
        ctx.setResumeApprovalId(approvalId);
        ctx.transitionTo(AgentState.RUNNING);

        RecordingEmitter emitter = new RecordingEmitter();

        SabotagingAgent agent = new SabotagingAgent(Runnable::run, jdbcTemplate, runId);
        agent.setName("stale-exec-test");
        agent.setMaxSteps(3);
        agent.setTaskRegistry(taskRegistry);
        agent.setRunRecorder(runRecorder);
        agent.setApprovalClaimService(claimService);
        agent.setApprovalCompletionService(completionService);

        agent.resume(ctx, emitter);

        assertTrue(emitter.has(AgentEventType.ERROR), "必须发一条脱敏 error，实际事件：" + emitter.types());
        assertFalse(emitter.has(AgentEventType.FINAL_ANSWER),
                "终态没提交成功，绝不能发 final_answer，实际事件：" + emitter.types());
        assertFalse(emitter.has(AgentEventType.DONE),
                "终态没提交成功，绝不能发 done，实际事件：" + emitter.types());

        /*
         * finally 再兜一次时 persisted 已经是 true，因此记录器只会被调用一次；
         * 多出来的那次就是"迟到补写"。
         */
        verify(runRecorder, times(1))
                .recordFinishStrict(any(AgentRunContext.class), any(), anyLong());

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT state, finished_at, execution_epoch FROM agent_run WHERE run_id = ?",
                runId);
        assertEquals("RUNNING", run.get("state"),
                "本次执行没有资格写终态，库里应保持接管后的状态：" + run);
        assertEquals(2, ((Number) run.get("execution_epoch")).intValue(),
                "代次已被新执行推进，旧执行不得回退它：" + run);

        assertEquals("RUNNING", jdbcTemplate.queryForObject(
                        "SELECT resume_state FROM agent_approval WHERE approval_id = ?",
                        String.class, approvalId),
                "run 终态没写成功时，审批不得被收尾");
    }

    // ==================== 辅助 ====================

    /**
     * 一步成功、但在成功前把库里的代次推进的 Agent：模拟"新代次已经接管"。
     */
    static class SabotagingAgent extends BaseAgent {

        private final JdbcTemplate jdbcTemplate;

        private final String runId;

        SabotagingAgent(Executor executor, JdbcTemplate jdbcTemplate, String runId) {
            super(executor);
            this.jdbcTemplate = jdbcTemplate;
            this.runId = runId;
        }

        @Override
        protected String step(AgentRunContext ctx) {
            // 模拟另一次领取把代次推到 2：本次执行从此不再拥有这一行
            jdbcTemplate.update(
                    "UPDATE agent_run SET execution_epoch = 2 WHERE run_id = ?", runId);
            ctx.setFinalAnswer("本次执行以为自己成功了");
            ctx.transitionTo(AgentState.SUCCEEDED);
            return "ok";
        }
    }

    /**
     * 记录 SSE 事件的替身：complete() 不触发生命周期回调，避免关闭连接反过来改状态。
     */
    static class RecordingEmitter extends SseEmitter {

        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) {
            builder.build().forEach(data -> {
                if (data.getData() instanceof AgentEvent event) {
                    events.add(event);
                }
            });
        }

        @Override
        public synchronized void send(Object object) {
            if (object instanceof AgentEvent event) {
                events.add(event);
            }
        }

        @Override
        public synchronized void complete() {
            // 不触发真实连接生命周期回调
        }

        boolean has(AgentEventType type) {
            return events.stream().anyMatch(event -> type.wireName().equals(event.getType()));
        }

        List<String> types() {
            return events.stream().map(AgentEvent::getType).toList();
        }
    }

    private String insertRun(String state, int epoch) {
        String runId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO agent_run (id, run_id, user_id, agent_type, user_prompt, state, "
                        + " current_step, tool_call_count, started_at, created_at, updated_at, "
                        + " execution_epoch, active_elapsed_millis, approval_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 0, CURRENT_TIMESTAMP, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0, 1)",
                IDS.incrementAndGet(), runId, USER, "MUSIC_USER", "失效执行测试", state, epoch
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
                        + " '失效执行摘要', ?, ?, 0, ?, CURRENT_TIMESTAMP, ?, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                approvalId, runId, USER, "MUSIC_USER", status, resumeState,
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
