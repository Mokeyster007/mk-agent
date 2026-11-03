package com.example.mkagent.service;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code recordProgress} 的终态约定（批次 2 调整后的回归）。
 *
 * 调整原因：进度写入也会写 state。若某一轮 step 自己把状态置成终态，
 * 进度写入就会先落一个"终态但 finished_at 为 NULL"的行，
 * 客户端轮询恰好落在这一瞬间会看到自相矛盾的数据。
 * 现在终态与 finished_at 由 {@code recordFinish} 一次写入。
 *
 * 这组用例覆盖**非审批路径**的六种收尾，防止调整影响普通任务：
 * 成功 / 失败 / 取消 / 超时 / 达到预算 / 旧代次。
 */
@HermeticSpringBootTest
class AgentRunRecorderProgressTest {

    private static final String USER = "recorder-progress-test";

    @Autowired
    private AgentRunRecorder recorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> runIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String runId : runIds) {
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        runIds.clear();
    }

    @Test
    @DisplayName("非终态：进度照常写入 step / toolCallCount / state")
    void runningStateStillWritesProgress() {
        AgentRunContext ctx = start();

        ctx.nextStep();
        ctx.addToolCallCount(1);
        recorder.recordProgress(ctx);

        Map<String, Object> row = row(ctx);
        assertEquals("RUNNING", row.get("state"));
        assertEquals(1, number(row, "current_step"));
        assertEquals(1, number(row, "tool_call_count"));
        assertNull(row.get("finished_at"), "过程态不得写 finished_at");
    }

    @Test
    @DisplayName("五种终态：进度写入一律跳过，终态与 finished_at 由 recordFinish 独占")
    void terminalStatesAreNotWrittenByProgress() {
        for (AgentState terminal : List.of(
                AgentState.SUCCEEDED,
                AgentState.FAILED,
                AgentState.CANCELLED,
                AgentState.TIMED_OUT,
                AgentState.MAX_STEPS_REACHED)) {

            AgentRunContext ctx = start();
            ctx.transitionTo(terminal);
            ctx.nextStep();
            ctx.addToolCallCount(3);

            recorder.recordProgress(ctx);

            Map<String, Object> row = row(ctx);
            assertEquals("RUNNING", row.get("state"),
                    terminal + " 不得由进度写入落库");
            assertEquals(0, number(row, "current_step"), terminal + " 不得写 step");
            assertEquals(0, number(row, "tool_call_count"), terminal + " 不得写工具次数");
            assertNull(row.get("finished_at"),
                    terminal + " 不得出现\"终态但 finished_at 为空\"的中间行");
        }
    }

    @Test
    @DisplayName("暂停态：进度写入跳过（暂停由 recordPause 与检查点同事务写）")
    void waitingApprovalIsNotWrittenByProgress() {
        AgentRunContext ctx = start();

        ctx.transitionTo(AgentState.WAITING_APPROVAL);
        recorder.recordProgress(ctx);

        Map<String, Object> row = row(ctx);
        assertEquals("RUNNING", row.get("state"));
        assertNull(row.get("finished_at"));
    }

    @Test
    @DisplayName("旧代次：进度写入影响 0 行，不覆盖新代次")
    void staleEpochWritesNothing() {
        AgentRunContext ctx = start();

        // 模拟恢复后新代次已经接管：旧 ctx 的代次落后
        ctx.setExecutionEpoch(ctx.getExecutionEpoch() + 1);
        ctx.nextStep();
        ctx.addToolCallCount(2);
        recorder.recordProgress(ctx);

        Map<String, Object> row = row(ctx);
        assertEquals(0, number(row, "current_step"), "旧代次不得写进度");
        assertEquals(0, number(row, "tool_call_count"));
    }

    @Test
    @DisplayName("recordFinish：成功与失败都一次性写入终态、finished_at 与脱敏信息")
    void recordFinishWritesTerminalOnce() {
        AgentRunContext success = start();
        success.transitionTo(AgentState.SUCCEEDED);
        success.setFinalAnswer("已经建好了");
        recorder.recordFinish(success, null, 123L);

        Map<String, Object> ok = row(success);
        assertEquals("SUCCEEDED", ok.get("state"));
        assertNotNull(ok.get("finished_at"));
        assertEquals("已经建好了", ok.get("final_answer"));

        AgentRunContext failed = start();
        failed.transitionTo(AgentState.FAILED);
        recorder.recordFinish(failed, "模型调用失败（已脱敏）", 45L);

        Map<String, Object> bad = row(failed);
        assertEquals("FAILED", bad.get("state"));
        assertNotNull(bad.get("finished_at"));
        assertEquals("模型调用失败（已脱敏）", bad.get("error_message"));
    }

    @Test
    @DisplayName("recordFinishStrict：返回受影响行数，暂停态不写")
    void recordFinishStrictReportsRows() {
        AgentRunContext success = start();
        success.transitionTo(AgentState.SUCCEEDED);

        assertEquals(1, recorder.recordFinishStrict(success, null, 10L),
                "命中当前代次时应返回 1 行");

        Map<String, Object> ok = row(success);
        assertEquals("SUCCEEDED", ok.get("state"));
        assertNotNull(ok.get("finished_at"));

        /*
         * 暂停态返回 0：调用方（审批收尾）据此跳过 approval FINISHED，
         * 避免出现"审批已收尾、run 还没终态"的假完成。
         */
        AgentRunContext paused = start();
        paused.transitionTo(AgentState.WAITING_APPROVAL);

        assertEquals(0, recorder.recordFinishStrict(paused, null, 10L));
        assertEquals("RUNNING", row(paused).get("state"));
        assertNull(row(paused).get("finished_at"));
    }

    // ==================== 辅助 ====================

    private AgentRunContext start() {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId(USER);
        ctx.setAgentType(AgentType.MUSIC_USER);
        ctx.transitionTo(AgentState.RUNNING);

        recorder.recordStart(ctx, "进度写入回归");
        runIds.add(ctx.getRunId().toString());
        return ctx;
    }

    private Map<String, Object> row(AgentRunContext ctx) {
        return jdbcTemplate.queryForMap(
                "SELECT state, current_step, tool_call_count, finished_at, "
                        + "final_answer, error_message, execution_epoch "
                        + "FROM agent_run WHERE run_id = ?",
                ctx.getRunId().toString());
    }

    private int number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    /**
     * MCP 自动配置在封闭环境里被关闭，chatApp 仍需要一个
     * ToolCallbackProvider；这里给一个空实现，避免上下文加载失败。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class EmptyToolCallbackProvider {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
