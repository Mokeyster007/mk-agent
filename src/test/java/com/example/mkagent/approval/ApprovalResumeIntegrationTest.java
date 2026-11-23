package com.example.mkagent.approval;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.BaseAgent;
import com.example.mkagent.agent.ShopAssistantAgent;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentEvent;
import com.example.mkagent.model.AgentEventType;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.shop.ShopSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.ShopCheckoutWriteTool;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批恢复的完整闭环（M3 批次 2）。
 *
 * 覆盖"决定 → 领取 → 启动标记 → 补做暂停轮 → 收尾"整条链路，
 * 用**真实**组件：真实扫描策略、真实写工具、真实 ToolCallingManager、
 * 真实事务服务、真实 H2。
 *
 * 恢复入口用同步执行器（{@code Runnable::run}）跑完全程，
 * 这样断言可以直接读取"恢复之后"的最终状态，不需要等线程；
 * 只有"重复恢复 / 断流后台继续"两类需要真并发的用例才换成异步执行器。
 *
 * 生产写工具白名单始终关闭：测试自己装配一个只含写工具的 Agent。
 */
@HermeticSpringBootTest
@Timeout(120)
class ApprovalResumeIntegrationTest {

    private static final String USER = ShopSeedInitializer.BUYER_SHOP_ONLY;

    private static final String OTHER = ShopSeedInitializer.BUYER_TWO;

    private static final String TOOL = "submit_checkout_order";

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

    @Autowired
    private ApprovalClaimService claimService;

    @Autowired
    private ApprovalExecutionCompletionService approvalCompletionService;

    @Autowired
    private ApprovalDecisionService decisionService;

    @Autowired
    private ApprovalProperties approvalProperties;

    private static final String NAME_PREFIX = "恢复-";

    private final List<String> createdRuns = new ArrayList<>();

    private int originalMaxPerRun;

    @BeforeEach
    void setUp() {
        originalMaxPerRun = approvalProperties.getMaxPerRun();

        taskRegistry.snapshot().forEach(t ->
                taskRegistry.remove(t.context().getRunId().toString(), t.epoch()));
    }

    @AfterEach
    void tearDown() {
        approvalProperties.setMaxPerRun(originalMaxPerRun);
        UserContextHolder.clear();

        /*
         * 测试用的是共享内存库（同一个 JVM 内所有上下文共用 mk_agent_test），
         * 本类真的会写入业务数据，必须自己收拾干净——
         * 否则别的用例（例如种子数据条数断言）会被这里多出来的歌单带偏。
         */
        jdbcTemplate.update(
                "DELETE FROM shop_order_item WHERE order_id IN (SELECT id FROM shop_order WHERE buyer_id IN (?, ?))",
                USER, OTHER);
        jdbcTemplate.update("DELETE FROM shop_order WHERE buyer_id IN (?, ?)", USER, OTHER);
        jdbcTemplate.update(
                "UPDATE shop_product SET price_cents = 36900, status = 'ACTIVE' WHERE id = ?",
                ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75);

        for (String runId : createdRuns) {
            jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdRuns.clear();
    }

    // ==================== 1. 批准 ====================

    @Test
    @DisplayName("批准后恢复：暂停轮执行一次，任务成功，审批 FINISHED")
    void approveThenResumeExecutesOriginalRoundOnce() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-a1", TOOL, createArgs(uniqueName()))
                .answer("歌单已经建好了");

        Paused paused = pause(model, 4, 10);

        assertEquals(0, ctxToolCalls(paused), "暂停轮不得执行工具");
        assertEquals(before, playlistCount(), "暂停时业务表不得变化");

        assertEquals(200, decide(paused, true).httpStatus(), "批准应当成功");

        resume(paused);

        // 1. 业务结果：恰好写一次
        assertEquals(before + 1, playlistCount(), "批准后必须恰好执行一次");

        // 2. 运行记录：成功、工具计 1 次、步数不重复计
        Map<String, Object> run = runRow(paused.runId());
        assertEquals("SUCCEEDED", run.get("state"), "实际：" + run);
        assertEquals(1, ((Number) run.get("tool_call_count")).intValue(),
                "暂停轮只执行一次，实际：" + run);
        assertEquals(2, ((Number) run.get("current_step")).intValue(),
                "恢复不得重复 nextStep（暂停第 1 步 → 下一轮是第 2 步），实际：" + run);
        assertNotNull(run.get("finished_at"));

        // 3. 审批：APPROVED + FINISHED
        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals("APPROVED", approval.get("status"));
        assertEquals("FINISHED", approval.get("resume_state"));
        assertNotNull(approval.get("claimed_epoch"), "领取必须绑定代次");

        // 4. 模型：暂停 1 次 + 恢复后 1 次
        assertEquals(2, model.calls.get(), "恢复后只应有一次新的模型调用");

        // 5. 资源
        assertEquals(0, taskRegistry.size(), "结束后注册表应清空");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "许可必须全部归还");

