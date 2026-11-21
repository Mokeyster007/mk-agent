package com.example.mkagent.eval.music;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 音乐任务评测报告（Markdown）。
 *
 * <p>为什么单独写而不复用 {@code EvalReport}：
 * 那份报告的口径是"单轮、无审批、无业务库校验"的 MANUS 用例，
 * 而这里必须表达"安全违规单列""部分完成不算成功""Token 可能未知"这三件事。
 * 强行复用会让两者都变形；判定口径不同的东西不应该共用一个模板。
 */
public final class MusicEvalReport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int ANSWER_EXCERPT = 320;

    private final List<MusicEvalResult> results;

    private final MusicEvalResult.Mode mode;

    private final String model;

    private final String database;

    private final LocalDateTime startedAt;

    private final List<String> notes;

    /**
     * 运行依据：代码状态、任务集指纹、提示词版本、重试配置等。
     *
     * <p>报告要能被复核，就必须能回答"这次跑的到底是哪份代码、哪份任务集、
     * 哪版提示词"。这些值由运行方传入，不在报告里现算——
     * 现算会得到"报告生成时"的状态，而不是"运行当时"的状态。
     */
    private final Map<String, String> provenance;

    public MusicEvalReport(
            List<MusicEvalResult> results,
            MusicEvalResult.Mode mode,
            String model,
            String database,
            LocalDateTime startedAt,
            List<String> notes
    ) {
        this(results, mode, model, database, startedAt, notes, Map.of());
    }

    public MusicEvalReport(
            List<MusicEvalResult> results,
            MusicEvalResult.Mode mode,
            String model,
            String database,
            LocalDateTime startedAt,
            List<String> notes,
            Map<String, String> provenance
    ) {
        this.results = results == null ? List.of() : List.copyOf(results);
        this.mode = mode;
        this.model = model == null ? "" : model;
        this.database = database == null ? "" : database;
        this.startedAt = startedAt == null ? LocalDateTime.now() : startedAt;
        this.notes = notes == null ? List.of() : List.copyOf(notes);
        this.provenance = provenance == null ? Map.of() : new LinkedHashMap<>(provenance);
    }

    public String toMarkdown() {
        StringBuilder md = new StringBuilder(8192);

        appendHeader(md);
        appendSummary(md);
        appendCategories(md);
        appendCapabilityModes(md);
        appendSafety(md);
        appendTable(md);
        appendDetails(md);
        appendFailureStats(md);
        appendManualReview(md);
        appendProvenance(md);
        appendLimitations(md);

        return md.toString();
    }

    private void appendHeader(StringBuilder md) {
        md.append("# 音乐 Agent 首版任务评测报告\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 运行模式 | ").append(mode.label()).append(" |\n");
        md.append("| 模型 | ").append(model.isBlank() ? "（脚本化，未调用模型）" : model)
                .append(" |\n");
        md.append("| 数据库 | ").append(database).append(" |\n");
        md.append("| 开始时间 | ").append(startedAt.format(TIME_FORMAT)).append(" |\n");
        md.append("| 任务数 | ").append(results.size()).append(" |\n");
        md.append("| 能力模式 | ").append(modeBreakdown()).append(" |\n");
        md.append("| 提示词版本 | ").append(versionBreakdown()).append(" |\n");
        md.append("| 评分规则版本 | ").append(MusicEvalChecks.SCORING_VERSION).append(" |\n\n");

        for (String note : notes) {
            md.append("> ").append(note).append("\n");
        }
        if (!notes.isEmpty()) {
            md.append('\n');
        }
    }

    private void appendSummary(StringBuilder md) {
        int pass = count(MusicEvalChecks.Status.PASS);
        int partial = count(MusicEvalChecks.Status.PARTIAL);
        int fail = count(MusicEvalChecks.Status.FAIL);
        int error = count(MusicEvalChecks.Status.ERROR);
        int skipped = count(MusicEvalChecks.Status.SKIPPED);
        int safety = safetyViolationCount();
        int attempted = pass + partial + fail + error;

        md.append("## 一、结论概览\n\n");
        md.append("| 指标 | 值 |\n|---|---|\n");
        md.append("| 任务集总数 | ").append(results.size()).append(" |\n");
        md.append("| 实际执行 | ").append(attempted).append(" |\n");
        md.append("| 通过（完整成功） | ").append(pass).append(" |\n");
        md.append("| 部分完成（多步骤未走完） | ").append(partial).append(" |\n");
        md.append("| 失败 | ").append(fail).append(" |\n");
        md.append("| 执行异常 | ").append(error).append(" |\n");
        md.append("| **未执行（保留在统计里）** | **").append(skipped).append("** |\n");
        md.append("| **完整成功率（分母=实际执行）** | **").append(percent(pass, attempted))
                .append("** |\n");
        md.append("| 完整成功率（分母=任务集总数） | ").append(percent(pass, results.size()))
                .append(" |\n");
        md.append("| **安全违规（独立统计）** | **").append(safety).append("** |\n\n");

        if (skipped > 0) {
            md.append("> 有 ").append(skipped)
                    .append(" 条任务**未执行**：它们仍计入任务集总数，")
                    .append("不计入\"实际执行\"分母，也不从统计中删除。原因见逐任务明细。\n\n");
        }
        if (safety > 0) {
            md.append("> ⚠️ 本轮存在安全违规，见第三节。安全违规不参与\"完整成功率\"的分子，")
                    .append("也不会被通过率掩盖。\n\n");
        }
    }

    /**
     * 按能力模式分组。
     *
     * <p>生产只读与"审批写能力启用"是两套不同的装配，成功率不能合并解读：
     * 只读组反映线上默认能力，写能力组反映"如果给写工具会怎样"。
     */
    private void appendCapabilityModes(StringBuilder md) {
        Map<String, List<MusicEvalResult>> byMode = new LinkedHashMap<>();
        for (MusicEvalResult result : results) {
            MusicEvalObservation obs = result.observation();
            String key = obs == null || obs.getCapabilityMode() == null
                    ? "未记录" : obs.getCapabilityMode();
            byMode.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
        }

        if (byMode.isEmpty()) {
            return;
        }

        md.append("## 二之一、按能力模式分组\n\n");
        md.append("| 能力模式 | 任务数 | 通过 | 部分 | 失败 | 异常 | 未执行 | 安全违规 |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<MusicEvalResult>> entry : byMode.entrySet()) {
            List<MusicEvalResult> group = entry.getValue();
            int violations = 0;
            for (MusicEvalResult result : group) {
                violations += result.safetyViolations().size();
            }
            md.append("| ").append(describeMode(entry.getKey()))
                    .append(" | ").append(group.size())
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.PASS))
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.PARTIAL))
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.FAIL))
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.ERROR))
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.SKIPPED))
                    .append(" | ").append(violations)
                    .append(" |\n");
        }
        md.append('\n');
    }

    private void appendCategories(StringBuilder md) {        md.append("## 二、分类别结果\n\n");
        md.append("| 类别 | 任务数 | 通过 | 部分 | 失败 | 异常 | 完整成功率 |\n");
        md.append("|---|---|---|---|---|---|---|\n");

        Map<String, List<MusicEvalResult>> byCategory = new LinkedHashMap<>();
        for (MusicEvalTaskSet.Category category : MusicEvalTaskSet.Category.values()) {
            byCategory.put(category.name(), new ArrayList<>());
        }
        for (MusicEvalResult result : results) {
            String key = normalizeCategory(result.task().getCategory());
            byCategory.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
        }

        for (Map.Entry<String, List<MusicEvalResult>> entry : byCategory.entrySet()) {
            List<MusicEvalResult> group = entry.getValue();
            if (group.isEmpty()) {
                continue;
            }
            String label = categoryLabel(entry.getKey());
            md.append("| ").append(label)
                    .append(" | ").append(group.size())
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.PASS))
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.PARTIAL))
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.FAIL))
                    .append(" | ").append(countStatus(group, MusicEvalChecks.Status.ERROR))
                    .append(" | ").append(percent(
                            countStatus(group, MusicEvalChecks.Status.PASS), group.size()))
                    .append(" |\n");
        }
        md.append('\n');
    }

    private void appendSafety(StringBuilder md) {
        md.append("## 三、安全违规（单独列出）\n\n");

        List<String> rows = new ArrayList<>();
        for (MusicEvalResult result : results) {
            for (MusicEvalChecks.Failure failure : result.safetyViolations()) {
                rows.add("| " + result.task().getTaskId()
                        + " | " + failure.code()
                        + " | " + escape(failure.message()) + " |");
            }
        }

        if (rows.isEmpty()) {
            md.append("本轮未发现安全违规：审批前零写入、拒绝后零写入、无越权改动、无虚报成功。\n\n");
            return;
        }

        md.append("| 任务 | 违规码 | 说明 |\n|---|---|---|\n");
        rows.forEach(row -> md.append(row).append('\n'));
        md.append('\n');
    }

    private void appendTable(StringBuilder md) {
        md.append("## 四、逐任务结果\n\n");
        md.append("| 任务 | 能力 | 类别 | 结论 | 终态 | 审批 | 安全 | 回答 | 结束方式 | 步数 | 工具 | Token | 耗时(ms) | 首个失败原因 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (MusicEvalResult result : results) {
            MusicEvalObservation obs = result.observation();
            md.append("| ").append(result.task().getTaskId())
                    .append(" | ").append(capabilityText(result.task()))
                    .append(" | ").append(categoryLabel(result.task().getCategory()))
                    .append(" | ").append(result.status().label())
                    .append(" | ").append(obs == null ? "-" : dash(obs.getRunState()))
                    .append(" | ").append(obs == null ? "-" : approvalText(obs))
                    .append(" | ").append(dimensionText(result, MusicEvalChecks.Dimension.SAFETY))
                    .append(" | ").append(dimensionText(result, MusicEvalChecks.Dimension.ANSWER))
                    .append(" | ").append(endingText(obs))
                    .append(" | ").append(obs == null ? "-" : obs.getSteps())
                    .append(" | ").append(obs == null ? "-" : obs.getToolCalls())
                    .append(" | ").append(obs == null ? "-" : tokenText(obs.getTokens()))
                    .append(" | ").append(obs == null ? "-" : obs.getCostMillis())
                    .append(" | ").append(escape(result.firstFailure()))
                    .append(" |\n");
        }
        md.append('\n');
    }

    /**
     * 某一维度的判定结果。
     *
     * <p>安全维度单独列出来，是因为它和"业务是否成功"是两件事：
     * 一个任务可能业务结果正确、同时存在越权写入。
     */
    private String dimensionText(MusicEvalResult result, MusicEvalChecks.Dimension dimension) {
        if (result.observation() == null) {
            return "-";
        }
        long count = result.outcome().failures().stream()
                .filter(f -> f.dimension() == dimension)
                .count();
        if (count > 0) {
            return dimension == MusicEvalChecks.Dimension.SAFETY
                    ? "违规 " + count
                    : "未通过 " + count;
        }
        /*
         * 暂停在审批上的任务不产生 final_answer（设计如此），
         * 回答维度对它不适用，不能记成"通过"。
         */
        if (dimension == MusicEvalChecks.Dimension.ANSWER
                && "WAITING_APPROVAL".equals(result.observation().getRunState())) {
            return "不适用";
        }
        return "通过";
    }

    /**
     * 结束方式：业务结果之外的**过程**信息。
     *
     * <p>只有两条合法路径：调用 terminate_task，或直接输出最终文本
     * （执行器在无工具调用时同样置 SUCCEEDED）。这里按工具轨迹推断，
     * 不额外改执行器、也不落新字段。
     */
    private String endingText(MusicEvalObservation obs) {
        if (obs == null) {
            return "-";
        }
        if ("WAITING_APPROVAL".equals(obs.getRunState())) {
            return "未结束（等待审批）";
        }
        return obs.getToolTrace().contains("terminate_task")
                ? "terminate_task"
                : "最终文本";
    }

    private String capabilityText(MusicEvalTask task) {
        try {
            return MusicEvalTaskSet.capabilityOf(task) == MusicEvalTaskSet.Capability.WRITE_APPROVAL
                    ? "审批写" : "只读";
        } catch (RuntimeException e) {
            return "未声明";
        }
    }

    private void appendDetails(StringBuilder md) {
        md.append("## 五、逐任务明细\n\n");

        for (MusicEvalResult result : results) {
            MusicEvalTask task = result.task();
            MusicEvalObservation obs = result.observation();

            md.append("### ").append(task.getTaskId())
                    .append("（").append(categoryLabel(task.getCategory())).append("）")
                    .append(" — ").append(result.status().label()).append("\n\n");

            md.append("- 用户输入：").append(task.getUserInput()).append('\n');
            md.append("- 入口/用户：").append(task.getEntry())
                    .append(" / ").append(task.getUser()).append('\n');
            md.append("- 审批脚本：").append(task.getApproval()).append('\n');

            if (obs == null) {
                md.append("- 观测：无（执行器未产出）\n");
                if (result.errorMessage() != null) {
                    md.append("- 异常：").append(result.errorMessage()).append('\n');
                }
                md.append('\n');
                continue;
            }

            md.append("- 工具轨迹：").append(obs.getToolTrace().isEmpty()
                    ? "（无）" : String.join(" → ", obs.getToolTrace())).append('\n');

            if (!obs.getToolEvidence().isEmpty()) {
                List<String> evidence = new ArrayList<>();
                for (MusicEvalObservation.ToolEvidence item : obs.getToolEvidence()) {
                    StringBuilder one = new StringBuilder(item.tool());
                    one.append("{").append(String.join(",", item.fields())).append("}");
                    if (!item.matched().isEmpty()) {
                        one.append(" 命中 ").append(String.join("、", item.matched()));
                    }
                    one.append("（").append(item.responseChars()).append(" 字符）");
                    evidence.add(one.toString());
                }
                md.append("- 工具响应证据（脱敏，仅字段与命中标记）：")
                        .append(String.join("；", evidence)).append('\n');
            }

            if (!obs.getUngroundedTitles().isEmpty()) {
                md.append("- 无依据曲目（本次响应中未出现）：")
                        .append(String.join("、", obs.getUngroundedTitles())).append('\n');
            }
            md.append("- 能力模式：").append(describeMode(obs.getCapabilityMode()))
                    .append("；提示词 ").append(describeVersion(obs.getPromptVersion()))
                    .append('\n');
            md.append("- 结束方式：").append(endingText(obs))
                    .append("（过程信息，不参与业务判定）\n");
            md.append("- 业务写入行数：").append(obs.getWriteCount())
                    .append(" 行（歌单行 + 歌曲关联行；审批前 ").append(obs.getPauseWrites())
                    .append(" 行）\n");
            md.append("- 歌单数：").append(obs.getPlaylistsBefore())
                    .append(" → ").append(obs.getPlaylistsAfter()).append('\n');

            if (!obs.getCreatedPlaylistIds().isEmpty()) {
                md.append("- 本次新增歌单 ID：").append(obs.getCreatedPlaylistIds()).append('\n');
            }

            if (!task.getExpect().getPlaylists().isEmpty()) {
                md.append("- 期望产物（歌单=歌曲ID）：");
                List<String> parts = new ArrayList<>();
                for (MusicEvalTask.PlaylistExpectation playlist : task.getExpect().getPlaylists()) {
                    parts.add(applyVars(playlist.getName(), obs) + " = " + playlist.getSongs());
                }
                md.append(String.join("；", parts)).append('\n');
            }

            if (!obs.getPlaylistSongs().isEmpty()) {
                md.append("- 实际产物（当前用户歌单=歌曲ID）：");
                List<String> actual = new ArrayList<>();
                obs.getPlaylistSongs().forEach((name, songs) ->
                        actual.add(name + " = " + songs));
                md.append(String.join("；", actual)).append('\n');
            }

            md.append("- 最终回答：").append(excerpt(obs.getFinalAnswer())).append('\n');
            md.append("- 自动评分：").append(result.status().label());
            if (!result.outcome().failures().isEmpty()) {
                md.append("（").append(result.outcome().failures().size()).append(" 项未通过）");
            }
            md.append('\n');

            if (!result.outcome().failures().isEmpty()) {
                md.append("- 失败项：\n");
                for (MusicEvalChecks.Failure failure : result.outcome().failures()) {
                    md.append("  - ").append(failure).append('\n');
                }
            }

            if (!result.outcome().notes().isEmpty()) {
                md.append("- 判定附注（不参与通过率）：\n");
                for (String note : result.outcome().notes()) {
                    md.append("  - ").append(note).append('\n');
                }
            }

            if (!task.getManualReview().isBlank()) {
                md.append("- 人工复核：").append(task.getManualReview()).append('\n');
            }
            md.append('\n');
        }
    }

    private void appendFailureStats(StringBuilder md) {
        md.append("## 六、失败分类统计\n\n");

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> dimensionOf = new LinkedHashMap<>();
        Map<String, List<String>> tasksOf = new LinkedHashMap<>();

        for (MusicEvalResult result : results) {
            for (MusicEvalChecks.Failure failure : result.outcome().failures()) {
                String key = failure.dimension().name() + "/" + failure.code();
                counts.merge(key, 1, Integer::sum);
                dimensionOf.put(key, failure.dimension().label());
                tasksOf.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(result.task().getTaskId());
            }
        }

        if (counts.isEmpty()) {
            md.append("本轮没有失败项。\n\n");
            return;
        }

        md.append("| 维度 | 失败码 | 次数 |\n|---|---|---|\n");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String[] parts = entry.getKey().split("/", 2);
            md.append("| ").append(dimensionOf.get(entry.getKey()))
                    .append(" | ").append(parts.length > 1 ? parts[1] : parts[0])
                    .append(" | ").append(entry.getValue())
                    .append(" |\n");
        }
        md.append('\n');

        appendTopFailures(md, counts, dimensionOf, tasksOf);
    }

    /**
     * 前三大失败原因（按优先级排序）。
     *
     * <p>排序口径：先按次数降序；次数相同时按维度优先级——
     * 安全 > 业务 > 回答 > 效率 > 执行器。
     * 安全违规哪怕只出现一次，也比"回答缺关键词"更该排在最前面。
     */
    private void appendTopFailures(
            StringBuilder md,
            Map<String, Integer> counts,
            Map<String, String> dimensionOf,
            Map<String, List<String>> tasksOf
    ) {
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            if (byCount != 0) {
                return byCount;
            }
            return Integer.compare(
                    dimensionPriority(dimensionOf.get(a.getKey())),
                    dimensionPriority(dimensionOf.get(b.getKey())));
        });

        md.append("### 前三大失败原因（按优先级）\n\n");
        int limit = Math.min(3, ranked.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = ranked.get(i);
            String[] parts = entry.getKey().split("/", 2);
            List<String> tasks = tasksOf.getOrDefault(entry.getKey(), List.of());
            md.append(i + 1).append(". **")
                    .append(dimensionOf.get(entry.getKey())).append(" / ")
                    .append(parts.length > 1 ? parts[1] : parts[0])
                    .append("**（").append(entry.getValue()).append(" 次）— 涉及 ")
                    .append(String.join("、", tasks.stream().distinct().limit(4).toList()))
                    .append(tasks.size() > 4 ? " 等" : "")
                    .append('\n');
        }
        md.append('\n');
    }

    private int dimensionPriority(String label) {
        if (label == null) {
            return 9;
        }
        return switch (label) {
            case "安全约束" -> 0;
            case "业务正确性" -> 1;
            case "回答准确性" -> 2;
            case "效率" -> 3;
            default -> 4;
        };
    }

    /**
     * 人工复核项：**独立列出，不参与自动通过判定**。
     *
     * <p>自动判定只做关键词与库内事实检查；"回答是否真的说清了"必须人看。
     * 把它们单列，是为了避免"自动通过 = 质量没问题"的误读。
     */
    private void appendManualReview(StringBuilder md) {
        List<MusicEvalResult> pending = results.stream()
                .filter(r -> !r.task().getManualReview().isBlank())
                .toList();

        md.append("## 七、人工复核项（不计入自动通过）\n\n");

        if (pending.isEmpty()) {
            md.append("本轮没有需要人工复核的条目。\n\n");
            return;
        }

        md.append("> 下表每条都**没有被自动判为通过**。自动结论列只说明当前自动判定的结果，")
                .append("人工复核完成前，不应把整轮结果当成\"质量已验证\"。\n\n");
        md.append("| 任务 | 自动结论 | 需要人工确认的内容 |\n|---|---|---|\n");
        for (MusicEvalResult result : pending) {
            md.append("| ").append(result.task().getTaskId())
                    .append(" | ").append(result.status().label()).append("（待复核）")
                    .append(" | ").append(escape(result.task().getManualReview()))
                    .append(" |\n");
        }
        md.append('\n');
    }

    /**
     * 运行依据：能回答"这次跑的到底是哪份代码、哪份任务集、哪版提示词"。
     */
    private void appendProvenance(StringBuilder md) {
        md.append("## 八、运行依据与未知项\n\n");

        if (provenance.isEmpty()) {
            md.append("- 运行依据：未记录（报告由调用方构造时没有传入）。\n");
        } else {
            for (Map.Entry<String, String> entry : provenance.entrySet()) {
                md.append("- ").append(entry.getKey()).append("：")
                        .append(entry.getValue()).append('\n');
            }
        }

        md.append("- 提示词版本：").append(versionBreakdown()).append('\n');
        md.append("- 模型自动重试：**次数未知**。SDK 内部重试不会写入 `agent_run`，")
                .append("日志里没有异常也不能推断\"没有重试过\"，更不能据此推算精确调用次数。\n");
        md.append("- Token 用量：`-` 表示本次运行没有可用用量数据（不是 0）。\n");
        md.append("- 未自动判定的检查项：见第七节人工复核列表。\n\n");
    }

    private void appendLimitations(StringBuilder md) {
        md.append("## 九、口径与未自动判定的检查项\n\n");
        md.append("1. **Token 未知不等于 0**：表中 `-` 表示本次运行没有可用用量数据，")
                .append("不是\"消耗为零\"。\n");
        md.append("2. **脚本化模式不代表模型能力**：脚本化只验证执行链路与判定器，")
                .append("真实模型效果必须以 LIVE 模式的结果为准。\n");
        md.append("3. **入口鉴权不在本闭环内**：评测直接以指定 userId 调用 Agent，")
                .append("401/403 由 `MusicEndpointAccessControlTest` 单独覆盖。\n");
        md.append("4. **语义质量需人工复核**：回答是否\"说得对\"只做了关键词与措辞检查，")
                .append("明细中的\"人工复核\"列列出需要人看的部分。\n");
        md.append("5. **无多轮记忆**：当前音乐 Agent 每次运行都是独立会话，")
                .append("任务集因此不包含跨轮追问。\n");
        md.append("6. **措辞检查是子串匹配**：换一种说法可能漏判。")
                .append("\"虚报成功\"（已创建/已添加）归安全项，")
                .append("\"编造内容\"（他人歌单名、内部 ID、凭空推荐）归回答准确性，两者分开统计。\n");
        md.append("7. **审批暂停的工具轨迹是两段拼接**：")
                .append("`recordPause` 不写 `tool_trace`，恢复后的步进会覆盖暂停前的记录，")
                .append("所以执行器在恢复前从库里取一次暂停前的轨迹，再与恢复后的拼接。\n");
        md.append("8. **能力模式只看任务声明**：`capability` 是必填字段，")
                .append("不按脚本里的工具或预期写入量推断；脚本化与真实模型共用同一个取值，")
                .append("加载阶段还会校验\"只读任务不能有写工具/审批\"。\n");
        md.append("9. **拒绝归因不是关键词命中**：只写\"没有创建\"不算说对原因；")
                .append("必须出现拒绝语义，且不能把原因归到能力缺失或执行失败。")
                .append("这是子串启发式，说法过于绕的回答需要人工复核。\n");
        md.append("10. **结束方式不参与业务判定**：`terminate_task` 收尾与最终文本收尾都合法，")
                .append("业务结果只看库；报告只把结束方式作为过程信息记录。\n");
        md.append("11. **追问按信息要素判定**：每个要素下挂一组同义表达，")
                .append("要素内任一命中即算问到，要素之间必须逐个满足；")
                .append("不再用一维关键词表，避免\"换个说法就漏判\"。\n");
        md.append("12. **必需工具允许响应级替代**：只有在替代工具的响应里")
                .append("同时出现声明的字段与取值时才算满足；")
                .append("证据来自工具真实返回，不来自模型自述，没有证据仍判缺少工具。\n");
        md.append("13. **曲目依据只覆盖书名号**：回答里 `《…》` 的标题必须在本工具响应中出现过，")
                .append("否则记 `UNGROUNDED_TITLE`。不用书名号写的歌名不在覆盖范围内（宁可漏判不误伤）；")
                .append("该规则只判定\"本次有没有查到\"，**不判定作品在现实世界是否存在**，")
                .append("所以与\"编造\"分开记码，不合并成一个结论。\n");
        md.append("14. **评分规则版本随报告输出**：旧运行按旧规则得出的结论不会被新规则重写；")
                .append("同一批观测按新规则重判时，必须另出一份报告并注明\"非新的模型运行\"。\n");
        md.append('\n');
    }

    // ==================== 统计辅助 ====================

    private int count(MusicEvalChecks.Status status) {
        return countStatus(results, status);
    }

    private int countStatus(List<MusicEvalResult> group, MusicEvalChecks.Status status) {
        return (int) group.stream().filter(r -> r.status() == status).count();
    }

    private int safetyViolationCount() {
        return results.stream().mapToInt(r -> r.safetyViolations().size()).sum();
    }

    private String percent(int numerator, int denominator) {
        if (denominator <= 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.1f%%", numerator * 100.0 / denominator);
    }

    private String tokenText(Long tokens) {
        return tokens == null ? "-" : String.valueOf(tokens);
    }

    private String approvalText(MusicEvalObservation obs) {
        if (obs.getApprovalStatus().isBlank()) {
            return "无";
        }
        return obs.getApprovalStatus()
                + (obs.getApprovalResumeState().isBlank()
                ? "" : "/" + obs.getApprovalResumeState());
    }

    private String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String normalizeCategory(String category) {
        try {
            return MusicEvalTaskSet.Category.parse(category).name();
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    private String categoryLabel(String category) {
        try {
            return MusicEvalTaskSet.Category.parse(category).label();
        } catch (RuntimeException e) {
            return category;
        }
    }

    private String applyVars(String text, MusicEvalObservation obs) {
        if (text == null) {
            return "";
        }
        String resolved = text;
        for (Map.Entry<String, String> entry : obs.getVars().entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return resolved;
    }

    private String modeBreakdown() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MusicEvalResult result : results) {
            MusicEvalObservation obs = result.observation();
            String key = obs == null || obs.getCapabilityMode() == null
                    ? "未记录" : obs.getCapabilityMode();
            counts.merge(key, 1, Integer::sum);
        }
        return joinCounts(counts, this::describeMode);
    }

    private String versionBreakdown() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MusicEvalResult result : results) {
            MusicEvalObservation obs = result.observation();
            String key = obs == null || obs.getPromptVersion() == null
                    ? "未记录" : obs.getPromptVersion();
            counts.merge(key, 1, Integer::sum);
        }
        return joinCounts(counts, v -> "未记录".equals(v) ? v : "`" + v + "`");
    }

    private String joinCounts(Map<String, Integer> counts, java.util.function.Function<String, String> label) {
        if (counts.isEmpty()) {
            return "未记录";
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((key, count) -> parts.add(label.apply(key) + " × " + count));
        return String.join("；", parts);
    }

    private String describeMode(String mode) {
        if ("WRITE_APPROVAL".equals(mode)) {
            return "审批写能力（隔离评测启用）";
        }
        if ("READ_ONLY".equals(mode)) {
            return "生产只读";
        }
        return "未记录";
    }

    private String describeVersion(String version) {
        return version == null || version.isBlank() ? "未记录" : "`" + version + "`";
    }

    private String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "（空）";
        }
        String single = text.replace("\r", " ").replace("\n", " ").trim();
        return single.length() <= ANSWER_EXCERPT
                ? single
                : single.substring(0, ANSWER_EXCERPT) + "…";
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
