package com.example.mkagent.approval;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.ShopAssistantAgent;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.shop.ShopSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.ShopCheckoutWriteTool;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实扫描链路 → 真实写工具（submit_checkout_order）→ 暂停且业务表零写入。
 */
@HermeticSpringBootTest
@Timeout(60)
class ApprovalWriteToolPauseIntegrationTest {

    @Autowired
    private ShopCheckoutWriteTool checkoutWriteTool;

    @Autowired
    private ApprovalToolPolicy approvalToolPolicy;

    @Autowired
    private ApprovalPauseGateway approvalPauseGateway;

    @Autowired
    private ApprovalSummaryBuilder approvalSummaryBuilder;

    @Autowired
    private RunHandoffLocks runHandoffLocks;

    @Autowired
    private AgentTaskRegistry taskRegistry;

    @Autowired
    private AgentRunRecorder runRecorder;

    @Autowired
    private AgentConcurrencyGuard concurrencyGuard;

    @Autowired
    private ToolEventMessageProvider toolEventMessageProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        taskRegistry.snapshot().forEach(t ->
                taskRegistry.remove(t.context().getRunId().toString(), t.epoch()));
    }

    @Test
    @DisplayName("真实写工具命中审批注解：暂停且订单表相对快照无变更")
    void realWriteToolPausesWithoutBusinessChange() {
        long ordersBefore = count("shop_order");
        long orderItemsBefore = count("shop_order_item");

        ScriptedModel model = new ScriptedModel(
                "call-w1", "submit_checkout_order",
                "{\"sku\":\"SKU-KEYBOARD-A75\",\"quantity\":1,\"expectedUnitPriceCents\":36900}");

        ShopAssistantAgent agent = newAgent(model);
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId(ShopSeedInitializer.BUYER_SHOP_ONLY);
        String runId = ctx.getRunId().toString();

        String answer = agent.run("请帮我按最新报价下单无线轻音机械键盘 Air75 一把", ctx);

        assertEquals("任务已暂停，等待人工确认。", answer);

        assertEquals(AgentState.WAITING_APPROVAL, ctx.getState());
        assertEquals(0, ctx.getToolCallCount(), "暂停轮不得执行任何工具");
        assertEquals(1, ctx.getCurrentStep(), "暂停发生在第 1 步");
        assertEquals(1, model.calls.get(), "暂停后不得再调用模型");

        assertEquals(ordersBefore, count("shop_order"), "确认前不得创建订单");
        assertEquals(orderItemsBefore, count("shop_order_item"), "确认前不得写订单明细");

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT state, current_step, tool_call_count, approval_count, "
                        + "finished_at, config_version, active_elapsed_millis "
                        + "FROM agent_run WHERE run_id = ?", runId);

        assertEquals("WAITING_APPROVAL", run.get("state"));
        assertNull(run.get("finished_at"));
        assertEquals(0, ((Number) run.get("tool_call_count")).intValue());
        assertEquals(1, ((Number) run.get("approval_count")).intValue());
        assertNotNull(run.get("config_version"));

        Map<String, Object> approval = jdbcTemplate.queryForMap(
                "SELECT approval_id, primary_tool, tool_count, status, resume_state, "
                        + "summary_text, args_hash FROM agent_approval WHERE run_id = ?",
                runId);

        assertEquals("submit_checkout_order", approval.get("primary_tool"));
        assertEquals(1, ((Number) approval.get("tool_count")).intValue());
        assertEquals("PENDING", approval.get("status"));
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertFalse(approval.get("args_hash").toString().isBlank());

        String summary = approval.get("summary_text").toString();
        assertTrue(summary.contains("Air75"), "摘要应包含商品名，实际：" + summary);
        assertTrue(summary.contains("369"), "摘要应包含价格，实际：" + summary);
        assertFalse(summary.contains("expectedUnitPriceCents"),
                "摘要不得包含参数原文，实际：" + summary);

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM agent_checkpoint WHERE run_id = ?",
                String.class, runId);
        ApprovalCheckpoint checkpoint = CheckpointCodec.decode(payload);

        assertEquals(1, checkpoint.pendingToolCalls().size());
        assertEquals("submit_checkout_order", checkpoint.pendingToolCalls().get(0).name());
        assertEquals("call-w1", checkpoint.pendingToolCalls().get(0).id());
        assertEquals(2, checkpoint.messages().size());
        assertNotNull(checkpoint.pendingAssistant());

        assertEquals(0, taskRegistry.size());
        assertEquals(concurrencyGuard.getMaxConcurrency(), concurrencyGuard.availablePermits());
        assertFalse(runHandoffLocks.exists(runId));
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private ShopAssistantAgent newAgent(ChatModel model) {
        ToolCallback[] callbacks = ToolCallbacks.from(checkoutWriteTool);

        ShopAssistantAgent agent = new ShopAssistantAgent(
                model,
                callbacks,
                Runnable::run,
                toolEventMessageProvider
        );

        agent.setTaskRegistry(taskRegistry);
        agent.setRunRecorder(runRecorder);
        agent.setConcurrencyGuard(concurrencyGuard);
        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(approvalPauseGateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);
        agent.setMaxSteps(4);
        agent.setMaxToolCalls(10);
        agent.setTimeout(Duration.ofSeconds(30));

        return agent;
    }

    static class ScriptedModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final String callId;
        private final String toolName;
        private final String arguments;

        ScriptedModel(String callId, String toolName, String arguments) {
            this.callId = callId;
            this.toolName = toolName;
            this.arguments = arguments;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "",
                            Map.of(),
                            List.of(new AssistantMessage.ToolCall(
                                    callId, "function", toolName, arguments))))),
                    ChatResponseMetadata.builder()
                            .model("stub-model")
                            .usage(new StubUsage(10, 5))
                            .build()
            );
        }
    }

    record StubUsage(int promptTokens, int completionTokens) implements Usage {
        @Override public Integer getPromptTokens() { return promptTokens; }
        @Override public Integer getCompletionTokens() { return completionTokens; }
        @Override public Object getNativeUsage() { return null; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
