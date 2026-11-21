package com.example.mkagent.eval.music;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.approval.ApprovalClaimService;
import com.example.mkagent.approval.ApprovalDecisionService;
import com.example.mkagent.approval.ApprovalExecutionCompletionService;
import com.example.mkagent.approval.ApprovalPauseGateway;
import com.example.mkagent.approval.ApprovalProperties;
import com.example.mkagent.approval.ApprovalResumeService;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.approval.RunHandoffLocks;
import com.example.mkagent.config.AgentToolProvider;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音乐任务评测执行器：一条任务从"准备数据"到"采集结果"的完整闭环。
 *
 * <p>执行链路（全部是真实组件，没有替身）：
 * <pre>
 * 准备隔离数据
 *   → 真实 MusicAgent（真实工具、真实审批策略、真实事务）
 *   → 按任务脚本驱动审批（批准 / 拒绝 / 保持等待）
 *   → 等待终态
 *   → 从数据库采集业务结果与运行记录
 *   → 判定
 *   → 清理本任务数据
 * </pre>
 *
 * <p>刻意不做的三件事：
 * 1. 不绕过审批：写工具仍然经过 {@code ApprovalPauseGateway}，
 *    由脚本调用 {@code ApprovalDecisionService} 决定，模型无权自行批准；
 * 2. 不改生产白名单：写工具只在这个执行器构造的 Agent 实例里出现；
 * 3. 不静默重试：一次任务只跑一次，失败就是失败。
 */
public class MusicEvalHarness {

    private static final Logger log = LoggerFactory.getLogger(MusicEvalHarness.class);

