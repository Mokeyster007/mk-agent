package com.example.mkagent.model;

import com.example.mkagent.entity.AgentRunEntity;
import com.example.mkagent.model.vo.AgentRunVO;
import com.example.mkagent.service.AgentRunService;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AgentRunVO.errorCode} 的映射与兼容性证据（M3 批次 4B）。
 *
 * <p>为什么需要它：`PROCESS_RESTART` 这类原因码以前只写在库里，
 * 查询接口拿不到，前端只能去解析中文 {@code errorMessage}。
 * 本批把它暴露成稳定字段，但**不能改变已有响应结构**——老客户端忽略未知字段即可，
 * 已有字段一个都不能少、一个都不能改名。所以这里同时锁两件事：
 * 数据库 → VO 的映射，以及序列化后的字段集合。
 */
@HermeticSpringBootTest
class AgentRunVOErrorCodeTest {

    private static final String USER = "vo-errorcode-user";

    private static final AtomicLong IDS = new AtomicLong(9_600_000_000_000_000L);

    /** 批次 4B 之前的响应字段集合：一个都不能丢，也不能改名。 */
    private static final Set<String> LEGACY_KEYS = new LinkedHashSet<>(List.of(
            "runId", "userId", "agentType", "userPrompt", "state", "currentStep",
            "toolCallCount", "toolTrace", "finalAnswer", "errorMessage", "model",
            "promptTokens", "completionTokens", "totalTokens", "startedAt",
            "finishedAt", "totalCostMillis", "createdAt"
    ));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentRunService runService;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> createdRuns = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String runId : createdRuns) {
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdRuns.clear();
    }

    @Test
    @DisplayName("查询映射：error_code 原样出现在 VO，中文说明仍在 errorMessage")
    void mapsErrorCodeFromDatabase() {
        String runId = insertRun("FAILED", "PROCESS_RESTART",
                "执行被进程重启中断，此前已提交的操作可能仍然有效，请核对");

        AgentRunVO vo = runService.getByRunIdForUser(runId, USER);

        assertEquals("PROCESS_RESTART", vo.getErrorCode(), "原因码必须可从查询接口拿到");
        assertEquals("FAILED", vo.getState());
        assertEquals("执行被进程重启中断，此前已提交的操作可能仍然有效，请核对",
                vo.getErrorMessage(), "中文说明保持原样，前端不该解析它");
    }

    @Test
    @DisplayName("老记录兼容：没有 error_code 的行映射为 null，不报错")
    void legacyRowWithoutErrorCodeMapsToNull() {
        String runId = insertRun("SUCCEEDED", null, null);

        AgentRunVO vo = runService.getByRunIdForUser(runId, USER);

        assertNull(vo.getErrorCode(), "历史行没有原因码时应当是 null，而不是空串或占位符");
        assertEquals("SUCCEEDED", vo.getState());
    }

    @Test
    @DisplayName("响应契约：字段集合 = 原有字段 + errorCode，没有删改任何老字段")
    void jsonKeepsLegacyKeysAndAddsErrorCode() throws Exception {
        AgentRunVO vo = AgentRunVO.fromEntity(fullEntity());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(vo));

        Set<String> actual = new LinkedHashSet<>();
        for (Iterator<String> it = json.fieldNames(); it.hasNext(); ) {
            actual.add(it.next());
        }

        Set<String> expected = new LinkedHashSet<>(LEGACY_KEYS);
        expected.add("errorCode");

        assertEquals(expected, actual,
                "新增字段是增量兼容：老字段必须全在，且不能多出未声明的字段");
        assertTrue(json.hasNonNull("errorCode"));
        assertEquals("PROCESS_RESTART", json.get("errorCode").asText());
    }

    private AgentRunEntity fullEntity() {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId("run-vo-contract");
        entity.setUserId(USER);
        entity.setAgentType("MUSIC_USER");
        entity.setUserPrompt("契约测试");
        entity.setState("FAILED");
        entity.setCurrentStep(3);
        entity.setToolCallCount(2);
        entity.setToolTrace("create_playlist");
        entity.setFinalAnswer(null);
        entity.setErrorMessage("执行被进程重启中断");
        entity.setErrorCode("PROCESS_RESTART");
        entity.setModel("test-model");
        entity.setPromptTokens(10L);
        entity.setCompletionTokens(5L);
        entity.setTotalTokens(15L);
        entity.setStartedAt(LocalDateTime.of(2026, 11, 4, 22, 0));
        entity.setFinishedAt(LocalDateTime.of(2026, 11, 4, 22, 1));
        entity.setTotalCostMillis(60_000L);
        entity.setCreatedAt(LocalDateTime.of(2026, 11, 4, 22, 0));
        return entity;
    }

    private String insertRun(String state, String errorCode, String errorMessage) {
        String runId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO agent_run (id, run_id, user_id, agent_type, user_prompt, state, "
                        + " current_step, tool_call_count, started_at, finished_at, created_at, "
                        + " updated_at, execution_epoch, active_elapsed_millis, approval_count, "
                        + " error_code, error_message) "
                        + "VALUES (?, ?, ?, 'MUSIC_USER', 'VO 契约测试', ?, 1, 0, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + " CURRENT_TIMESTAMP, 0, 0, 0, ?, ?)",
                IDS.incrementAndGet(), runId, USER, state, errorCode, errorMessage
        );

        createdRuns.add(runId);
        return runId;
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
