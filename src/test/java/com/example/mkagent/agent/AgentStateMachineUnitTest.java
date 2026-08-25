package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.RunningAgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 状态机单元测试。
 *
 * 不启动 Spring 上下文、不调用任何真实模型或外部 API。
 * 通过可控的 TestAgent 直接驱动 BaseAgent 的 run() / runStream()，
 * 验证状态机、任务注册表、中断取消与清理逻辑。
 *
 * 覆盖用户要求的测试点：
 * 1. 正常任务最终状态为 SUCCEEDED；
 * 2. 达到最大步骤数 / 最大工具调用次数后状态为 MAX_STEPS_REACHED；
 * 3. 超时后状态为 TIMED_OUT；
 * 4. 主动取消后状态为 CANCELLED；
 * 5. 取消后不会进入新的 Agent Step；
 * 6. cleanup 不会重复执行；
 * 7. 运行中的任务能注册和移除。
 */
@Timeout(20)
class AgentStateMachineUnitTest {

    /**
     * 可控测试 Agent：
     * step 行为由每个用例通过 stepBehavior 注入，
     * 并记录最后一次执行的 ctx 与 cleanup 次数。
     */
    static class TestAgent extends BaseAgent {

        final AtomicInteger cleanupCount = new AtomicInteger();

        volatile AgentRunContext lastCtx;

        volatile Consumer<AgentRunContext> stepBehavior = ctx -> {
        };

        TestAgent(Executor executor) {
            super(executor);
        }

        @Override
        protected String step(AgentRunContext ctx) {
            lastCtx = ctx;
            stepBehavior.accept(ctx);
            return "step-" + ctx.getCurrentStep() + " 已执行";
        }

        @Override
        protected void cleanup(AgentRunContext ctx) {
            cleanupCount.incrementAndGet();
        }
    }

    /** 同步直接执行器：runAsync 提交的任务在当前线程立即执行。 */
    private static final Executor DIRECT = Runnable::run;

    // ===== 1. 正常任务 → SUCCEEDED =====

    @Test
    void succeededWhenFinalAnswerProduced() {
        TestAgent agent = new TestAgent(DIRECT);
        agent.stepBehavior = ctx -> {
            ctx.setFinalAnswer("这是最终回答");
            ctx.transitionTo(AgentState.SUCCEEDED);
        };

        String result = agent.run("任意任务");

        assertThat(result).isEqualTo("这是最终回答");
        assertThat(agent.lastCtx.getState()).isEqualTo(AgentState.SUCCEEDED);
    }

    // ===== 2. 达到预算 → MAX_STEPS_REACHED =====

    @Test
    void maxStepsReachedWhenStepBudgetExhausted() {
        TestAgent agent = new TestAgent(DIRECT);
        agent.setMaxSteps(3);
        agent.setMaxToolCalls(100);
        // step 从不结束任务，循环一直跑到 maxSteps

        agent.run("任意任务");

        assertThat(agent.lastCtx.getState())
                .isEqualTo(AgentState.MAX_STEPS_REACHED);
        assertThat(agent.lastCtx.getCurrentStep()).isEqualTo(3);
    }

    @Test
    void maxStepsReachedWhenToolCallBudgetExhausted() {
        TestAgent agent = new TestAgent(DIRECT);
        agent.setMaxSteps(100);
        agent.setMaxToolCalls(2);
        agent.stepBehavior = ctx -> ctx.addToolCallCount(1);

        agent.run("任意任务");

        assertThat(agent.lastCtx.getState())
                .isEqualTo(AgentState.MAX_STEPS_REACHED);
        assertThat(agent.lastCtx.getToolCallCount()).isEqualTo(2);
    }

    // ===== 3. 超时 → TIMED_OUT =====

    @Test
    void timedOutWhenRunningLongerThanTimeout() {
        TestAgent agent = new TestAgent(DIRECT);
        agent.setTimeout(Duration.ofMillis(80));
        agent.setMaxSteps(1000);
        agent.stepBehavior = ctx -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        agent.run("任意任务");

        assertThat(agent.lastCtx.getState()).isEqualTo(AgentState.TIMED_OUT);
    }

    // ===== 4. 异常 → FAILED =====

    @Test
    void failedWhenStepThrowsException() {
        TestAgent agent = new TestAgent(DIRECT);
        agent.stepBehavior = ctx -> {
            throw new IllegalStateException("模拟模型调用失败");
        };

        String result = agent.run("任意任务");

        assertThat(result).contains("执行错误");
        assertThat(agent.lastCtx.getState()).isEqualTo(AgentState.FAILED);
    }

    // ===== 5. 取消 → CANCELLED，且不再进入新 step =====

    @Test
    void cancelStopsLoopAndPreventsFurtherSteps() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TestAgent agent = new TestAgent(pool);
            AgentTaskRegistry registry = new AgentTaskRegistry();
            agent.setTaskRegistry(registry);
            agent.setMaxSteps(1000);

