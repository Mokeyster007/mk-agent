package com.example.mkagent.shop;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.AfterSaleAgent;
import com.example.mkagent.approval.ApprovalClaimService;
import com.example.mkagent.approval.ApprovalDecisionService;
import com.example.mkagent.approval.ApprovalExecutionCompletionService;
import com.example.mkagent.approval.ApprovalPauseGateway;
import com.example.mkagent.approval.ApprovalProperties;
import com.example.mkagent.approval.ApprovalResumeService;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.approval.RunHandoffLocks;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后 Agent 的确认链路：暂停 → 决定 → 恢复执行。
 *
 * <h2>为什么必须用真实审批链路而不是"直接调工具"</h2>
 *
 * 需求里最关键的一条是"确认前不建单、不占额度；拒绝零写入"。
 * 这条约束只在**真实**的 {@link ApprovalToolPolicy}（容器扫描注解）
 * + 真实 {@link ApprovalPauseGateway}（落检查点）+ 真实
 * {@code ApprovalResumeService}（恢复执行）之下才有意义。
 * 用替身或直接调工具都测不到它。
 *
 * <h2>写工具为什么用包装后的回调</h2>
 *
 * 生产装配里 {@code submit_return} 是 {@code IdempotentToolCallback}
 * （事务边界 + 幂等身份都在它里面）。这里刻意注入**同一个 bean**
 * （{@code afterSaleWriteToolCallbacks}），因此本测试覆盖的是真实调用链：
 * 确认 → 恢复 → 计划绑定 → 幂等执行器开事务 → 售后写服务建单。
 *
 * <h2>模型是桩，但业务不是</h2>
 *
 * 桩模型只负责"这一轮要调用 submit_return、参数是什么"，
 * 判定、事务、额度、事件全部走真实代码。桩不等于联调，这一点在交付说明里单独标注。
 */
@HermeticSpringBootTest
@Timeout(120)
class AfterSaleApprovalFlowTest {

    private static final String BUYER = "music-user-1";

    private static final String OTHER = "music-user-2";

    private static final String ORDER_NO = "SO20261122100000-A001";

    private static final String SKU = "SKU-KEYBOARD-K87";

    private static final String TOOL = "submit_return";

    private static final String REASON = "键盘按键失灵";

    @Autowired
    @Qualifier("afterSaleWriteToolCallbacks")
    private ToolCallback[] writeCallbacks;

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

    @Autowired
    private ApprovalClaimService claimService;

    @Autowired
    private ApprovalExecutionCompletionService approvalCompletionService;

    @Autowired
    private ApprovalDecisionService decisionService;

    @Autowired
    private ApprovalProperties approvalProperties;

    @Autowired
    private AfterSaleService afterSaleService;

    private final List<String> createdRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ShopTestFixture.reset(jdbcTemplate);
        ShopTestFixture.createSignedOrder(
                jdbcTemplate, ORDER_NO, BUYER, SKU, 2001L, "机械键盘 K87", 29900L, 2,
                LocalDateTime.now());

        taskRegistry.snapshot().forEach(t ->
                taskRegistry.remove(t.context().getRunId().toString(), t.epoch()));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();

        for (String runId : createdRuns) {
            jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdRuns.clear();

        // 本类真的写业务数据，必须自己收拾干净（共享同一个内存库）
        ShopTestFixture.reset(jdbcTemplate);
    }

    // ==================== 1. 暂停 ====================

    @Test
    @DisplayName("暂停：不建单、不占额度，确认卡固定订单号/商品/数量/原因")
    void pauseCreatesNothingAndCardFixesTheFacts() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-as1", TOOL, args(1, REASON))
                .answer("已提交退货申请");

