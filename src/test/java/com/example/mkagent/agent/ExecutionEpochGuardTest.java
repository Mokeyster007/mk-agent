package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行代次与资源释放的回归（批次 1）。
 *
 * 审批恢复会给同一个 runId 注册新句柄。这里锁住三条：
 * 1. 旧代次不能移除新句柄；
 * 2. 旧代次不能覆盖新注册；
 * 3. 一次执行只释放一次并发许可（暂停路径与 finally 不重复释放）。
 */
class ExecutionEpochGuardTest {

    private static final Executor DIRECT = Runnable::run;

    @Test
    @DisplayName("旧代次不能移除新句柄")
    void oldEpochCannotRemoveNewHandle() {
        AgentTaskRegistry registry = new AgentTaskRegistry();
        AgentRunContext ctx = new AgentRunContext();
        String runId = ctx.getRunId().toString();

        registry.register(runId, new RunningAgentTask(ctx, new CompletableFuture<>(), 0));
        registry.register(runId, new RunningAgentTask(ctx, new CompletableFuture<>(), 1));

        assertFalse(registry.remove(runId, 0),
                "旧代次移除必须失败");
        assertTrue(registry.contains(runId), "新句柄不能被旧线程删掉");
        assertEquals(1, registry.get(runId).epoch());

        assertTrue(registry.remove(runId, 1), "自己的句柄可以移除");
        assertFalse(registry.contains(runId));
    }

    @Test
    @DisplayName("旧代次不能覆盖新注册")
    void olderEpochCannotOverrideNewer() {
        AgentTaskRegistry registry = new AgentTaskRegistry();
        AgentRunContext ctx = new AgentRunContext();
        String runId = ctx.getRunId().toString();

        assertTrue(registry.register(runId,
                new RunningAgentTask(ctx, new CompletableFuture<>(), 1)));

        assertFalse(registry.register(runId,
                new RunningAgentTask(ctx, new CompletableFuture<>(), 0)),
                "更旧的代次不得覆盖");
        assertEquals(1, registry.get(runId).epoch());
    }

    @Test
    @DisplayName("release-once：同一 ctx 只允许释放一次")
    void permitReleaseIsIdempotent() {
        AgentRunContext ctx = new AgentRunContext();

        assertTrue(ctx.markPermitReleased(), "第一次应返回 true");
        assertFalse(ctx.markPermitReleased(), "第二次必须返回 false");
    }

    @Test
    @DisplayName("正常结束：许可归还且不超过上限")
    void permitReleasedExactlyOnceOnSuccess() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);

        EchoAgent agent = new EchoAgent(DIRECT);
        agent.setConcurrencyGuard(guard);
        agent.setMaxSteps(2);

        agent.run("任务一");
        assertEquals(1, guard.availablePermits(),
                "正常结束后许可必须归还，且不能多还");

        agent.run("任务二");
        assertEquals(1, guard.availablePermits(),
                "连续两次任务后仍应恰好 1 个许可");
    }

    @Test
    @DisplayName("暂停结束：许可归还、注册表清空，不重复释放")
    void permitReleasedOnceOnPause() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);
        AgentTaskRegistry registry = new AgentTaskRegistry();

        PausingAgent agent = new PausingAgent(DIRECT);
        agent.setConcurrencyGuard(guard);
        agent.setTaskRegistry(registry);
        agent.setMaxSteps(2);

        AgentRunContext ctx = new AgentRunContext();
        agent.run("会暂停的任务", ctx);

        assertEquals(AgentState.WAITING_APPROVAL, ctx.getState());
        assertEquals(1, guard.availablePermits(),
                "暂停也必须归还许可，且只能归还一次");
        assertEquals(0, registry.size(), "暂停后注册表必须清空");
    }

    /**
     * 一步成功的最小 Agent。
     */
    static class EchoAgent extends BaseAgent {

        EchoAgent(Executor executor) {
            super(executor);
        }

        @Override
        protected String step(AgentRunContext ctx) {
            ctx.setFinalAnswer("ok");
            ctx.transitionTo(AgentState.SUCCEEDED);
            return "ok";
        }
    }

    /**
     * 一步进入暂停的最小 Agent。
     */
    static class PausingAgent extends BaseAgent {

        PausingAgent(Executor executor) {
            super(executor);
        }

        @Override
        protected String step(AgentRunContext ctx) {
            ctx.transitionTo(AgentState.WAITING_APPROVAL);
            return null;
        }
    }
}
