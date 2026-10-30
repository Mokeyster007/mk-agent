package com.example.mkagent.eval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * MkManus 离线评测器。
 *
 * 执行链路：
 * 1. 从 JSON 加载用例；
 * 2. 每条用例创建一个全新的 AgentRunContext，
 *    用同步 run(prompt, ctx) 执行 MkManus；
 * 3. 从 ctx 采集 state / step / toolCallCount / token / toolTrace / 耗时；
 * 4. 确定性检查层判断工具选择与预算；
 * 5. 关键词无法判定时调用 LLM-as-judge；
 * 6. 汇总为 Markdown 报告。
 *
 * 隔离保证：
 * - 评测 Agent 不注入 AgentRunRecorder，不写 agent_run；
 * - 评测 Agent 不注入 AgentTaskRegistry，不进入线上取消链路；
 * - 评测使用独立的 AgentConcurrencyGuard，
 *   与线上 mkagent.agent.max-concurrency 不是同一个 Semaphore；
 * - 评测串行执行，避免并发负载影响 P50/P95 延迟统计；
 * - 整轮预算（Token / 时长 / 条数）耗尽即停止，避免一次误跑烧掉大量额度。
 */
@Component
public class AgentEvaluator {

    private static final Logger log =
            LoggerFactory.getLogger(AgentEvaluator.class);

    private final ChatModel agentChatModel;

    private final ToolCallback[] tools;

    private final ToolEventMessageProvider toolEventMessageProvider;

    private final EvalProperties properties;

    private final EvalCaseLoader caseLoader;

    private final DeterministicChecker deterministicChecker;

    private final LlmJudge judge;

    /**
     * Spring 注入入口：
     * 业务 Agent 使用生产 ChatModel；
     * 评委优先使用 auxiliary 模型（更便宜），没配置时回退到生产 ChatModel，
     * 再套一层低温度包装。
     *
     * 注意回退的是"用哪个模型"，不是"复用哪个对象"：
     * auxiliary 未启用时 judge 直接复用 chatModel 实例（同一个对象），
     * 因为此时没有第二个客户端可用；启用后则是两个独立实例、各自独立的
     * RetryTemplate（见 OpenAiCompatibleChatModelConfig）。
     */
    @Autowired
    public AgentEvaluator(
            ChatModel chatModel,
            @Qualifier("auxiliaryChatModel") ObjectProvider<ChatModel> auxiliaryChatModel,
            @Qualifier("mkToolCallbacks") ToolCallback[] tools,
            ToolEventMessageProvider toolEventMessageProvider,
            EvalProperties properties
    ) {
        this(
                chatModel,
                new LowTemperatureChatModel(
                        auxiliaryChatModel.getIfAvailable(() -> chatModel),
                        Objects.requireNonNull(properties, "EvalProperties 不能为空")
                                .getJudgeTemperature()
                ),
                tools,
                toolEventMessageProvider,
                properties
        );
    }

    /**
     * 单元测试入口：可以分别注入 Agent 模型与评委模型（桩）。
     */
    public AgentEvaluator(
            ChatModel agentChatModel,
            ChatModel judgeChatModel,
            ToolCallback[] tools,
            ToolEventMessageProvider toolEventMessageProvider,
            EvalProperties properties
    ) {
        this.agentChatModel = Objects.requireNonNull(
                agentChatModel, "agentChatModel 不能为空");
        this.tools = tools == null ? new ToolCallback[0] : tools.clone();
        this.toolEventMessageProvider = Objects.requireNonNull(
                toolEventMessageProvider, "ToolEventMessageProvider 不能为空");
        this.properties = Objects.requireNonNull(
                properties, "EvalProperties 不能为空");
        this.caseLoader = new EvalCaseLoader();
        this.deterministicChecker = new DeterministicChecker();
        this.judge = new LlmJudge(
                Objects.requireNonNull(judgeChatModel, "judgeChatModel 不能为空"),
                properties
        );
    }