    /**
     * 判定为"已结束"的状态。
     */
    private static final Set<String> TERMINAL_STATES = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "MAX_STEPS_REACHED"
    );

    private static final Pattern PLAYLIST_ID_PATTERN =
            Pattern.compile("^\\{\\{playlistId:(.+)\\}\\}$");

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final AgentToolProvider toolProvider;

    private final ToolCallback[] writeCallbacks;

    private final ApprovalToolPolicy approvalToolPolicy;

    private final ApprovalPauseGateway approvalPauseGateway;

    private final ApprovalSummaryBuilder approvalSummaryBuilder;

    private final RunHandoffLocks runHandoffLocks;

    private final AgentTaskRegistry taskRegistry;

    private final AgentRunRecorder runRecorder;

    private final AgentConcurrencyGuard concurrencyGuard;

    private final ToolEventMessageProvider toolEventMessageProvider;

    private final ApprovalClaimService claimService;

    private final ApprovalExecutionCompletionService approvalCompletionService;

    private final ApprovalDecisionService decisionService;

    private final ApprovalProperties approvalProperties;

    private final JdbcTemplate jdbcTemplate;

    private final MusicEvalChecks checks = new MusicEvalChecks();

    private int taskTimeoutSeconds = 60;

    public MusicEvalHarness(
            AgentToolProvider toolProvider,
            ToolCallback[] writeCallbacks,
            ApprovalToolPolicy approvalToolPolicy,
            ApprovalPauseGateway approvalPauseGateway,
            ApprovalSummaryBuilder approvalSummaryBuilder,
            RunHandoffLocks runHandoffLocks,
            AgentTaskRegistry taskRegistry,
            AgentRunRecorder runRecorder,
            AgentConcurrencyGuard concurrencyGuard,
            ToolEventMessageProvider toolEventMessageProvider,
            ApprovalClaimService claimService,
            ApprovalExecutionCompletionService approvalCompletionService,
            ApprovalDecisionService decisionService,
            ApprovalProperties approvalProperties,
            JdbcTemplate jdbcTemplate
    ) {
        this.toolProvider = toolProvider;
        this.writeCallbacks = writeCallbacks == null ? new ToolCallback[0] : writeCallbacks.clone();
        this.approvalToolPolicy = approvalToolPolicy;
        this.approvalPauseGateway = approvalPauseGateway;
        this.approvalSummaryBuilder = approvalSummaryBuilder;
        this.runHandoffLocks = runHandoffLocks;
        this.taskRegistry = taskRegistry;
        this.runRecorder = runRecorder;
        this.concurrencyGuard = concurrencyGuard;
        this.toolEventMessageProvider = toolEventMessageProvider;
        this.claimService = claimService;
        this.approvalCompletionService = approvalCompletionService;
        this.decisionService = decisionService;
        this.approvalProperties = approvalProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public MusicEvalHarness setTaskTimeoutSeconds(int seconds) {
        this.taskTimeoutSeconds = Math.max(1, seconds);
        return this;
    }

    /**
     * 执行一条任务，带单任务超时。
     *
     * <p>超时用独立线程 + {@code Future.get}，而不是只依赖 Agent 自身的 timeout：
     * Agent 的 timeout 管的是循环预算，管不住卡在数据库或工具里的调用。
     */
    public MusicEvalResult run(
            MusicEvalTask task,
            MusicEvalResult.Mode mode,
            ChatModel liveModel
    ) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "music-eval-" + task.getTaskId());
            thread.setDaemon(true);
            return thread;
        });

        Future<MusicEvalResult> future = executor.submit(
                () -> runInline(task, mode, liveModel));

        try {
            return future.get(taskTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[music-eval] 任务超时：taskId={}, timeout={}s",
                    task.getTaskId(), taskTimeoutSeconds);
            return MusicEvalResult.error(task, mode, null,
                    "单任务超时（" + taskTimeoutSeconds + "s）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("[music-eval] 任务执行异常：taskId={}", task.getTaskId(), cause);
            return MusicEvalResult.error(task, mode, null,
                    "执行异常：" + cause.getClass().getSimpleName()
                            + (cause.getMessage() == null ? "" : "：" + cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MusicEvalResult.error(task, mode, null, "评测被中断");
        } finally {
            executor.shutdownNow();
        }
    }

    // ==================== 单任务主体 ====================

    private MusicEvalResult runInline(
            MusicEvalTask task,
            MusicEvalResult.Mode mode,
            ChatModel liveModel
    ) {
        String token = UUID.randomUUID().toString().substring(0, 6);
        String name = "评测-" + task.getTaskId() + "-" + token;
        String fixtureName = "评测-" + task.getTaskId() + "-fx-" + token;

        String userId = resolveUser(task.getUser());

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("name", name);
        vars.put("fixture", fixtureName);

        String runId = UUID.randomUUID().toString();
        AgentRunContext ctx = new AgentRunContext(java.util.UUID.fromString(runId));

        List<Long> fixtureIds = new ArrayList<>();
        long maxIdBefore = 0L;

        try {
            cleanupByTaskPrefix(task.getTaskId(), userId);

            fixtureIds.addAll(createFixtures(task, fixtureName, userId));
            maxIdBefore = maxPlaylistId(userId);

            Snapshot before = snapshot(userId);
            String otherBefore = otherUsersSignature(userId);

            /*
             * 先解析占位符再构造模型：脚本里的歌单 ID 依赖数据库当前值，
             * 不能在 JSON 里写死。
             */
            List<MusicEvalTask.ScriptTurn> resolvedScript =
                    resolveScript(task, vars, userId, fixtureIds.isEmpty() ? null : fixtureIds.get(0));

            ChatModel model = mode == MusicEvalResult.Mode.SCRIPTED
                    ? new ScriptedMusicModel(resolvedScript)
                    : liveModel;

            if (model == null) {
                return MusicEvalResult.error(task, mode, null,
                        "真实模型模式下没有可用的 ChatModel");
            }

            /*
             * 能力模式只来自任务声明（见 MusicEvalTaskSet.capabilityOf）：
             * 脚本化与真实模型走同一条取值路径，不按脚本工具或预期写入量推断。
             * 给写工具 → 用"写能力 + 审批"提示词；不给 → 用生产只读提示词。
             */
            MusicEvalTaskSet.Capability capability = MusicEvalTaskSet.capabilityOf(task);
            boolean writeEnabled = capability == MusicEvalTaskSet.Capability.WRITE_APPROVAL;

            /*
             * 工具响应采集器：只在评测装配里包一层，生产链路不变。
             * 它记录的是"工具真实返回了什么"，用来判断必需工具的替代路径
             * 是否成立（q02），以及回答里的曲目有没有查询依据（c03）。
             */
            ToolResponseRecorder recorder = new ToolResponseRecorder(watchMarkers(task));

            MusicAgent agent = buildAgent(task, model, userId, writeEnabled, recorder);

            String input = resolveText(task.getUserInput(), vars);

            UserContextHolder.set(userId);
            try {
                agent.run(input, ctx);
            } finally {
                UserContextHolder.clear();
            }

            boolean paused = "WAITING_APPROVAL".equals(String.valueOf(ctx.getState()));

            int pauseWrites = 0;
            String approvalId = "";
            String pauseToolTrace = "";

            if (paused) {
                approvalId = findApprovalId(runId);
                Snapshot atPause = snapshot(userId);
                pauseWrites = diffWrites(before, atPause);
                /*
                 * recordPause 不写 tool_trace，恢复后的步进会把它覆盖掉。
                 * 所以"暂停前调了哪些工具"只能在恢复前从库里取一次，
                 * 之后与恢复后的轨迹拼起来——两段都是数据库里的观测值。
                 */
                pauseToolTrace = readToolTrace(runId);

                driveApproval(task, agent, approvalId, userId);
            }

            awaitTerminal(runId, task.getApproval(), paused);

            Snapshot after = snapshot(userId);

            MusicEvalObservation obs = observe(
                    task, runId, approvalId, before, after, pauseWrites, paused,
                    userId, otherBefore, pauseToolTrace);

            obs.setCapabilityMode(writeEnabled ? "WRITE_APPROVAL" : "READ_ONLY")
                    .setPromptVersion(promptVersionOf(task, writeEnabled))
                    .setVars(vars)
                    .setToolEvidence(recorder.evidence())
                    .setUngroundedTitles(recorder.ungroundedTitles(obs.getFinalAnswer()));

            if (mode == MusicEvalResult.Mode.SCRIPTED
                    && model instanceof ScriptedMusicModel scripted
                    && scripted.exhausted()) {
                return MusicEvalResult.error(task, mode, obs,
                        "脚本回合用尽：任务实际需要的模型回合多于脚本定义");
            }

            MusicEvalChecks.Outcome outcome = checks.check(task, obs, vars);
            return new MusicEvalResult(task, mode, obs, outcome, null);

        } catch (RuntimeException e) {
            log.warn("[music-eval] 任务失败：taskId={}", task.getTaskId(), e);
            return MusicEvalResult.error(task, mode, null,
                    e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        } finally {
            UserContextHolder.clear();
            taskRegistry.remove(runId, ctx.getExecutionEpoch());
            cleanupTask(userId, task.getTaskId(), runId, maxIdBefore);
        }
    }

    /**
     * 按任务脚本驱动审批。
     *
     * <p>批准/拒绝走真实服务；HOLD 什么都不做——这正是"保持等待"的含义。
     * 审批失败不吞：抛出去由上层记为执行异常，避免"没批成却按通过算"。
     */
    private void driveApproval(
            MusicEvalTask task,
            MusicAgent agent,
            String approvalId,
            String userId
    ) {
        if (approvalId == null || approvalId.isBlank()) {
            return;
        }

        switch (task.getApproval()) {
            case "APPROVE" -> {
                decisionService.decide(approvalId, userId, true);
                resume(agent, approvalId, userId);
            }
            case "REJECT" -> {
                decisionService.decide(approvalId, userId, false);
                resume(agent, approvalId, userId);
            }
            case "HOLD" -> {
                // 保持等待：不做任何决定，任务应停在 WAITING_APPROVAL
            }
            default -> {
                // NONE 却暂停了：由判定层记 UNEXPECTED_PAUSE
            }
        }
    }

    private void resume(MusicAgent agent, String approvalId, String userId) {
        ApprovalResumeService resumeService = new ApprovalResumeService(
                claimService, approvalProperties, List.of(agent));
        resumeService.resume(approvalId, userId);
    }

    // ==================== Agent 装配 ====================

    private MusicAgent buildAgent(
            MusicEvalTask task,
            ChatModel model,
            String userId,
            boolean writeEnabled,
            ToolResponseRecorder recorder
    ) {
        AgentType agentType = AgentType.valueOf(task.getEntry());

        ToolCallback[] base = toolProvider.getTools(agentType);
        /*
         * 只给读工具套采集器：写工具必须保持 IdempotentToolCallback 的原始类型，
         * 否则执行器的幂等计划会漏掉它，写操作直接被 fail-closed 拒绝。
         * 详见 ToolResponseRecorder#wrapReadOnly 的说明。
         */
        ToolCallback[] recorded = recorder == null ? base : recorder.wrapReadOnly(base);
        ToolCallback[] tools = writeEnabled ? concat(recorded, writeCallbacks) : recorded;

        String prompt;
        if ("MUSIC_ADMIN".equals(task.getEntry())) {
            prompt = MusicAgent.ADMIN_SYSTEM_PROMPT;
        } else if (writeEnabled) {
            prompt = MusicAgent.USER_WRITE_SYSTEM_PROMPT;
        } else {
            prompt = MusicAgent.USER_SYSTEM_PROMPT;
        }

        MusicAgent agent = new MusicAgent(
                model,
                tools,
                Runnable::run,
                toolEventMessageProvider,
                agentType,
                "music-eval-" + task.getTaskId(),
                prompt
        );

        agent.setTaskRegistry(taskRegistry);
        agent.setRunRecorder(runRecorder);
        agent.setConcurrencyGuard(concurrencyGuard);

        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(approvalPauseGateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);
        agent.setApprovalClaimService(claimService);
        agent.setApprovalCompletionService(approvalCompletionService);

        agent.setMaxSteps(task.getExpect().getMaxSteps());
        agent.setMaxToolCalls(task.getExpect().getMaxToolCalls());
        agent.setTimeout(Duration.ofSeconds(Math.max(5, taskTimeoutSeconds - 5)));

        return agent;
    }

    /**
     * 本任务关心的证据标记：所有替代路径的证据要求取并集。
     *
     * <p>采集器只记这些标记的命中情况，不记其它内容——
     * 证据是"任务声明要看的东西"，不是"把响应抄一遍"。
     */
    private List<String> watchMarkers(MusicEvalTask task) {
        Set<String> markers = new LinkedHashSet<>();
        for (MusicEvalTask.ToolFallback fallback : task.getExpect().getToolFallbacks()) {
            markers.addAll(fallback.getResponseMustContain());
        }
        return List.copyOf(markers);
    }

    /**
     * 该任务使用的能力模式对应的提示词版本。
     */
    private String promptVersionOf(MusicEvalTask task, boolean writeEnabled) {
        if ("MUSIC_ADMIN".equals(task.getEntry())) {
            return MusicAgent.ADMIN_SYSTEM_PROMPT_VERSION;
        }
        return writeEnabled
                ? MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION
                : MusicAgent.USER_SYSTEM_PROMPT_VERSION;
    }

    private ToolCallback[] concat(ToolCallback[] left, ToolCallback[] right) {
        ToolCallback[] merged = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, merged, left.length, right.length);
        return merged;
    }

    // ==================== 数据准备与清理 ====================

    private List<Long> createFixtures(MusicEvalTask task, String fixtureName, String userId) {
        List<Long> ids = new ArrayList<>();

        for (MusicEvalTask.PlaylistFixture fixture : task.getInitialData()) {
            String name = "{{fixture}}".equals(fixture.getName()) ? fixtureName : fixture.getName();

            jdbcTemplate.update(
                    "INSERT INTO music_playlist (user_id, name, description, is_public) "
                            + "VALUES (?, ?, ?, ?)",
                    userId, name, "评测隔离数据", fixture.isPublic());

            Long playlistId = jdbcTemplate.queryForObject(
                    "SELECT id FROM music_playlist WHERE user_id = ? AND name = ?",
                    Long.class, userId, name);

            int position = 1;
            for (Long songId : fixture.getSongs()) {
                jdbcTemplate.update(
                        "INSERT INTO music_playlist_song (playlist_id, song_id, position) "
                                + "VALUES (?, ?, ?)",
                        playlistId, songId, position++);
            }
            ids.add(playlistId);
        }

        return ids;
    }

    /**
     * 清理一个任务的隔离数据。
     *
     * <p>两道保险：
     * 1. 按名字前缀删（正常路径）；
     * 2. 按 id &gt; maxIdBefore 删（真实模型可能不按要求的名字建歌单）。
     * 都限定在**本任务用户**，不会碰到别人的数据。
     */
    private void cleanupTask(String userId, String taskId, String runId, long maxIdBefore) {
        String prefix = "评测-" + taskId + "-%";

        try {
            jdbcTemplate.update(
                    "DELETE FROM music_playlist_song WHERE playlist_id IN "
                            + "(SELECT id FROM music_playlist WHERE user_id = ? "
                            + "AND (name LIKE ? OR id > ?))",
                    userId, prefix, maxIdBefore);

            jdbcTemplate.update(
                    "DELETE FROM music_playlist WHERE user_id = ? AND (name LIKE ? OR id > ?)",
                    userId, prefix, maxIdBefore);

            jdbcTemplate.update("DELETE FROM music_tool_idempotency WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        } catch (RuntimeException e) {
            log.warn("[music-eval] 清理失败（可能影响后续用例）：taskId={}, runId={}",
                    taskId, runId, e);
        }
    }

    private void cleanupByTaskPrefix(String taskId, String userId) {
        String prefix = "评测-" + taskId + "-%";
        jdbcTemplate.update(
                "DELETE FROM music_playlist_song WHERE playlist_id IN "
                        + "(SELECT id FROM music_playlist WHERE user_id = ? AND name LIKE ?)",
                userId, prefix);
        jdbcTemplate.update(
                "DELETE FROM music_playlist WHERE user_id = ? AND name LIKE ?",
                userId, prefix);
    }

    private long maxPlaylistId(String userId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM music_playlist WHERE user_id = ?",
                Long.class, userId);
        return value == null ? 0L : value;
    }

    // ==================== 观测 ====================

    private Snapshot snapshot(String userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM music_playlist WHERE user_id = ? ORDER BY id",
                Long.class, userId);

        int songLinks = 0;
        Map<String, List<Long>> byName = new LinkedHashMap<>();

        for (Long id : ids) {
            List<Long> songs = jdbcTemplate.queryForList(
                    "SELECT song_id FROM music_playlist_song WHERE playlist_id = ? "
                            + "ORDER BY position",
                    Long.class, id);
            songLinks += songs.size();

            String name = jdbcTemplate.queryForObject(
                    "SELECT name FROM music_playlist WHERE id = ?", String.class, id);
            byName.put(name, songs);
        }

        return new Snapshot(new LinkedHashSet<>(ids), songLinks, byName);
    }

    /**
     * 其他用户数据的指纹：id + 名字 + 歌曲数。
     */
    private String otherUsersSignature(String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id, p.name, COUNT(s.song_id) AS songs "
                        + "FROM music_playlist p "
                        + "LEFT JOIN music_playlist_song s ON s.playlist_id = p.id "
                        + "WHERE p.user_id <> ? "
                        + "GROUP BY p.id, p.name ORDER BY p.id",
                userId);

        StringBuilder signature = new StringBuilder();
        for (Map<String, Object> row : rows) {
            signature.append(row.get("id")).append(':')
                    .append(row.get("name")).append(':')
                    .append(row.get("songs")).append(';');
        }
        return signature.toString();
    }

    private MusicEvalObservation observe(
            MusicEvalTask task,
            String runId,
            String approvalId,
            Snapshot before,
            Snapshot after,
            int pauseWrites,
            boolean paused,
            String userId,
            String otherBefore,
            String pauseToolTrace
    ) {
        MusicEvalObservation obs = new MusicEvalObservation()
                .setRunId(runId)
                .setApprovalId(approvalId)
                .setPlaylistsBefore(before.ids().size())
                .setPlaylistsAfter(after.ids().size())
                .setPlaylistSongs(after.byName())
                .setPauseWrites(pauseWrites)
                .setPaused(paused)
                .setUnexpectedPause(paused && "NONE".equals(task.getApproval()));

        List<Long> created = new ArrayList<>(after.ids());
        created.removeAll(before.ids());
        obs.setCreatedPlaylistIds(created);
        obs.setWriteCount((after.ids().size() - before.ids().size())
                + (after.songLinks() - before.songLinks()));

        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                "SELECT state, tool_trace, final_answer, model, total_tokens, "
                        + "tool_call_count, current_step, total_cost_millis, error_code "
                        + "FROM agent_run WHERE run_id = ?", runId);

        if (!runs.isEmpty()) {
            Map<String, Object> row = runs.get(0);
            obs.setRunState(text(row.get("state")))
                    .setToolTrace(mergeTrace(pauseToolTrace, text(row.get("tool_trace"))))
                    .setFinalAnswer(text(row.get("final_answer")))
                    .setModel(text(row.get("model")))
                    .setSteps(number(row.get("current_step")))
                    .setToolCalls(number(row.get("tool_call_count")))
                    .setCostMillis(number(row.get("total_cost_millis")))
                    .setErrorCode(text(row.get("error_code")));

            Object tokens = row.get("total_tokens");
            if (tokens instanceof Number number && number.longValue() > 0) {
                obs.setTokens(number.longValue());
            } else {
                /*
                 * 0 或 null 一律记"未知"：脚本化模型不产生真实用量，
                 * 把它写成 0 会让报告看起来像"零成本"。
                 */
                obs.setTokens(null);
            }
        }

        List<Map<String, Object>> approvals = jdbcTemplate.queryForList(
                "SELECT status, resume_state FROM agent_approval WHERE run_id = ?", runId);
        if (!approvals.isEmpty()) {
            obs.setApprovalStatus(text(approvals.get(0).get("status")))
                    .setApprovalResumeState(text(approvals.get(0).get("resume_state")));
        }

        obs.setOtherUserChanged(!otherUsersSignature(userId).equals(otherBefore));

        return obs;
    }

    private String findApprovalId(String runId) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT approval_id FROM agent_approval WHERE run_id = ?", String.class, runId);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    private String readToolTrace(String runId) {
        List<String> traces = jdbcTemplate.queryForList(
                "SELECT tool_trace FROM agent_run WHERE run_id = ?", String.class, runId);
        return traces.isEmpty() ? "" : text(traces.get(0));
    }

    /**
     * 拼接暂停前与恢复后的工具轨迹。
     *
     * <p>只折叠"相邻重复"：一次暂停+恢复可能把同一个写入工具记两遍，
     * 但真正调用了两次的工具仍然会保留两个位置。
     */
    private List<String> mergeTrace(String pauseTrace, String finalTrace) {
        List<String> merged = new ArrayList<>();
        for (String tool : splitTrace(pauseTrace)) {
            merged.add(tool);
        }
        for (String tool : splitTrace(finalTrace)) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1).equals(tool)) {
                continue;
            }
            merged.add(tool);
        }
        return merged;
    }

    private void awaitTerminal(String runId, String approval, boolean paused) {
        /*
         * 保持等待（HOLD）与"未预期的暂停"都不会再推进状态，
         * 直接返回，避免把正常等待误判成超时。
         */
        if (paused && ("HOLD".equals(approval) || "NONE".equals(approval))) {
            return;
        }

        long deadline = System.currentTimeMillis() + taskTimeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            List<String> states = jdbcTemplate.queryForList(
                    "SELECT state FROM agent_run WHERE run_id = ?", String.class, runId);

            if (states.isEmpty()) {
                sleep(25);
                continue;
            }

            String state = states.get(0);

            if (TERMINAL_STATES.contains(state)) {
                return;
            }
            if ("HOLD".equals(approval) && "WAITING_APPROVAL".equals(state)) {
                return;
            }
            sleep(25);
        }

        throw new IllegalStateException("等待任务终态超时：runId=" + runId);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("评测线程被中断", e);
        }
    }

    // ==================== 占位符与参数 ====================

    private List<MusicEvalTask.ScriptTurn> resolveScript(
            MusicEvalTask task,
            Map<String, String> vars,
            String userId,
            Long fixtureId
    ) {
        List<MusicEvalTask.ScriptTurn> resolved = new ArrayList<>();

        for (MusicEvalTask.ScriptTurn turn : task.getScript()) {
            MusicEvalTask.ScriptTurn copy = new MusicEvalTask.ScriptTurn();
            copy.setTool(turn.getTool());
            copy.setAnswer(resolveText(turn.getAnswer(), vars));
            copy.setArgs(resolveArgs(turn.getArgs(), vars, userId, fixtureId));
            resolved.add(copy);
        }
        return resolved;
    }

    private Map<String, Object> resolveArgs(
            Map<String, Object> args,
            Map<String, String> vars,
            String userId,
            Long fixtureId
    ) {
        Map<String, Object> resolved = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : args.entrySet()) {
            resolved.put(entry.getKey(),
                    resolveValue(entry.getValue(), vars, userId, fixtureId));
        }
        return resolved;
    }

    private Object resolveValue(
            Object value,
            Map<String, String> vars,
            String userId,
            Long fixtureId
    ) {
        if (value instanceof String text) {
            Matcher matcher = PLAYLIST_ID_PATTERN.matcher(text);
            if (matcher.matches()) {
                return requirePlaylistId(userId, matcher.group(1));
            }
            if ("{{fixtureId}}".equals(text)) {
                if (fixtureId == null) {
                    throw new IllegalStateException("任务引用了 {{fixtureId}} 但没有准备隔离歌单");
                }
                return fixtureId;
            }
            return resolveText(text, vars);
        }

        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            for (Object item : list) {
                items.add(item instanceof Number number
                        ? (Object) number.longValue()
                        : resolveValue(item, vars, userId, fixtureId));
            }
            return items;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        return value;
    }

    private Long requirePlaylistId(String userId, String playlistName) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM music_playlist WHERE user_id = ? AND name = ?",
                Long.class, userId, playlistName);

        if (ids.isEmpty()) {
            throw new IllegalStateException(
                    "任务引用的歌单不存在：" + playlistName + "（user=" + userId + "）");
        }
        return ids.get(0);
    }

    private String resolveText(String text, Map<String, String> vars) {
        if (text == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = vars.get(key);
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    // ==================== 其他 ====================

    private String resolveUser(String alias) {
        return switch (alias == null ? "" : alias.toLowerCase(Locale.ROOT)) {
            case "alice" -> MusicSeedInitializer.USER_ALICE;
            case "bob" -> MusicSeedInitializer.USER_BOB;
            case "admin" -> MusicSeedInitializer.USER_ADMIN;
            default -> throw new IllegalArgumentException("未知测试用户：" + alias);
        };
    }

    private int diffWrites(Snapshot before, Snapshot after) {
        return (after.ids().size() - before.ids().size())
                + (after.songLinks() - before.songLinks());
    }

    private List<String> splitTrace(String trace) {
        if (trace == null || trace.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trace.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 一次快照：歌单 id 集合、歌曲关联总数、名称 → 歌曲。
     */
    private record Snapshot(Set<Long> ids, int songLinks, Map<String, List<Long>> byName) {
    }
}