        Paused paused = pause(model);

        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate), "确认前不得建单");
        assertEquals(0, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_NO), "确认前不得占额度");
        assertEquals(0, paused.toolCalls(), "暂停轮不得执行任何工具");

        assertTrue(approvalToolPolicy.requiresApproval(TOOL),
                "submit_return 必须在审批名单里，否则确认卡形同虚设");

        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals(TOOL, approval.get("primary_tool"));
        assertEquals("PENDING", approval.get("status"));

        String summary = approval.get("summary_text").toString();
        assertTrue(summary.contains(ORDER_NO), "确认卡必须固定订单号：" + summary);
        assertTrue(summary.contains("机械键盘 K87"), "确认卡必须固定商品：" + summary);
        assertTrue(summary.contains("1 件"), "确认卡必须固定数量：" + summary);
        assertTrue(summary.contains(REASON), "确认卡必须固定原因：" + summary);
        assertFalse(summary.contains("orderNo"), "不得回显参数原文：" + summary);
    }

    // ==================== 2. 批准 ====================

    @Test
    @DisplayName("批准后恢复：恰好建一单、占一份额度、任务成功、审批 FINISHED")
    void approveExecutesExactlyOnce() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-as2", TOOL, args(1, REASON))
                .answer("已提交退货申请，退货单号 RT…");

        Paused paused = pause(model);

        assertEquals(200, decide(paused, true).httpStatus(), "批准应当成功");
        resume(paused);

        assertEquals(1, ShopTestFixture.totalReturns(jdbcTemplate), "批准后必须恰好建一单");
        assertEquals(1, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_NO));

        String returnNo = jdbcTemplate.queryForObject("""
                SELECT r.return_no
                FROM shop_aftersale_return r
                JOIN shop_order o ON o.id = r.order_id
                WHERE o.order_no = ?
                """, String.class, ORDER_NO);
        assertEquals(1, ShopTestFixture.eventCount(jdbcTemplate, returnNo));
        assertEquals(1, ShopTestFixture.returnQty(jdbcTemplate, returnNo));

        Map<String, Object> run = runRow(paused.runId());
        assertEquals("SUCCEEDED", run.get("state"), "实际：" + run);
        assertEquals(1, ((Number) run.get("tool_call_count")).intValue(), "实际：" + run);

        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals("APPROVED", approval.get("status"));
        assertEquals("FINISHED", approval.get("resume_state"));

        // A1 不动库存、不写退货入库流水
        assertEquals(0, ShopTestFixture.totalMovements(jdbcTemplate), "不得写任何库存流水");
    }

    // ==================== 3. 拒绝 ====================

    @Test
    @DisplayName("拒绝后恢复：零写入（不建单、不占额度、不执行工具）")
    void rejectWritesNothing() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-as3", TOOL, args(1, REASON))
                .answer("好的，没有提交退货申请");

        Paused paused = pause(model);

        assertEquals(200, decide(paused, false).httpStatus(), "拒绝应当成功");
        resume(paused);

        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate), "拒绝不得建单");
        assertEquals(0, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_NO), "拒绝不得占额度");

        Map<String, Object> run = runRow(paused.runId());
        assertEquals("SUCCEEDED", run.get("state"), "拒绝是正常结果，不是失败：" + run);
        assertEquals(0, ((Number) run.get("tool_call_count")).intValue(), "实际：" + run);

        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals("REJECTED", approval.get("status"));
        assertEquals("FINISHED", approval.get("resume_state"));
    }

    // ==================== 4. 他人不能操作 ====================

    @Test
    @DisplayName("他人批准/拒绝：404，审批仍 PENDING，业务零变更")
    void otherUserCannotDecide() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-as4", TOOL, args(1, REASON))
                .answer("已提交");

        Paused paused = pause(model);

        assertEquals(404, decide(paused, true, OTHER).httpStatus(), "他人批准必须 404");
        assertEquals(404, decide(paused, false, OTHER).httpStatus(), "他人拒绝必须 404");

        assertEquals("PENDING", approvalRow(paused.approvalId()).get("status"));
        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate));
        assertEquals(0, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_NO));
    }

    // ==================== 5. 卡打开之后额度被用掉 ====================

    @Test
    @DisplayName("确认卡打开后额度被另一请求用掉：执行时重新校验，不建第二单")
    void staleCardIsCaughtByRevalidation() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-as5", TOOL, args(1, REASON))
                .answer("抱歉，这件商品的可退额度已经被用掉了");

        Paused paused = pause(model);

        /*
         * 用户在确认卡上犹豫的时候，另一笔申请（普通表单）把两件都退掉了。
         * 此时确认卡上的"剩余 2 件"已经过期——预览从来不是资格凭证。
         */
        afterSaleService.submitForm(
                BUYER, ORDER_NO, 2, "另一笔申请先提交", "req-competitor-0001");
        assertEquals(2, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_NO));

        assertEquals(200, decide(paused, true).httpStatus());
        resume(paused);

        assertEquals(1, ShopTestFixture.totalReturns(jdbcTemplate), "不得建出第二单");
        assertEquals(2, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_NO), "额度不得被超占");

        Map<String, Object> run = runRow(paused.runId());
        assertEquals("SUCCEEDED", run.get("state"),
                "业务拒绝不是任务失败，模型要能读到原因：" + run);
        assertEquals(1, ((Number) run.get("tool_call_count")).intValue());
    }

    // ==================== 6. 重复确认 ====================

    @Test
    @DisplayName("批准后再次批准/恢复：冲突且仍然只有一单")
    void duplicateApprovalCreatesOnlyOneReturn() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-as6", TOOL, args(1, REASON))
                .answer("已提交");

        Paused paused = pause(model);

        assertEquals(200, decide(paused, true).httpStatus());
        resume(paused);
        assertEquals(1, ShopTestFixture.totalReturns(jdbcTemplate));

        ApprovalDecisionService.DecisionResult second = decide(paused, true);
        assertNotEquals(200, second.httpStatus(), "重复批准必须冲突");
        assertEquals("ALREADY_APPROVED", second.code(),
                "重复批准必须被明确拒绝，而不是静默成功");

        assertEquals(1, ShopTestFixture.totalReturns(jdbcTemplate), "重复确认不得建第二单");
        assertEquals(1, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_NO));
    }

    // ==================== 辅助 ====================

    private String args(int quantity, String reason) {
        return "{\"orderNo\":\"" + ORDER_NO + "\",\"quantity\":" + quantity
                + ",\"reason\":\"" + reason + "\"}";
    }

    private Paused pause(ScriptedModel model) {
        AfterSaleAgent agent = newAgent(model);

        AgentRunContext ctx = new AgentRunContext();

        UserContextHolder.set(BUYER);
        try {
            agent.run("帮我退一把键盘，键盘坏了", ctx);
        } finally {
            UserContextHolder.clear();
        }

        assertEquals(AgentState.WAITING_APPROVAL, ctx.getState(), "前置条件：必须真的暂停了");

        String runId = ctx.getRunId().toString();
        createdRuns.add(runId);

        String approvalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE run_id = ?",
                String.class, runId);

        return new Paused(agent, runId, approvalId, ctx);
    }

    private AfterSaleAgent newAgent(ChatModel model) {
        AfterSaleAgent agent = new AfterSaleAgent(
                model,
                writeCallbacks,
                Runnable::run,
                toolEventMessageProvider);

        agent.setTaskRegistry(taskRegistry);
        agent.setRunRecorder(runRecorder);
        agent.setConcurrencyGuard(concurrencyGuard);

        // 真实策略 + 真实暂停/领取/收尾服务：不是替身
        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(approvalPauseGateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);
        agent.setApprovalClaimService(claimService);
        agent.setApprovalCompletionService(approvalCompletionService);

        agent.setMaxSteps(4);
        agent.setMaxToolCalls(10);
        agent.setTimeout(Duration.ofSeconds(30));

        return agent;
    }

    private ApprovalDecisionService.DecisionResult decide(Paused paused, boolean approve) {
        return decide(paused, approve, BUYER);
    }

    private ApprovalDecisionService.DecisionResult decide(
            Paused paused, boolean approve, String userId) {
        return decisionService.decide(paused.approvalId(), userId, approve);
    }

    private SseEmitter resume(Paused paused) {
        return new ApprovalResumeService(
                claimService, approvalProperties, List.of(paused.agent()))
                .resume(paused.approvalId(), BUYER);
    }

    private Map<String, Object> runRow(String runId) {
        return jdbcTemplate.queryForMap(
                "SELECT state, current_step, tool_call_count, approval_count, "
                        + "finished_at, error_code, execution_epoch "
                        + "FROM agent_run WHERE run_id = ?", runId);
    }

    private Map<String, Object> approvalRow(String approvalId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, resume_state, primary_tool, tool_count, summary_text, args_hash "
                        + "FROM agent_approval WHERE approval_id = ?", approvalId);
    }

    private record Paused(
            AfterSaleAgent agent, String runId, String approvalId, AgentRunContext ctx) {

        int toolCalls() {
            return ctx.getToolCallCount();
        }
    }

    /**
     * 脚本模型：按顺序回放响应；脚本用完就回答"已完成"。
     *
     * <p>它只决定"这一轮要不要调 submit_return、参数是什么"，
     * 其余全部走真实代码。
     */
    static class ScriptedModel implements ChatModel {

        private final Deque<Supplier<ChatResponse>> script = new ArrayDeque<>();

        final AtomicInteger calls = new AtomicInteger();

        ScriptedModel toolCall(String id, String name, String args) {
            script.add(() -> toolResponse(id, name, args));
            return this;
        }

        ScriptedModel answer(String text) {
            script.add(() -> textResponse(text));
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            Supplier<ChatResponse> next = script.poll();
            return next == null ? textResponse("已完成") : next.get();
        }
    }

    private static ChatResponse toolResponse(String id, String name, String args) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(
                        "",
                        Map.of(),
                        List.of(new AssistantMessage.ToolCall(
                                id, "function", name, args))))),
                ChatResponseMetadata.builder()
                        .model("stub-model")
                        .usage(new StubUsage(10, 5))
                        .build()
        );
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model("stub-model")
                        .usage(new StubUsage(10, 5))
                        .build()
        );
    }

    record StubUsage(int promptTokens, int completionTokens) implements Usage {

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