    /**
     * 加载用例 → 执行全部用例 → 生成并写出 Markdown 报告。
     */
    public EvalReport evaluateAll() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "评测未启用：请设置 mkagent.eval.enabled=true"
            );
        }

        List<EvalCase> cases = loadCases();
        EvalRun run = evaluateWithBudget(cases);

        EvalReport report = new EvalReport(
                run.results(),
                resolveModel(run.results()),
                properties.getMaxConcurrency(),
                LocalDateTime.now(),
                run.budget()
        );

        Path reportPath = writeReport(report);

        log.info(
                "离线评测完成：total={}, passed={}, passRate={}%, judgeUsed={}, tokens={}, report={}",
                report.getTotalCount(),
                report.getPassedCount(),
                String.format(Locale.ROOT, "%.1f", report.getPassRate() * 100.0),
                report.getJudgeUsedCount(),
                run.budget().consumedTokens(),
                reportPath.toAbsolutePath()
        );

        /*
         * 预算触发时明确告警：本轮指标只覆盖已执行的部分用例，
         * 不能当作全量评测结论使用。
         */
        if (run.budgetStopped()) {
            log.warn(
                    "评测因预算提前结束：reason={}, detail={}, executed={}, skipped={}, report={}",
                    run.budget().stopReason().label(),
                    run.budget().stopDetail(),
                    run.budget().executedCases(),
                    run.budget().skippedCases(),
                    reportPath.toAbsolutePath()
            );
        }

        return report;
    }

    /**
     * 从配置路径加载并校验用例。
     */
    public List<EvalCase> loadCases() {
        return caseLoader.load(properties.getCasesPath());
    }

    /**
     * 执行一组用例，只返回用例结果（便于单测与旧调用方）。
     */
    public List<EvalResult> evaluate(List<EvalCase> cases) {
        return evaluateWithBudget(cases).results();
    }

    /**
     * 执行一组用例，并返回本轮预算快照。
     *
     * 串行执行，保证：
     * 1. 单条用例的耗时不被其他用例争抢模型/网络资源干扰；
     * 2. 评测结果可复现、可对比。
     *
     * 即便是串行，评测 Agent 仍会经过独立并发闸门，
     * 从而验证闸门配置不会与线上限流互相影响。
     *
     * 预算保护：每条用例开始前检查整轮预算（条数 / 时长 / Token），
     * 一旦耗尽立即停止，剩余用例记为"预算跳过"而不是执行后失败。
     */
    public EvalRun evaluateWithBudget(List<EvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("评测用例不能为空");
        }
        if (properties.getCaseTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "mkagent.eval.case-timeout-seconds 必须为正数"
            );
        }

        /*
         * 独立评测闸门：与 Spring 容器中的线上 AgentConcurrencyGuard
         * 完全不同的 Semaphore 实例。
         */
        AgentConcurrencyGuard evalConcurrencyGuard =
                new AgentConcurrencyGuard(properties.getMaxConcurrency());

        EvalAgent agent = new EvalAgent(
                agentChatModel,
                tools,
                toolEventMessageProvider,
                evalConcurrencyGuard
        );
        agent.setTimeout(Duration.ofSeconds(properties.getCaseTimeoutSeconds()));

        EvalBudget budget = EvalBudget.from(properties);

        List<EvalResult> results = new ArrayList<>(cases.size());

        for (EvalCase evalCase : cases) {
            if (!budget.allowsNextCase()) {
                log.warn(
                        "[eval] 预算用尽，跳过剩余用例：reason={}, detail={}, executed={}",
                        budget.getStopReason().label(),
                        budget.getStopDetail(),
                        budget.getExecutedCases()
                );
                break;
            }

            EvalResult result = evaluateCase(agent, evalCase, budget);
            results.add(result);

            log.info(
                    "[eval] case={}, category={}, passed={}, state={}, steps={}, tools={}, tokens={}, duration={}ms",
                    evalCase.getId(),
                    evalCase.getCategory(),
                    result.passed(),
                    result.execution().state(),
                    result.execution().steps(),
                    result.execution().toolTrace(),
                    result.execution().totalTokens(),
                    result.execution().durationMillis()
            );
        }

        /*
         * 串行执行结束后，评测闸门许可应全部归还。
         * 若不一致，说明 BaseAgent 的 finally 释放链路存在问题，
         * 只告警不影响单轮报告，但便于尽早发现泄漏。
         */
        if (evalConcurrencyGuard.availablePermits()
                != properties.getMaxConcurrency()) {
            log.warn(
                    "评测并发许可未完全归还：available={}, max={}",
                    evalConcurrencyGuard.availablePermits(),
                    properties.getMaxConcurrency()
            );
        }

        return new EvalRun(results, budget.summary(cases.size()));
    }

    /**
     * 执行单条用例。
     *
     * 任何异常都收敛为该用例失败，不中断整轮评测。
     * 执行结束后把 Agent Token + judge Token 记入整轮预算。
     */
    private EvalResult evaluateCase(
            EvalAgent agent,
            EvalCase evalCase,
            EvalBudget budget
    ) {
        List<String> deterministicFailures = new ArrayList<>();

        /*
         * 每条用例按自己的预算执行：
         * 预算既会限制 Agent Loop，也会在确定性检查层再次断言。
         */
        agent.setMaxSteps(evalCase.getMaxSteps());
        agent.setMaxToolCalls(evalCase.getMaxToolCalls());

        long startNanos = System.nanoTime();

        EvalExecution execution;
        try {
            AgentRunContext ctx = new AgentRunContext();
            agent.run(evalCase.getUserPrompt(), ctx);

            execution = EvalExecution.from(ctx, elapsedMillis(startNanos));

        } catch (Exception e) {
            execution = EvalExecution.failed(
                    elapsedMillis(startNanos),
                    "执行异常：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : "：" + e.getMessage())
            );
            deterministicFailures.add(
                    "执行异常：" + e.getClass().getSimpleName()
            );

            log.warn(
                    "评测用例执行异常：caseId={}, error={}",
                    evalCase.getId(),
                    e.toString()
            );
        }

        DeterministicChecker.CheckResult check =
                deterministicChecker.check(evalCase, execution);

        deterministicFailures.addAll(check.failures());

        boolean deterministicPassed = deterministicFailures.isEmpty();

        /*
         * 关键词无法判定（expectedKeywords 为空）时，
         * 且确定性检查已通过，才调用 LLM-as-judge。
         */
        boolean judgeUsed = false;
        boolean judgePassed = false;
        String judgeReason = "";
        long judgeTokens = 0L;

        if (deterministicPassed && evalCase.requiresJudge()) {
            judgeUsed = true;

            LlmJudge.JudgeVerdict verdict = judge.judge(evalCase, execution);
            judgePassed = verdict.passed();
            judgeReason = verdict.reason();
            judgeTokens = verdict.totalTokens();
        }

        /*
         * 整轮预算口径：Agent 模型 Token + judge Token（两者都是付费调用）。
         */
        budget.record(execution.totalTokens() + judgeTokens);

        return new EvalResult(
                evalCase,
                execution,
                deterministicPassed,
                deterministicFailures,
                judgeUsed,
                judgePassed,
                judgeReason
        );
    }

    /**
     * 写出 Markdown 报告。
     *
     * @return 实际写入路径（相对路径会基于当前工作目录解析）
     */
    public Path writeReport(EvalReport report) {
        String configuredPath = properties.getReportPath();
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("mkagent.eval.report-path 不能为空");
        }

        Path path = Path.of(configuredPath);

        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    path,
                    report.toMarkdown(),
                    StandardCharsets.UTF_8
            );
            return path;

        } catch (IOException e) {
            throw new IllegalStateException(
                    "写出评测报告失败：" + path.toAbsolutePath(),
                    e
            );
        }
    }

    /**
     * 报告中展示本次实际使用的模型名（取第一条非空值）。
     */
    private String resolveModel(List<EvalResult> results) {
        return results.stream()
                .map(result -> result.execution().model())
                .filter(model -> model != null && !model.isBlank())
                .findFirst()
                .orElse("");
    }

    private long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
