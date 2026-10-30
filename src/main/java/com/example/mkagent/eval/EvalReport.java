package com.example.mkagent.eval;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一轮离线评测的汇总报告。
 *
 * 指标口径：
 * 1. 总通过率 = 通过用例数 / 用例总数；
 * 2. 分类别通过率 = 该类别通过数 / 该类别用例数；
 * 3. 平均步数 / 平均 Token 对全部用例取算术平均；
 * 4. P50 / P95 耗时使用 nearest-rank 百分位：
 *    index = ceil(p * n) - 1。
 *
 * 报告只包含聚合指标与失败用例摘要，
 * 不包含工具参数、API Key、系统提示词等敏感信息。
 *
 * 若本轮评测因预算提前结束，报告会额外注明"只覆盖已执行用例"，
 * 避免把部分结果当成全量结论。
 */
public class EvalReport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 报告中的类别展示顺序。
     */
    private static final List<String> CATEGORY_ORDER = List.of(
            "SEARCH",
            "NO_TOOL",
            "URL_SCRAPE",
            "SSRF",
            "PROMPT_INJECTION",
            "INSUFFICIENT_INFO"
    );

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "SEARCH", "必须调用搜索",
            "NO_TOOL", "不应调用工具",
            "URL_SCRAPE", "URL 抓取",
            "SSRF", "内网 SSRF 拒绝",
            "PROMPT_INJECTION", "提示词注入",
            "INSUFFICIENT_INFO", "信息不足拒答"
    );

    private static final int MAX_FAILURE_ANSWER_CHARS = 800;

    private final List<EvalResult> results;

    private final String agentModel;

    private final int evalMaxConcurrency;

    private final LocalDateTime generatedAt;

    /**
     * 整轮预算快照；为 null 表示该报告不记录预算信息（如单测直接构造）。
     */
    private final EvalBudgetSummary budget;

    public EvalReport(
            List<EvalResult> results,
            String agentModel,
            int evalMaxConcurrency
    ) {
        this(results, agentModel, evalMaxConcurrency, LocalDateTime.now(), null);
    }

    public EvalReport(
            List<EvalResult> results,
            String agentModel,
            int evalMaxConcurrency,
            LocalDateTime generatedAt
    ) {
        this(results, agentModel, evalMaxConcurrency, generatedAt, null);
    }

    public EvalReport(
            List<EvalResult> results,
            String agentModel,
            int evalMaxConcurrency,
            LocalDateTime generatedAt,
            EvalBudgetSummary budget
    ) {
        this.results = results == null ? List.of() : List.copyOf(results);
        this.agentModel = agentModel == null ? "" : agentModel;
        this.evalMaxConcurrency = evalMaxConcurrency;
        this.generatedAt = generatedAt == null
                ? LocalDateTime.now()
                : generatedAt;
        this.budget = budget;
    }

    public List<EvalResult> getResults() {
        return results;
    }

    public EvalBudgetSummary getBudget() {
        return budget;
    }

    public int getTotalCount() {
        return results.size();
    }

    public int getPassedCount() {
        return (int) results.stream().filter(EvalResult::passed).count();
    }

    public double getPassRate() {
        return getTotalCount() == 0
                ? 0.0
                : (double) getPassedCount() / getTotalCount();
    }

    public int getJudgeUsedCount() {
        return (int) results.stream()
                .filter(EvalResult::judgeUsed)
                .count();
    }

    public double getAverageSteps() {
        return results.stream()
                .mapToInt(result -> result.execution().steps())
                .average()
                .orElse(0.0);
    }

    public double getAverageTokens() {
        return results.stream()
                .mapToLong(result -> result.execution().totalTokens())
                .average()
                .orElse(0.0);
    }

    public long getP50DurationMillis() {
        return percentile(50);
    }

    public long getP95DurationMillis() {
        return percentile(95);
    }

    /**
     * 分类别统计，按固定顺序输出，未出现的类别不展示。
     */
    public List<CategoryStat> getCategoryStats() {
        Map<String, int[]> counters = new LinkedHashMap<>();

        for (String category : CATEGORY_ORDER) {
            counters.put(category, new int[2]);
        }

        for (EvalResult result : results) {
            String category = result.evalCase().getCategory();
            counters.computeIfAbsent(category, key -> new int[2]);
            counters.get(category)[0]++;
            if (result.passed()) {
                counters.get(category)[1]++;
            }
        }

        List<CategoryStat> stats = new ArrayList<>();
        counters.forEach((category, counts) -> {
            if (counts[0] > 0) {
                stats.add(new CategoryStat(
                        category,
                        categoryLabel(category),
                        counts[0],
                        counts[1]
                ));
            }
        });
        return List.copyOf(stats);
    }

    /**
     * 渲染为 Markdown。
     */
    public String toMarkdown() {
        StringBuilder markdown = new StringBuilder();

        markdown.append("# MkManus 离线评测报告\n\n");

        markdown.append("- 生成时间：")
                .append(generatedAt.format(TIME_FORMAT))
                .append('\n');
        markdown.append("- 被测 Agent：MkManus（评测专用 AgentType=EVAL，复用 MANUS 工具白名单与系统提示词）\n");
        markdown.append("- 评测并发闸门：mkagent.eval.max-concurrency=")
                .append(evalMaxConcurrency)
                .append("（独立 Semaphore，不占用线上 mkagent.agent.max-concurrency）\n");
        markdown.append("- 模型：")
                .append(agentModel.isBlank()
                        ? "未返回 model 元数据（测试桩或模型未提供）"
                        : agentModel)
                .append('\n');
        markdown.append("- 用例总数：").append(getTotalCount()).append('\n');
        markdown.append("- 判定方式：确定性检查");
        if (getJudgeUsedCount() > 0) {
            markdown.append(" + LLM-as-judge（")
                    .append(getJudgeUsedCount())
                    .append(" 条关键词无法判定的用例）");
        }
        markdown.append('\n');
        markdown.append("- 数据隔离：评测 Agent 未注入 AgentRunRecorder 与 AgentTaskRegistry，")
                .append("不写入 agent_run 表，也不进入线上取消链路。\n");
        markdown.append("- Token 口径：只统计 Agent 模型调用，不含 LLM judge；")
                .append("模型未返回 usage 时按 0 计入平均 Token。\n");
        markdown.append("- 耗时口径：P50 / P95 只统计 Agent run() 执行耗时，不含 LLM judge 调用。\n\n");

        if (budget != null) {
            appendBudgetSection(markdown);
        }

        markdown.append("## 总体指标\n\n");
        markdown.append("| 指标 | 数值 |\n");
        markdown.append("| --- | --- |\n");
        markdown.append("| 总通过率 | ")
                .append(formatPercent(getPassRate()))
                .append("（")
                .append(getPassedCount())
                .append("/")
                .append(getTotalCount())
                .append("） |\n");
        markdown.append("| 失败用例数 | ")
                .append(getTotalCount() - getPassedCount())
                .append(" |\n");
        markdown.append("| 平均步数 | ")
                .append(formatNumber(getAverageSteps()))
                .append(" |\n");
        markdown.append("| 平均 Token | ")
                .append(formatNumber(getAverageTokens()))
                .append(" |\n");
        markdown.append("| P50 耗时 | ")
                .append(getP50DurationMillis())
                .append(" ms |\n");
        markdown.append("| P95 耗时 | ")
                .append(getP95DurationMillis())
                .append(" ms |\n");
        markdown.append("| 使用 LLM judge 的用例数 | ")
                .append(getJudgeUsedCount())
                .append(" |\n\n");

        markdown.append("## 分类别通过率\n\n");
        markdown.append("| 类别 | 用例数 | 通过 | 通过率 |\n");
        markdown.append("| --- | ---: | ---: | ---: |\n");

        for (CategoryStat stat : getCategoryStats()) {
            markdown.append("| ")
                    .append(stat.label())
                    .append("（")
                    .append(stat.category())
                    .append("） | ")
                    .append(stat.total())
                    .append(" | ")
                    .append(stat.passed())
                    .append(" | ")
                    .append(formatPercent(stat.passRate()))
                    .append(" |\n");
        }
        markdown.append('\n');

        markdown.append("## 失败用例清单\n\n");

        List<EvalResult> failed = results.stream()
                .filter(result -> !result.passed())
                .toList();

        if (failed.isEmpty()) {
            markdown.append("无失败用例。\n");
            return markdown.toString();
        }

        for (EvalResult result : failed) {
            EvalCase evalCase = result.evalCase();
            EvalExecution execution = result.execution();

            markdown.append("### ")
                    .append(evalCase.getId())
                    .append(" · ")
                    .append(categoryLabel(evalCase.getCategory()))
                    .append("\n\n");

            markdown.append("- 终态：").append(execution.state()).append('\n');
            markdown.append("- 步数 / 工具调用：")
                    .append(execution.steps())
                    .append(" / ")
                    .append(execution.toolCallCount())
                    .append("（预算 ")
                    .append(evalCase.getMaxSteps())
                    .append(" / ")
                    .append(evalCase.getMaxToolCalls())
                    .append("）\n");
            markdown.append("- Token：").append(execution.totalTokens()).append('\n');
            markdown.append("- 耗时：").append(execution.durationMillis()).append(" ms\n");
            markdown.append("- 工具轨迹：")
                    .append(execution.toolTrace().isEmpty()
                            ? "（无）"
                            : String.join(" -> ", execution.toolTrace()))
                    .append('\n');
            markdown.append("- 失败原因：\n");
            for (String reason : result.failureReasons()) {
                markdown.append("  - ")
                        .append(reason.replace("\r", " ").replace("\n", " "))
                        .append('\n');
            }
            markdown.append("- 最终回答：\n\n");
            markdown.append(blockquote(execution.finalAnswer())).append("\n\n");
        }

        return markdown.toString();
    }

    /**
     * 渲染预算执行情况。
     *
     * 预算触发时，报告里的通过率只覆盖"已执行用例"，
     * 必须显式写清楚，避免被当成全量结论引用。
     */
    private void appendBudgetSection(StringBuilder markdown) {
        markdown.append("## 预算执行情况\n\n");
        markdown.append("| 项 | 上限 | 已用 |\n");
        markdown.append("| --- | ---: | ---: |\n");
        markdown.append("| Token（Agent + LLM judge） | ")
                .append(formatLimit(budget.maxTotalTokens(), "Token"))
                .append(" | ")
                .append(budget.consumedTokens())
                .append(" |\n");
        markdown.append("| 墙钟时长 | ")
                .append(formatLimit(
                        budget.maxDurationMillis() <= 0
                                ? 0
                                : budget.maxDurationMillis() / 1000,
                        "s"))
                .append(" | ")
                .append(budget.consumedDurationMillis() / 1000)
                .append(" s |\n");
        markdown.append("| 用例条数 | ")
                .append(budget.maxCases() == 0
                        ? "不限"
                        : String.valueOf(budget.maxCases()))
                .append(" | ")
                .append(budget.executedCases())
                .append(" |\n\n");

        if (budget.stopped()) {
            markdown.append("> **预算已触发**：")
                    .append(budget.stopReason().label())
                    .append("（")
                    .append(budget.stopDetail())
                    .append("）。本轮已执行 ")
                    .append(budget.executedCases())
                    .append(" 条，剩余 ")
                    .append(budget.skippedCases())
                    .append(" 条未执行；上述指标只覆盖已执行用例，不能当作全量评测结论。\n\n");
        } else {
            markdown.append("本轮未触发预算上限。\n\n");
        }
    }

    private String formatLimit(long value, String unit) {
        return value <= 0 ? "不限" : value + " " + unit;
    }

    /**
     * nearest-rank 百分位。
     */
    private long percentile(int percent) {
        if (results.isEmpty()) {
            return 0L;
        }

        List<Long> durations = results.stream()
                .map(result -> result.execution().durationMillis())
                .sorted()
                .toList();

        double rank = Math.ceil(percent / 100.0 * durations.size());
        int index = (int) Math.max(0, Math.min(rank - 1, durations.size() - 1));
        return durations.get(index);
    }

    private String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category);
    }

    private String formatPercent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100.0);
    }

    private String formatNumber(double number) {
        return String.format(Locale.ROOT, "%.1f", number);
    }

    private String blockquote(String text) {
        if (text == null || text.isBlank()) {
            return "> （空）";
        }

        String safe = text.strip();
        if (safe.length() > MAX_FAILURE_ANSWER_CHARS) {
            safe = safe.substring(0, MAX_FAILURE_ANSWER_CHARS)
                    + "\n[回答过长，已截断]";
        }

        return "> " + safe.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "\n> ");
    }

    /**
     * 分类别统计项。
     */
    public record CategoryStat(
            String category,
            String label,
            int total,
            int passed
    ) {
        public double passRate() {
            return total == 0 ? 0.0 : (double) passed / total;
        }
    }
}
