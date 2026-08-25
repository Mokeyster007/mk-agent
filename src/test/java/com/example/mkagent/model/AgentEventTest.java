package com.example.mkagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentEvent 统一 SSE 事件 DTO 的序列化 / 反序列化单元测试。
 *
 * 不启动 Spring 上下文：
 * 直接用 Jackson 验证 DTO 与 JSON 双向转换正确，
 * 保证前端能稳定反序列化后端推送的每一条事件。
 */
class AgentEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void agentEventSerializesToJsonWithAllProtocolFields() throws Exception {
        AgentEvent event = AgentEvent.of(
                "run-1",
                AgentEventType.TOOL_START,
                "正在查询库存信息",
                2
        );

        String json = objectMapper.writeValueAsString(event);

        assertTrue(json.contains("\"runId\":\"run-1\""),
                "JSON 应包含 runId 字段：" + json);
        assertTrue(json.contains("\"type\":\"tool_start\""),
                "JSON 应包含 type 字段（与 SSE event name 一致）：" + json);
        assertTrue(json.contains("\"message\":\"正在查询库存信息\""),
                "JSON 应包含 message 字段：" + json);
        assertTrue(json.contains("\"step\":2"),
                "JSON 应包含 step 字段：" + json);
        assertTrue(json.contains("\"timestamp\":"),
                "JSON 应包含 timestamp 字段：" + json);
    }

    @Test
    void agentEventDeserializesBackToEquivalentObject() throws Exception {
        AgentEvent original = AgentEvent.of(
                "run-2",
                AgentEventType.FINAL_ANSWER,
                "这是最终回答",
                3
        );

        String json = objectMapper.writeValueAsString(original);
        AgentEvent restored = objectMapper.readValue(json, AgentEvent.class);

        assertEquals(original.getRunId(), restored.getRunId());
        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getMessage(), restored.getMessage());
        assertEquals(original.getStep(), restored.getStep());
        assertEquals(original.getTimestamp(), restored.getTimestamp());
    }

    @Test
    void eventTypeWireNamesMatchSseProtocol() {
        assertEquals("run_id", AgentEventType.RUN_ID.wireName());
        assertEquals("status", AgentEventType.STATUS.wireName());
        assertEquals("step", AgentEventType.STEP.wireName());
        assertEquals("tool_start", AgentEventType.TOOL_START.wireName());
        assertEquals("tool_result", AgentEventType.TOOL_RESULT.wireName());
        assertEquals("final_answer", AgentEventType.FINAL_ANSWER.wireName());
        assertEquals("error", AgentEventType.ERROR.wireName());
        assertEquals("done", AgentEventType.DONE.wireName());
        assertEquals("cancelled", AgentEventType.CANCELLED.wireName());
    }
}