            CountDownLatch firstStepEntered = new CountDownLatch(1);
            AtomicInteger stepCount = new AtomicInteger();
            agent.stepBehavior = ctx -> {
                stepCount.incrementAndGet();
                firstStepEntered.countDown();
                try {
                    // 模拟一次很长的模型 / 工具调用
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(
                            new InterruptedException("被取消中断")
                    );
                }
            };

            agent.runStream("任意任务");

            assertThat(firstStepEntered.await(5, TimeUnit.SECONDS))
                    .as("任务应已进入第一步").isTrue();

            AgentRunContext ctx = agent.lastCtx;
            String runId = ctx.getRunId().toString();
            RunningAgentTask task = registry.get(runId);
            assertThat(task).as("运行中的任务应在注册表中").isNotNull();

            /*
             * 模拟取消接口的三步操作：
             * 1. 先置终态 CANCELLED（保证意图不被覆盖）；
             * 2. future.cancel(true)（CompletableFuture 不会中断线程）；
             * 3. 显式中断执行线程，让中断检测点生效。
             */
            assertThat(ctx.transitionTo(AgentState.CANCELLED)).isTrue();
            task.future().cancel(true);
            ctx.interruptExecutingThread();

            // 等待异步任务退出（被取消后 join 抛 CancellationException）。
            try {
                task.future().join();
            } catch (Exception ignored) {
                // 预期内的取消异常
            }

            assertThat(ctx.getState()).isEqualTo(AgentState.CANCELLED);

            // 关键断言：取消后步数不再增长，即没有进入新的 step。
            int countAfterCancel = stepCount.get();
            Thread.sleep(200);
            assertThat(stepCount.get())
                    .as("取消后不应再进入新的 step")
                    .isEqualTo(countAfterCancel);

            // 任务结束后已从注册表移除。
            assertThat(registry.contains(runId)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    // ===== 6. cleanup 不会重复执行 =====

    @Test
    void cleanupOnceNeverRunsTwice() {
        TestAgent agent = new TestAgent(DIRECT);
        AgentRunContext ctx = new AgentRunContext();
        AtomicBoolean cleaned = new AtomicBoolean(false);

        agent.cleanupOnce(ctx, cleaned);
        agent.cleanupOnce(ctx, cleaned);
        agent.cleanupOnce(ctx, cleaned);

        assertThat(agent.cleanupCount.get()).isEqualTo(1);
    }

    @Test
    void cleanupRunsExactlyOncePerRun() {
        // 正常路径：只执行一次
        TestAgent normalAgent = new TestAgent(DIRECT);
        normalAgent.stepBehavior = ctx -> {
            ctx.setFinalAnswer("完成");
            ctx.transitionTo(AgentState.SUCCEEDED);
        };
        normalAgent.run("任务");
        assertThat(normalAgent.cleanupCount.get()).isEqualTo(1);

        // 异常路径：同样只执行一次
        TestAgent failedAgent = new TestAgent(DIRECT);
        failedAgent.stepBehavior = ctx -> {
            throw new IllegalStateException("boom");
        };
        failedAgent.run("任务");
        assertThat(failedAgent.cleanupCount.get()).isEqualTo(1);
    }

    // ===== 7. 注册表：注册与移除 =====

    @Test
    void registryRegistersAndRemovesTask() {
        AgentTaskRegistry registry = new AgentTaskRegistry();
        AgentRunContext ctx = new AgentRunContext();
        String runId = ctx.getRunId().toString();

        registry.register(runId, new RunningAgentTask(ctx, new CompletableFuture<>()));

        assertThat(registry.contains(runId)).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get(runId).context()).isSameAs(ctx);

        registry.remove(runId);

        assertThat(registry.contains(runId)).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    void runStreamRegistersTaskAndRemovesOnCompletion() {
        TestAgent agent = new TestAgent(DIRECT);
        AgentTaskRegistry registry = new AgentTaskRegistry();
        agent.setTaskRegistry(registry);
        agent.stepBehavior = ctx -> {
            ctx.setFinalAnswer("已完成");
            ctx.transitionTo(AgentState.SUCCEEDED);
        };

        agent.runStream("任意任务");

        // DIRECT 执行器下任务同步完成，完成后注册表应为空。
        assertThat(registry.size()).isZero();
        assertThat(agent.lastCtx.getState()).isEqualTo(AgentState.SUCCEEDED);
    }

    // ===== 8. 终态保护：不可被覆盖 =====

    @Test
    void terminalStateCannotBeOverwritten() {
        AgentRunContext ctx = new AgentRunContext();
        assertThat(ctx.getState()).isEqualTo(AgentState.IDLE);

        assertThat(ctx.transitionTo(AgentState.RUNNING)).isTrue();
        assertThat(ctx.transitionTo(AgentState.SUCCEEDED)).isTrue();

        // 已是终态：不能回到 RUNNING，也不能被其它终态覆盖。
        assertThat(ctx.transitionTo(AgentState.RUNNING)).isFalse();
        assertThat(ctx.transitionTo(AgentState.CANCELLED)).isFalse();
        assertThat(ctx.transitionTo(AgentState.SUCCEEDED)).isFalse();

        assertThat(ctx.getState()).isEqualTo(AgentState.SUCCEEDED);
    }
}