        // 6. 消息配对：恢复轮之后模型看到的是"一个 assistant + 一个 tool 响应"
        assertPaired(model.lastPrompt(), "call-a1", 1);
    }

    // ==================== 2. 拒绝 ====================

    @Test
    @DisplayName("拒绝后恢复：不执行工具、回填拒绝回执、任务继续到成功")
    void rejectBackfillsWithoutExecutingTools() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-r1", TOOL, createArgs(uniqueName()))
                .answer("好的，没有创建歌单");

        Paused paused = pause(model, 4, 10);

        assertEquals(200, decide(paused, false).httpStatus(), "拒绝应当成功");

        resume(paused);

        assertEquals(before, playlistCount(), "拒绝不得执行任何写工具");

        Map<String, Object> run = runRow(paused.runId());
        assertEquals("SUCCEEDED", run.get("state"), "实际：" + run);
        assertEquals(0, ((Number) run.get("tool_call_count")).intValue(),
                "拒绝不执行工具，不得增加工具计数，实际：" + run);

        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals("REJECTED", approval.get("status"));
        assertEquals("FINISHED", approval.get("resume_state"));

        // 拒绝回执必须与 pending call id 严格配对，且只出现一次 assistant
        assertPaired(model.lastPrompt(), "call-r1", 1);

        ToolResponseMessage response = lastToolResponse(model.lastPrompt());
        assertEquals(rejectionText(), response.getResponses().get(0).responseData(),
                "拒绝回执文本必须稳定，模型据此改口");
    }

    @Test
    @DisplayName("拒绝在工具额度为 0 时仍能完成回填")
    void rejectWorksWithZeroToolQuota() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-r0", TOOL, createArgs(uniqueName()))
                .answer("没有创建");

        Paused paused = pause(model, 4, 10);

        /*
         * 额度 0 只能在暂停之后设置：暂停本身也要进循环，
         * 一开始就 0 额度的话根本走不到暂停。配置指纹不含额度，
         * 因此检查点校验仍然通过。
         */
        paused.agent().setMaxToolCalls(0);

        assertEquals(200, decide(paused, false).httpStatus());
        resume(paused);

        assertEquals(before, playlistCount(), "拒绝不得写入业务数据");
        assertEquals(0, ((Number) runRow(paused.runId()).get("tool_call_count")).intValue());

        // 回填确实发生：模型第二次调用时看到了配对的拒绝回执
        assertPaired(model.lastPrompt(), "call-r0", 1);
    }

    // ==================== 3. 跨用户 ====================

    @Test
    @DisplayName("他人访问 approve/reject/resume/query/cancel 均为 404，且数据库快照不变")
    void crossUserRequestsAre404AndChangeNothing() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-x1", TOOL, createArgs(uniqueName()))
                .answer("好的");

        Paused paused = pause(model, 4, 10);

        String payloadBefore = checkpointPayload(paused.runId());
        Map<String, Object> approvalBefore = approvalRow(paused.approvalId());
        int callsAfterPause = model.calls.get();

        assertEquals(404, decide(paused, true, OTHER).httpStatus());
        assertEquals(404, decide(paused, false, OTHER).httpStatus());
        assertEquals(404, decisionService.cancel(paused.runId(), OTHER).httpStatus());

        BusinessException resumeDenied = assertThrows(BusinessException.class,
                () -> resumeService(paused.agent()).resume(paused.approvalId(), OTHER));
        assertEquals(404, resumeDenied.getHttpStatus());
        assertEquals("APPROVAL_NOT_FOUND", resumeDenied.getCode());

        BusinessException viewDenied = assertThrows(BusinessException.class,
                () -> resumeService(paused.agent()).view(paused.approvalId(), OTHER));
        assertEquals(404, viewDenied.getHttpStatus());

        // 审批、run、检查点、业务数据全部保持原样
        assertEquals(approvalBefore, approvalRow(paused.approvalId()),
                "跨用户请求不得改动审批记录（尤其不能置 INVALIDATED）");
        assertEquals("WAITING_APPROVAL", runRow(paused.runId()).get("state"));
        assertEquals(payloadBefore, checkpointPayload(paused.runId()),
                "检查点不得被改动");
        assertEquals(callsAfterPause, model.calls.get(),
                "不得因为他人请求而执行任何东西");

        // 所有者仍然可以正常批准
        assertEquals(200, decide(paused, true).httpStatus());
    }

    // ==================== 4. 预算 ====================

    @Test
    @DisplayName("maxSteps=1 暂停后批准：原轮仍执行一次")
    void maxStepsOneStillCompletesTheOriginalRound() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-m1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 1, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        resume(paused);

        assertEquals(before + 1, playlistCount(),
                "currentStep==maxSteps 不应阻止完成已经计过步的那一轮");

        Map<String, Object> run = runRow(paused.runId());
        assertEquals(1, ((Number) run.get("tool_call_count")).intValue(), "实际：" + run);
        assertEquals("MAX_STEPS_REACHED", run.get("state"),
                "原轮完成后再也进不了下一轮，应正常收尾为达到预算，实际：" + run);
    }

    // ==================== 5. 重复恢复 ====================

    @Test
    @DisplayName("同一审批重复恢复：至多一个执行者")
    void duplicateResumeRunsAtMostOneExecutor() throws Exception {
        long before = playlistCount();

        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-d1", TOOL, createArgs(uniqueName()))
                .blocking(entered, blocked, "已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        Executor async = command -> new Thread(command, "resume-test").start();

        // 真正并发需要一个异步执行器的 Agent；配置指纹与暂停时一致，检查点校验可通过
        ShopAssistantAgent asyncAgent = newAgent(model, async, 4, 10);
        ApprovalResumeService service =
                new ApprovalResumeService(claimService, approvalProperties, List.of(asyncAgent));

        service.resume(paused.approvalId(), USER);

        assertTrue(entered.await(10, TimeUnit.SECONDS), "恢复线程应当进入第二次模型调用");

        // 第二次恢复：领取已被占用，必须被明确拒绝
        BusinessException second = assertThrows(BusinessException.class,
                () -> service.resume(paused.approvalId(), USER));
        assertEquals(409, second.getHttpStatus());
        assertTrue(List.of("RESUME_IN_PROGRESS", "APPROVAL_ALREADY_FINISHED")
                        .contains(second.getCode()),
                "重复恢复必须被明确拒绝，实际：" + second.getCode());

        blocked.countDown();

        awaitRunState(paused.runId(), "SUCCEEDED");
        awaitPermitsReleased();

        assertEquals(before + 1, playlistCount(), "写工具只能执行一次");
        assertEquals(2, model.calls.get(), "模型只应被调用两次（暂停 1 + 恢复 1）");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "重复恢复不得泄漏许可");
    }

    // ==================== 6. 启动门槛 ====================

    @Test
    @DisplayName("排队期间保持 CLAIMED，执行器真正开始后才变 RUNNING")
    void claimedUntilExecutorActuallyRuns() throws Exception {
        long before = playlistCount();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-q0", TOOL, createArgs(uniqueName()))
                .blocking(entered, release, "已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        // 可控排队执行器：只把任务放进队列，不运行
        Deque<Runnable> queue = new ArrayDeque<>();
        ShopAssistantAgent queuedAgent = newAgent(model, queue::add, 4, 10);

        resumeService(queuedAgent).resume(paused.approvalId(), USER);

        assertEquals("CLAIMED", approvalRow(paused.approvalId()).get("resume_state"),
                "已提交但尚未执行时必须保持 CLAIMED");
        assertNotNull(approvalRow(paused.approvalId()).get("claimed_epoch"));
        assertEquals("RUNNING", runRow(paused.runId()).get("state"),
                "领取已把任务置为 RUNNING");
        assertEquals(1, queue.size(), "任务应已进入队列");
        assertEquals(0, ctxToolCalls(paused), "排队期间不得执行工具");
        assertEquals(before, playlistCount(), "排队期间不得写业务数据");

        // 真正开始执行：另起线程跑队列里的任务，进入模型调用后卡住
        Thread worker = new Thread(queue.poll(), "queued-resume");
        worker.start();

        assertTrue(entered.await(10, TimeUnit.SECONDS), "任务应已开始执行");

        assertEquals("RUNNING", approvalRow(paused.approvalId()).get("resume_state"),
                "任务真正开始后才允许 CLAIMED → RUNNING");
        assertEquals(before + 1, playlistCount(), "启动之后才会执行被冻结的写工具");

        release.countDown();
        worker.join(10_000);

        assertEquals("SUCCEEDED", runRow(paused.runId()).get("state"));
        assertEquals("FINISHED", approvalRow(paused.approvalId()).get("resume_state"));
    }

    @Test
    @DisplayName("启动标记 0 行：模型与工具零调用，且不写终态、许可归还")
    void startupMarkZeroRowsSkipsEverything() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-s1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());
        int callsAfterPause = model.calls.get();

        Deque<Runnable> queue = new ArrayDeque<>();
        ShopAssistantAgent queuedAgent = newAgent(model, queue::add, 4, 10);

        resumeService(queuedAgent).resume(paused.approvalId(), USER);
        assertEquals("CLAIMED", approvalRow(paused.approvalId()).get("resume_state"));

        /*
         * 模拟"领取已被别人补偿"：审批回到 PENDING_RESUME、run 回到 WAITING_APPROVAL。
         * 此时本次执行手里的 epoch 已失效，启动标记必然 0 行。
         */
        jdbcTemplate.update(
                "UPDATE agent_approval SET resume_state = 'PENDING_RESUME', claimed_epoch = NULL "
                        + "WHERE approval_id = ?", paused.approvalId());
        jdbcTemplate.update(
                "UPDATE agent_run SET state = 'WAITING_APPROVAL' WHERE run_id = ?",
                paused.runId());

        queue.poll().run();

        assertEquals(callsAfterPause, model.calls.get(), "启动标记 0 行后不得调用模型");
        assertEquals(before, playlistCount(), "启动标记 0 行后不得执行工具");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "许可必须归还且只归还一次");
        assertNull(taskRegistry.get(paused.runId()), "句柄必须注销");

        // 不能把已经不属于本次代次的 run 写成终态
        assertEquals("WAITING_APPROVAL", runRow(paused.runId()).get("state"),
                "启动门槛失败时不得写终态");
        assertNull(runRow(paused.runId()).get("finished_at"));
        assertEquals("PENDING_RESUME", approvalRow(paused.approvalId()).get("resume_state"));
    }

    @Test
    @DisplayName("启动标记抛异常：补偿回可重试，模型与工具零调用")
    void startupMarkExceptionCompensates() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-s2", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());
        int callsAfterPause = model.calls.get();

        // 启动标记写入故障：其余方法仍是真实实现
        ApprovalClaimService failing = new ApprovalClaimService(jdbcTemplate) {
            @Override
            public boolean markStarted(String approvalId, int epoch) {
                throw new IllegalStateException("模拟启动标记写入故障");
            }
        };

        ShopAssistantAgent agent = newAgent(model, Runnable::run, 4, 10);
        agent.setApprovalClaimService(failing);

        resumeService(agent).resume(paused.approvalId(), USER);

        assertEquals(callsAfterPause, model.calls.get(), "标记异常后不得调用模型");
        assertEquals(before, playlistCount(), "标记异常后不得执行工具");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "许可必须归还且只归还一次");
        assertNull(taskRegistry.get(paused.runId()));

        // 尚未开始业务执行（仍停在 CLAIMED），因此补偿是允许的
        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals("APPROVED", approval.get("status"), "补偿不得改动用户的决定");
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertNull(approval.get("claimed_epoch"));
        assertEquals("WAITING_APPROVAL", runRow(paused.runId()).get("state"));
    }

    @Test
    @DisplayName("补偿只吃 CLAIMED：已开始执行的 RUNNING 不会被回退")
    void compensateNeverRollsBackStartedExecution() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-c1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        ApprovalRecord record = claimService.find(paused.approvalId());
        int epoch = claimService.claim(record);
        assertTrue(claimService.markStarted(paused.approvalId(), epoch),
                "前置条件：启动标记成功");

        // 迟到的补偿（例如提交超时之后才走到 catch）不得回退已开始的执行
        claimService.compensate(paused.approvalId(), paused.runId(), epoch);

        assertEquals("RUNNING", approvalRow(paused.approvalId()).get("resume_state"),
                "已越过 CLAIMED 的审批不得被补偿回 PENDING_RESUME");
        assertEquals("RUNNING", runRow(paused.runId()).get("state"),
                "已开始执行的任务不得被回退成 WAITING_APPROVAL");
    }

    @Test
    @DisplayName("终态事务失败：不发 final_answer / done，只发脱敏 error，且不写假完成")
    void terminalPersistFailureSendsNoSuccessEvent() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-f1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        RecordingEmitter emitter = new RecordingEmitter();

        ShopAssistantAgent agent = newAgent(model, Runnable::run, 4, 10, emitter);

        /*
         * 终态事务固定失败：证明"写不进数据库就不发成功事件"。
         * 这里替换的是收尾服务（不是数据库），因为它代表"事务没提交"这一事实；
         * 事务本身的原子性由 ApprovalCompletionTransactionTest 用真实事务验证。
         */
        agent.setApprovalCompletionService(
                new ApprovalExecutionCompletionService(runRecorder, claimService) {
                    @Override
                    public boolean complete(
                            AgentRunContext ctx, String errorMessage, long totalCostMillis) {
                        throw new IllegalStateException("模拟终态事务提交失败");
                    }
                });

        resumeService(agent).resume(paused.approvalId(), USER);

        // 1. 事件：没有任何"成功"信号，只有脱敏错误
        assertTrue(emitter.has(AgentEventType.ERROR), "必须告诉客户端执行结果没保存");
        assertFalse(emitter.has(AgentEventType.FINAL_ANSWER),
                "终态没提交就发 final_answer，等于告诉用户「库里已有这个成功结果」");
        assertFalse(emitter.has(AgentEventType.DONE),
                "终态没提交不得发 done（done 表示本段执行正常结束）");

        String errorText = emitter.textOf(AgentEventType.ERROR);
        assertTrue(errorText.contains("执行结果未能保存"), "错误必须是脱敏提示，实际：" + errorText);
        assertFalse(errorText.contains("模拟终态事务提交失败"),
                "不得把内部异常原文发给客户端，实际：" + errorText);

        // 2. 数据库：没有假完成
        Map<String, Object> run = runRow(paused.runId());
        assertEquals("RUNNING", run.get("state"), "写失败不得留下终态，实际：" + run);
        assertNull(run.get("finished_at"), "写失败不得写 finished_at，实际：" + run);
        assertEquals("RUNNING", approvalRow(paused.approvalId()).get("resume_state"),
                "审批也不得被单独收尾");

        // 3. 资源：句柄与许可按本次执行段正常清理
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "许可必须归还且只归还一次");
        assertNull(taskRegistry.get(paused.runId()), "句柄必须注销");
    }

    @Test
    @DisplayName("业务状态在执行时重新检查：暂停期间商品改价是业务失败，不是检查点损坏")
    void businessOwnershipRecheckedAtExecution() {
        long ordersBefore = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-o1", TOOL, createArgs(uniqueName()))
                .answer("商品价格已变动，请按最新报价重新确认。");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        // 合法检查点，但业务价格在暂停期间变动：执行时必须重查并拒绝落单
        jdbcTemplate.update("UPDATE shop_product SET price_cents = 39900 WHERE id = ?",
                ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75);

        resume(paused);

        assertEquals("SUCCEEDED", runRow(paused.runId()).get("state"),
                "业务数据变化不应把任务判成失败");
        assertEquals("APPROVED", approvalRow(paused.approvalId()).get("status"),
                "不得因为业务状态变化就 INVALIDATED 审批");
        assertEquals("FINISHED", approvalRow(paused.approvalId()).get("resume_state"));

        assertEquals(ordersBefore, playlistCount(),
                "执行时的实时报价校验必须拦住落单");
    }

    @Test
    @DisplayName("执行器拒绝：安全补偿为可重试，随后重试成功")
    void executorRejectionIsRetryable() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-e1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        Executor rejecting = command -> {
            throw new RejectedExecutionException("线程池已满");
        };

        ShopAssistantAgent failingAgent = newAgent(model, rejecting, 4, 10);

        BusinessException e = assertThrows(BusinessException.class,
                () -> resumeService(failingAgent).resume(paused.approvalId(), USER));
        assertEquals(409, e.getHttpStatus());
        assertEquals("RESUME_SUBMIT_FAILED", e.getCode());

        // 补偿证据：审批回到 PENDING_RESUME、run 回到 WAITING_APPROVAL、代次不回退
        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals("APPROVED", approval.get("status"), "补偿不得改动决定");
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertNull(approval.get("claimed_epoch"), "补偿必须清空领取代次");

        Map<String, Object> run = runRow(paused.runId());
        assertEquals("WAITING_APPROVAL", run.get("state"));
        assertTrue(((Number) run.get("execution_epoch")).intValue() >= 1,
                "代次只增不减，实际：" + run);
        assertNull(run.get("finished_at"), "补偿不是失败终态");

        assertEquals(before, playlistCount(), "执行器拒绝时不得执行工具");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "补偿后许可必须归还");
        assertEquals(0, taskRegistry.size(), "补偿后不得残留句柄");

        // 重试：换一个正常执行器，应当成功
        resume(paused);
        assertEquals(before + 1, playlistCount(), "重试必须能真正执行");
        assertEquals("FINISHED", approvalRow(paused.approvalId()).get("resume_state"));
    }

    @Test
    @DisplayName("已拒绝的审批遇到执行器拒绝：同样可安全重试")
    void rejectedExecutorRejectionIsRetryable() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-e2", TOOL, createArgs(uniqueName()))
                .answer("没有创建");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, false).httpStatus());

        Executor rejecting = command -> {
            throw new RejectedExecutionException("线程池已满");
        };

        BusinessException e = assertThrows(BusinessException.class,
                () -> resumeService(newAgent(model, rejecting, 4, 10))
                        .resume(paused.approvalId(), USER));

        assertEquals("RESUME_SUBMIT_FAILED", e.getCode());

        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals("REJECTED", approval.get("status"), "补偿不得把拒绝改成别的决定");
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertNull(approval.get("claimed_epoch"));
        assertEquals("WAITING_APPROVAL", runRow(paused.runId()).get("state"));

        assertEquals(before, playlistCount(), "拒绝路径本来就不写业务数据");

        // 重试：仍然走"拒绝回填"，不执行工具
        resume(paused);
        assertEquals(before, playlistCount());
        assertEquals("SUCCEEDED", runRow(paused.runId()).get("state"));
        assertEquals("FINISHED", approvalRow(paused.approvalId()).get("resume_state"));
    }

    @Test
    @DisplayName("拿不到并发许可：429 且补偿为可重试，不计入失败次数")
    void concurrencyUnavailableIsRetryable() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-q1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        // 占满所有许可，模拟系统繁忙
        int permits = concurrencyGuard.getMaxConcurrency();
        for (int i = 0; i < permits; i++) {
            assertTrue(concurrencyGuard.tryAcquire(), "应当能占满许可");
        }

        try {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> resumeService(paused.agent()).resume(paused.approvalId(), USER));

            assertEquals(429, e.getHttpStatus());
            assertEquals("RESUME_CONCURRENCY_LIMIT", e.getCode());

            Map<String, Object> approval = approvalRow(paused.approvalId());
            assertEquals("APPROVED", approval.get("status"), "系统忙不得作废用户的批准");
            assertEquals("PENDING_RESUME", approval.get("resume_state"));
            assertEquals("WAITING_APPROVAL", runRow(paused.runId()).get("state"));
            assertEquals(before, playlistCount(), "没有许可时不得执行任何工具");
        } finally {
            for (int i = 0; i < permits; i++) {
                concurrencyGuard.release();
            }
        }

        // 许可恢复后可以直接重试成功
        resume(paused);
        assertEquals(before + 1, playlistCount());
        assertEquals("SUCCEEDED", runRow(paused.runId()).get("state"));
    }

    // ==================== 7. 启动期限 ====================

    @Test
    @DisplayName("超过启动期限：明确失败，不执行任何工具")
    void startDeadlineExpiredFailsClosed() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-t1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        // 把决定时间推到很久以前：超过 mkagent.approval.resume-start-timeout
        jdbcTemplate.update(
                "UPDATE agent_approval SET decided_at = ? WHERE approval_id = ?",
                LocalDateTime.now().minusHours(2), paused.approvalId());

        BusinessException e = assertThrows(BusinessException.class,
                () -> resumeService(paused.agent()).resume(paused.approvalId(), USER));

        assertEquals(410, e.getHttpStatus());
        assertEquals("APPROVAL_RESUME_TIMEOUT", e.getCode());

        assertEquals(before, playlistCount(), "过期不得执行工具");

        Map<String, Object> run = runRow(paused.runId());
        assertEquals("FAILED", run.get("state"), "实际：" + run);
        assertEquals("APPROVAL_RESUME_TIMEOUT", run.get("error_code"));
        assertNotNull(run.get("finished_at"), "失败必须有终态时间");

        assertEquals("FAILED", approvalRow(paused.approvalId()).get("resume_state"));
    }

    // ==================== 8. 检查点完整性 ====================

    @Test
    @DisplayName("检查点归属被篡改：失败关闭，不执行工具")
    void tamperedCheckpointFailsClosed() {
        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-c1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        String payload = checkpointPayload(paused.runId());
        assertTrue(payload.contains(USER), "检查点应包含归属用户");
        jdbcTemplate.update(
                "UPDATE agent_checkpoint SET payload_json = ? WHERE run_id = ?",
                payload.replace(USER, OTHER), paused.runId());

        BusinessException e = assertThrows(BusinessException.class,
                () -> resumeService(paused.agent()).resume(paused.approvalId(), USER));

        assertEquals(409, e.getHttpStatus());
        assertEquals("CHECKPOINT_OWNER_MISMATCH", e.getCode());

        assertEquals(before, playlistCount(), "校验失败不得执行工具");
        assertEquals("FAILED", runRow(paused.runId()).get("state"));
        assertEquals("INVALIDATED", approvalRow(paused.approvalId()).get("status"),
                "损坏的检查点必须让审批失效，而不是留在 PENDING_RESUME");
    }

    @Test
    @DisplayName("检查点缺失：失败关闭")
    void missingCheckpointFailsClosed() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-c2", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", paused.runId());

        BusinessException e = assertThrows(BusinessException.class,
                () -> resumeService(paused.agent()).resume(paused.approvalId(), USER));

        assertEquals(409, e.getHttpStatus());
        assertEquals("CHECKPOINT_MISSING", e.getCode());
    }

    // ==================== 9. 取消 ====================

    @Test
    @DisplayName("取消暂停任务：审批与 run 同时 CANCELLED，之后不能再批准")
    void cancelPausedRunWinsAgainstApprove() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-k1", TOOL, createArgs(uniqueName()))
                .answer("已完成");

        Paused paused = pause(model, 4, 10);

        ApprovalDecisionService.DecisionResult cancelled =
                decisionService.cancel(paused.runId(), USER);

        assertTrue(cancelled.ok(), "取消应当成功");
        assertEquals("CANCELLED", runRow(paused.runId()).get("state"));
        assertEquals("CANCELLED", approvalRow(paused.approvalId()).get("status"));

        ApprovalDecisionService.DecisionResult late = decide(paused, true);
        assertEquals(409, late.httpStatus(), "取消之后再批准必须冲突");
        assertEquals("APPROVAL_NOT_PENDING", late.code());
        assertEquals("CANCELLED", late.record().status());
    }

    // ==================== 10. 恢复后再次暂停 ====================

    @Test
    @DisplayName("恢复后再次暂停：旧审批 FINISHED、新审批 PENDING、run 仍 WAITING_APPROVAL")
    void rePauseFinishesPreviousApproval() {
        long before = playlistCount();

        String first = uniqueName();
        String second = uniqueName();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-p1", TOOL, createArgs(first))
                .toolCall("call-p2", TOOL, createArgs(second))
                .answer("两个歌单都建好了");

        Paused paused = pause(model, 5, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        resume(paused);

        // 恢复 → 执行第一轮 → 第二轮又命中写工具 → 再次暂停
        Map<String, Object> run = runRow(paused.runId());
        assertEquals("WAITING_APPROVAL", run.get("state"), "实际：" + run);
        assertEquals(2, ((Number) run.get("approval_count")).intValue(), "实际：" + run);
        assertNull(run.get("finished_at"), "再次暂停仍不是终态");

        assertEquals("FINISHED", approvalRow(paused.approvalId()).get("resume_state"),
                "旧审批的执行阶段必须收尾，否则会与新审批重叠");

        String newApprovalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE run_id = ? "
                        + "AND status = 'PENDING'",
                String.class, paused.runId());

        assertEquals("PENDING_RESUME", approvalRow(newApprovalId).get("resume_state"));
        assertEquals(before + 1, playlistCount(), "只有第一轮被执行");

        // 新审批可以继续走完整流程
        ApprovalResumeService service = resumeService(paused.agent());
        assertEquals(200, decisionService.decide(newApprovalId, USER, true).httpStatus());
        service.resume(newApprovalId, USER);

        assertEquals(before + 2, playlistCount(), "第二条审批批准后应执行第二个歌单");
        assertEquals("SUCCEEDED", runRow(paused.runId()).get("state"));
        assertEquals("FINISHED", approvalRow(newApprovalId).get("resume_state"));
    }

    // ==================== 11. 审批次数上限 ====================

    @Test
    @DisplayName("审批次数触顶：FAILED + APPROVAL_LIMIT_REACHED，不创建新审批、不执行写工具")
    void approvalLimitReachedFailsWithoutExecuting() {
        approvalProperties.setMaxPerRun(1);

        long before = playlistCount();

        ScriptedModel model = new ScriptedModel()
                .toolCall("call-l1", TOOL, createArgs(uniqueName()))
                .toolCall("call-l2", TOOL, createArgs(uniqueName()))
                .answer("完成");

        Paused paused = pause(model, 5, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        resume(paused);

        Map<String, Object> run = runRow(paused.runId());
        assertEquals("FAILED", run.get("state"), "实际：" + run);
        assertEquals("APPROVAL_LIMIT_REACHED", run.get("error_code"), "实际：" + run);
        assertEquals(1, ((Number) run.get("approval_count")).intValue(),
                "触顶的那次暂停必须整体回滚，不能留下计数，实际：" + run);

        Integer approvals = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_approval WHERE run_id = ?",
                Integer.class, paused.runId());
        assertEquals(1, approvals, "不得创建第二条审批");

        assertEquals(before + 1, playlistCount(), "只有第一轮被执行，第二轮不得执行");
    }

    // ==================== 12. 查询与路由 ====================

    @Test
    @DisplayName("查询只返回摘要与状态，不含检查点原文")
    void viewReturnsSummaryWithoutCheckpoint() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-v1", TOOL, createArgs(uniqueName()))
                .answer("完成");

        Paused paused = pause(model, 4, 10);

        ApprovalView view = resumeService(paused.agent()).view(paused.approvalId(), USER);

        assertEquals(paused.approvalId(), view.approvalId());
        assertEquals("PENDING", view.status());
        assertEquals("PENDING_RESUME", view.resumeState());
        assertEquals(TOOL, view.primaryTool());
        assertEquals(1, view.toolCount());
        assertNotNull(view.summary(), "应返回结构化摘要");
        assertFalse(view.summaryText().isBlank(), "摘要文本不能为空");
    }

    @Test
    @DisplayName("找不到对应 Agent：503 且不改动任何记录")
    void unknownAgentKeepsRecordUntouched() {
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-u1", TOOL, createArgs(uniqueName()))
                .answer("完成");

        Paused paused = pause(model, 4, 10);
        assertEquals(200, decide(paused, true).httpStatus());

        ApprovalResumeService empty =
                new ApprovalResumeService(claimService, approvalProperties, List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> empty.resume(paused.approvalId(), USER));

        assertEquals(503, e.getHttpStatus());
        assertEquals("AGENT_UNAVAILABLE", e.getCode());

        Map<String, Object> approval = approvalRow(paused.approvalId());
        assertEquals("APPROVED", approval.get("status"));
        assertEquals("PENDING_RESUME", approval.get("resume_state"),
                "部署问题不应把审批判死，修好之后要能重试");
    }

    // ==================== 辅助 ====================

    private record Paused(ShopAssistantAgent agent, String runId, String approvalId, ScriptedModel model) {
    }

    private int ctxToolCalls(Paused paused) {
        return ((Number) runRow(paused.runId()).get("tool_call_count")).intValue();
    }

    private ApprovalDecisionService.DecisionResult decide(Paused paused, boolean approve) {
        return decide(paused, approve, USER);
    }

    private ApprovalDecisionService.DecisionResult decide(
            Paused paused, boolean approve, String userId) {
        return decisionService.decide(paused.approvalId(), userId, approve);
    }

    private SseEmitter resume(Paused paused) {
        return resumeService(paused.agent()).resume(paused.approvalId(), USER);
    }

    private ApprovalResumeService resumeService(BaseAgent agent) {
        return new ApprovalResumeService(
                claimService, approvalProperties, List.of(agent));
    }

    private Paused pause(ScriptedModel model, int maxSteps, int maxToolCalls) {
        ShopAssistantAgent agent = newAgent(model, Runnable::run, maxSteps, maxToolCalls);

        AgentRunContext ctx = new AgentRunContext();

        UserContextHolder.set(USER);
        try {
            agent.run("请帮我下单 Air75 一把", ctx);
        } finally {
            UserContextHolder.clear();
        }

        assertEquals(AgentState.WAITING_APPROVAL, ctx.getState(),
                "前置条件：必须真的暂停了");

        String runId = ctx.getRunId().toString();
        createdRuns.add(runId);

        String approvalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE run_id = ?",
                String.class, runId);

        return new Paused(agent, runId, approvalId, model);
    }

    private ShopAssistantAgent newAgent(
            ChatModel model, Executor executor, int maxSteps, int maxToolCalls) {
        return newAgent(model, executor, maxSteps, maxToolCalls, null);
    }

    private ShopAssistantAgent newAgent(
            ChatModel model,
            Executor executor,
            int maxSteps,
            int maxToolCalls,
            SseEmitter resumeEmitter
    ) {
        ToolCallback[] callbacks = ToolCallbacks.from(checkoutWriteTool);

        ShopAssistantAgent agent = resumeEmitter == null
                ? new ShopAssistantAgent(
                        model,
                        callbacks,
                        executor,
                        toolEventMessageProvider
                )
                : new ShopAssistantAgent(
                        model,
                        callbacks,
                        executor,
                        toolEventMessageProvider
                ) {
                    @Override
                    public SseEmitter createResumeEmitter(AgentRunContext ctx) {
                        return resumeEmitter;
                    }
                };

        agent.setTaskRegistry(taskRegistry);
        agent.setRunRecorder(runRecorder);
        agent.setConcurrencyGuard(concurrencyGuard);

        // 真实策略 + 真实暂停/领取服务：不是替身
        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(approvalPauseGateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);
        agent.setApprovalClaimService(claimService);
        agent.setApprovalCompletionService(approvalCompletionService);

        agent.setMaxSteps(maxSteps);
        agent.setMaxToolCalls(maxToolCalls);
        agent.setTimeout(Duration.ofSeconds(30));

        return agent;
    }

    private String uniqueName() {
        return NAME_PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createArgs(String name) {
        return "{\"sku\":\"SKU-KEYBOARD-A75\",\"quantity\":1,\"expectedUnitPriceCents\":36900}";
    }

    private long playlistCount() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE buyer_id = ?", Long.class, USER);
        return value == null ? 0L : value;
    }

    private Map<String, Object> runRow(String runId) {
        return jdbcTemplate.queryForMap(
                "SELECT state, current_step, tool_call_count, approval_count, "
                        + "finished_at, error_code, execution_epoch "
                        + "FROM agent_run WHERE run_id = ?", runId);
    }

    private Map<String, Object> approvalRow(String approvalId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, resume_state, claimed_epoch, decided_at, "
                        + "args_hash, primary_tool, tool_count, summary_text "
                        + "FROM agent_approval WHERE approval_id = ?", approvalId);
    }

    private String checkpointPayload(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_json FROM agent_checkpoint WHERE run_id = ?",
                String.class, runId);
    }

    private void awaitRunState(String runId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        Object state = null;

        while (System.currentTimeMillis() < deadline) {
            state = runRow(runId).get("state");
            if (expected.equals(state)) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals(expected, state, "等待任务状态超时");
    }

    /**
     * 等待并发许可全部归还。
     *
     * 终态写库发生在 finally 释放许可之前，因此"看到 SUCCEEDED"不等于
     * "许可已经归还"；异步用例必须显式等这一步，否则断言会随机失败。
     */
    private void awaitPermitsReleased() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (concurrencyGuard.availablePermits() == concurrencyGuard.getMaxConcurrency()) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "等待许可归还超时");
    }

    /**
     * 断言消息配对：assistant(toolCalls) 恰好一条，tool 响应按 id 一一对应。
     *
     * 这条断言直接跑在**真实** ToolCallingManager 产出的对话历史上：
     * 多追加一次 assistant、或漏掉一条 tool 响应，都会让它失败。
     */
    private void assertPaired(Prompt prompt, String callId, int expectedResponses) {
        assertNotNull(prompt, "必须捕获到模型收到的 Prompt");

        List<Message> messages = prompt.getInstructions();
        long assistants = messages.stream()
                .filter(m -> m instanceof AssistantMessage am && !am.getToolCalls().isEmpty())
                .count();

        assertEquals(1, assistants,
                "assistant(toolCalls) 只能出现一次，实际消息：" + describe(messages));

        ToolResponseMessage response = lastToolResponse(prompt);
        assertEquals(expectedResponses, response.getResponses().size(),
                "tool 响应数量必须与 pending 调用一致");
        assertEquals(callId, response.getResponses().get(0).id(),
                "tool 响应 id 必须与模型给出的 call id 一致");
    }

    private ToolResponseMessage lastToolResponse(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage response) {
                return response;
            }
        }
        throw new AssertionError("对话历史中没有 ToolResponseMessage：" + describe(messages));
    }

    private String describe(List<Message> messages) {
        List<String> kinds = new ArrayList<>();
        messages.forEach(m -> kinds.add(m.getMessageType().name()));
        return kinds.toString();
    }

    /**
     * 拒绝回执文本（与 ToolCallAgent 保持一致）。
     */
    private String rejectionText() {
        return "用户拒绝执行该操作，未做任何修改。";
    }

    /**
     * 记录 SSE 事件的 emitter 替身（不建立真实连接）。
     *
     * complete() 被重写为空操作：不触发 onCompletion 生命周期回调，
     * 避免测试里"关闭连接"反过来改任务状态，掩盖真正要断言的事件顺序。
     */
    static class RecordingEmitter extends SseEmitter {

        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) {
            builder.build().forEach(data -> {
                Object payload = data.getData();
                if (payload instanceof AgentEvent event) {
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

        String textOf(AgentEventType type) {
            return events.stream()
                    .filter(event -> type.wireName().equals(event.getType()))
                    .map(AgentEvent::getMessage)
                    .reduce((first, second) -> second)
                    .orElse("");
        }
    }

    /**
     * 脚本模型：按顺序回放响应，并记录最后一次 Prompt 便于断言消息配对。
     */
    static class ScriptedModel implements ChatModel {

        private final Deque<Supplier<ChatResponse>> script = new ArrayDeque<>();

        final AtomicInteger calls = new AtomicInteger();

        private volatile Prompt lastPrompt;

        ScriptedModel toolCall(String id, String name, String args) {
            script.add(() -> toolResponse(id, name, args));
            return this;
        }

        ScriptedModel answer(String text) {
            script.add(() -> textResponse(text));
            return this;
        }

        ScriptedModel blocking(CountDownLatch entered, CountDownLatch release, String text) {
            script.add(() -> {
                entered.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return textResponse(text);
            });
            return this;
        }

        Prompt lastPrompt() {
            return lastPrompt;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            lastPrompt = prompt;

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
