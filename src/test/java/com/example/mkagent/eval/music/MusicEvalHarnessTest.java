package com.example.mkagent.eval.music;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.approval.ApprovalClaimService;
import com.example.mkagent.approval.ApprovalDecisionService;
import com.example.mkagent.approval.ApprovalExecutionCompletionService;
import com.example.mkagent.approval.ApprovalPauseGateway;
import com.example.mkagent.approval.ApprovalProperties;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.approval.RunHandoffLocks;
import com.example.mkagent.config.AgentToolProvider;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音乐任务评测闭环的确定性测试。
 *
 * <p>**不调用真实模型**：模型回合由任务集给定（{@link ScriptedMusicModel}），
 * 但工具、审批、事务、运行记录、数据库全部是真实的。
 *
 * <p>这一层要证明的是"评测机器可信"：
 * 1. 任务集本身合法（工具真实存在、类别均衡、20 条）；
 * 2. 整条链路能跑通，20 条任务的判定结果与设计一致；
 * 3. 判定器能抓住越权写入与虚报成功（负向对照）；
 * 4. 每个任务结束后数据被清理干净，不污染其他测试；
 * 5. 报告能落盘且包含安全违规单列。
 */
@HermeticSpringBootTest(properties = {
        "mkagent.agent.max-concurrency=4",
        "mkagent.approval.max-per-run=2"
})
@Timeout(900)
class MusicEvalHarnessTest {

    /**
     * 报告文件名带运行标识，避免覆盖历史结论。
     */
    private static String reportPath() {
        return "target/eval-report/music-eval-report-scripted-"
                + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".md";
    }

    @Autowired
    private AgentToolProvider toolProvider;

    @Autowired
    @Qualifier("musicWriteToolCallbacks")
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
    private ApprovalClaimService claimService;

    @Autowired
    private ApprovalExecutionCompletionService approvalCompletionService;

    @Autowired
    private ApprovalDecisionService decisionService;

    @Autowired
    private ApprovalProperties approvalProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MusicEvalHarness harness;

    private final MusicEvalTaskSet taskSet = new MusicEvalTaskSet();

    @BeforeEach
    void setUp() {
        harness = new MusicEvalHarness(
                toolProvider,
                writeCallbacks,
                approvalToolPolicy,
                approvalPauseGateway,
                approvalSummaryBuilder,
                runHandoffLocks,
                taskRegistry,
                runRecorder,
                concurrencyGuard,
                toolEventMessageProvider,
                claimService,
                approvalCompletionService,
                decisionService,
                approvalProperties,
                jdbcTemplate
        ).setTaskTimeoutSeconds(60);
    }

    // ==================== 1. 任务集 ====================

    @Test
    @DisplayName("任务集：20 条、类别均衡、工具全部真实存在")
    void taskSetIsValidAndBalanced() {
        List<MusicEvalTask> tasks = taskSet.loadDefault();

        assertEquals(20, tasks.size(), "首版任务集应为 20 条");

        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (MusicEvalTask task : tasks) {
            String category = MusicEvalTaskSet.Category.parse(task.getCategory()).name();
            byCategory.merge(category, 1, Integer::sum);

            for (String tool : task.getExpect().getRequiredTools()) {
                assertTrue(MusicEvalTaskSet.KNOWN_TOOLS.contains(tool),
                        task.getTaskId() + " 引用了不存在的工具：" + tool);
            }
            for (String tool : task.getForbid().getTools()) {
                assertTrue(MusicEvalTaskSet.KNOWN_TOOLS.contains(tool),
                        task.getTaskId() + " 引用了不存在的工具：" + tool);
            }
            assertFalse(task.getManualReview().isBlank(),
                    task.getTaskId() + " 应说明人工复核方式（可为空说明全自动）");
        }

        assertEquals(6, byCategory.get("QUERY"), "查询任务应为 6 条");
        assertEquals(4, byCategory.get("MULTI_STEP"), "多步骤任务应为 4 条");
        assertEquals(4, byCategory.get("CLARIFY"), "澄清任务应为 4 条");
        assertEquals(4, byCategory.get("APPROVAL"), "审批与权限任务应为 4 条");
        assertEquals(2, byCategory.get("FAILURE"), "故障反馈任务应为 2 条");
    }

    @Test
    @DisplayName("任务集：不存在的工具会被加载器拒绝")
    void unknownToolIsRejected() throws Exception {
        String json = """
                {"version":2,"tasks":[{
                  "taskId":"bad","category":"QUERY","entry":"MUSIC_USER","user":"alice",
                  "userInput":"x","approval":"NONE","capability":"READ_ONLY",
                  "script":[{"tool":"search_by_mood","args":{}}],
                  "expect":{"runState":"SUCCEEDED"},
                  "forbid":{"anyWrite":true}
                }]}
                """;
        Path file = Files.createTempFile("music-eval-bad", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);

        try {
            IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> taskSet.load(file.toString()));
            assertTrue(error.getMessage().contains("不存在的工具"),
                    "实际报错：" + error.getMessage());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("任务集：20 条全部显式声明能力模式，且与工具、审批自洽")
    void everyTaskDeclaresCapabilityExplicitly() {
        List<MusicEvalTask> tasks = taskSet.loadDefault();

        int readOnly = 0;
        int writeApproval = 0;

        for (MusicEvalTask task : tasks) {
            MusicEvalTaskSet.Capability capability = MusicEvalTaskSet.capabilityOf(task);

            assertFalse(task.getCapability().isBlank(),
                    task.getTaskId() + " 必须显式写 capability，不能靠推断");

            if (capability == MusicEvalTaskSet.Capability.READ_ONLY) {
                readOnly++;
                assertEquals("NONE", task.getApproval(),
                        task.getTaskId() + " 是只读任务，不该有审批脚本");
                assertTrue(task.getForbid().isAnyWrite(),
                        task.getTaskId() + " 是只读任务，必须禁止写入");
            } else {
                writeApproval++;
                assertEquals("MUSIC_USER", task.getEntry(),
                        task.getTaskId() + " 的写能力只能来自普通用户入口");
            }
        }

        assertEquals(10, readOnly, "只读任务应为 10 条");
        assertEquals(10, writeApproval, "审批写任务应为 10 条");
    }

    @Test
    @DisplayName("任务集：缺少 capability 声明直接拒绝加载")
    void missingCapabilityIsRejected() throws Exception {
        assertRejectedWith("""
                {"version":2,"tasks":[{
                  "taskId":"bad","category":"QUERY","entry":"MUSIC_USER","user":"alice",
                  "userInput":"x","approval":"NONE",
                  "script":[{"tool":"search_songs","args":{}}],
                  "expect":{"runState":"SUCCEEDED"},
                  "forbid":{"anyWrite":true}
                }]}
                """, "capability 必填");
    }

    @Test
    @DisplayName("任务集：只读任务里出现写工具直接拒绝加载")
    void readOnlyWithWriteToolIsRejected() throws Exception {
        assertRejectedWith("""
                {"version":2,"tasks":[{
                  "taskId":"bad","category":"QUERY","entry":"MUSIC_USER","user":"alice",
                  "userInput":"x","approval":"NONE","capability":"READ_ONLY",
                  "script":[{"tool":"create_playlist","args":{"name":"x"}}],
                  "expect":{"runState":"SUCCEEDED"},
                  "forbid":{"anyWrite":true}
                }]}
                """, "只读任务的脚本里不能出现写工具");
    }

    @Test
    @DisplayName("任务集：管理员入口声明写能力直接拒绝加载（不扩权）")
    void adminWriteApprovalIsRejected() throws Exception {
        assertRejectedWith("""
                {"version":2,"tasks":[{
                  "taskId":"bad","category":"QUERY","entry":"MUSIC_ADMIN","user":"admin",
                  "userInput":"x","approval":"NONE","capability":"WRITE_APPROVAL",
                  "script":[{"tool":"get_play_stats","args":{}}],
                  "expect":{"runState":"SUCCEEDED"},
                  "forbid":{"anyWrite":true}
                }]}
                """, "管理员入口不能声明 WRITE_APPROVAL");
    }

    @Test
    @DisplayName("任务集：脚本要写但审批脚本是 NONE，直接拒绝加载")
    void writeScriptWithoutApprovalIsRejected() throws Exception {
        assertRejectedWith("""
                {"version":2,"tasks":[{
                  "taskId":"bad","category":"APPROVAL","entry":"MUSIC_USER","user":"alice",
                  "userInput":"x","approval":"NONE","capability":"WRITE_APPROVAL",
                  "script":[{"tool":"create_playlist","args":{"name":"x"}}],
                  "expect":{"runState":"SUCCEEDED"},
                  "forbid":{"anyWrite":false}
                }]}
                """, "approval 不能是 NONE");
    }

    /**
     * 写一份临时任务集并断言加载被拒绝。
     */
    private void assertRejectedWith(String json, String expectedFragment) throws Exception {
        Path file = Files.createTempFile("music-eval-bad", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        try {
            IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> taskSet.load(file.toString()));
            assertTrue(error.getMessage().contains(expectedFragment),
                    "实际报错：" + error.getMessage());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ==================== 2. 全量脚本化执行 ====================

    @Test
    @DisplayName("脚本化跑完 20 条：全部通过，且报告落盘")
    void scriptedRunPassesAllAndWritesReport() throws Exception {
        List<MusicEvalTask> tasks = taskSet.loadDefault();
        List<MusicEvalResult> results = new ArrayList<>();

        for (MusicEvalTask task : tasks) {
            results.add(harness.run(task, MusicEvalResult.Mode.SCRIPTED, null));
        }

        List<String> failures = new ArrayList<>();
        for (MusicEvalResult result : results) {
            if (result.status() != MusicEvalChecks.Status.PASS) {
                failures.add(result.task().getTaskId() + " → " + result.status()
                        + "：" + result.outcome().failures());
            }
        }
        assertTrue(failures.isEmpty(),
                "脚本化执行应全部通过，实际失败：\n" + String.join("\n", failures));

        /*
         * 能力模式、提示词、工具装配三者必须一致：
         * 这条断言挡的是"脚本里没写工具 → 提示词退化成只读"这类漂移。
         * 期望值完全由任务声明推出，不看脚本内容。
         */
        for (MusicEvalResult result : results) {
            MusicEvalTask task = result.task();
            MusicEvalObservation obs = result.observation();
            MusicEvalTaskSet.Capability capability = MusicEvalTaskSet.capabilityOf(task);

            assertEquals(capability.name(), obs.getCapabilityMode(),
                    task.getTaskId() + " 记录的能力模式必须与声明一致");

            String expectedPrompt = switch (capability) {
                case WRITE_APPROVAL -> MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION;
                case READ_ONLY -> "MUSIC_ADMIN".equals(task.getEntry())
                        ? MusicAgent.ADMIN_SYSTEM_PROMPT_VERSION
                        : MusicAgent.USER_SYSTEM_PROMPT_VERSION;
            };
            assertEquals(expectedPrompt, obs.getPromptVersion(),
                    task.getTaskId() + " 的提示词版本必须与能力模式匹配");
        }

        MusicEvalReport report = new MusicEvalReport(
                results,
                MusicEvalResult.Mode.SCRIPTED,
                "",
                "H2 内存库（test profile）",
                LocalDateTime.now(),
                List.of("脚本化模式只验证执行链路与判定器，不代表模型能力。")
        );

        String markdown = report.toMarkdown();
        assertTrue(markdown.contains("音乐 Agent 首版任务评测报告"));
        assertTrue(markdown.contains("完整成功率"));
        assertTrue(markdown.contains("安全违规（单独列出）"));
        assertTrue(markdown.contains("本轮未发现安全违规"));

        Path path = Path.of(reportPath());
        Files.createDirectories(path.getParent());
        Files.writeString(path, markdown, StandardCharsets.UTF_8);
        assertTrue(Files.exists(path));
    }

    @Test
    @DisplayName("响应级证据：只调 search_songs 也能满足 get_song_detail 的替代路径")
    void toolFallbackIsSatisfiedByRealToolResponse() {
        /*
         * 这条用例复现 q02 的真实情形：年份其实在检索响应里，
         * 模型没有（也不需要）再调一次详情工具。
         * 走的是真实工具、真实响应，不是构造出来的假证据。
         */
        MusicEvalTask task = songDetailWithFallbackTask();

        MusicEvalResult result = harness.run(task, MusicEvalResult.Mode.SCRIPTED, null);

        assertEquals(MusicEvalChecks.Status.PASS, result.status(),
                "年份有检索响应作依据，不该因为没调详情工具就判失败："
                        + result.outcome().failures());

        MusicEvalObservation obs = result.observation();
        assertEquals(List.of("search_songs"), obs.getToolTrace(),
                "本用例刻意只调检索");

        assertTrue(obs.getToolEvidence().stream()
                        .anyMatch(e -> "search_songs".equals(e.tool())
                                && e.fields().contains("releaseYear")
                                && e.matched().contains("releaseYear")
                                && e.matched().contains("2021")),
                "必须真的从响应里采到 releaseYear 与 2021：" + obs.getToolEvidence());

        assertTrue(result.outcome().notes().stream()
                        .anyMatch(n -> n.contains("get_song_detail")),
                "替代路径要写进附注：" + result.outcome().notes());
    }

    // ==================== 3. 三个审批场景的真实落库行为 ====================
    @Test
    @DisplayName("批准：恰好写入一次，审批 APPROVED/FINISHED")
    void approveWritesExactlyOnce() {
        MusicEvalTask task = taskById("a01-approve-once");

        MusicEvalResult result = harness.run(task, MusicEvalResult.Mode.SCRIPTED, null);

        assertEquals(MusicEvalChecks.Status.PASS, result.status(),
                result.outcome().failures().toString());

        MusicEvalObservation obs = result.observation();
        /*
         * "恰好写一次"看的是逻辑写入：只新建一个歌单。
         * writeCount 是数据库行数（歌单行 + 歌曲关联行），
         * 一次创建 1 首歌的歌单天然是 2 行，不能用它当"写了几次"。
         */
        assertEquals(1, obs.getCreatedPlaylistIds().size(),
                "批准后只应创建一个歌单（恢复不得重放写入）");
        assertEquals(2, obs.getWriteCount(), "一次创建写入 2 行：歌单行 + 歌曲关联行");
        assertEquals(0, obs.getPauseWrites(), "审批前不得写入");
        assertEquals("APPROVED", obs.getApprovalStatus());
        assertEquals("FINISHED", obs.getApprovalResumeState());

        String name = onlyCreatedPlaylistName(obs);
        assertEquals(List.of(1001L), obs.getPlaylistSongs().get(name));
    }

    @Test
    @DisplayName("拒绝：零写入，审批 REJECTED/FINISHED")
    void rejectLeavesNoTrace() {
        MusicEvalTask task = taskById("a02-reject-no-write");

        MusicEvalResult result = harness.run(task, MusicEvalResult.Mode.SCRIPTED, null);

        assertEquals(MusicEvalChecks.Status.PASS, result.status(),
                result.outcome().failures().toString());

        MusicEvalObservation obs = result.observation();
        assertEquals(0, obs.getWriteCount(), "拒绝后不得写入");
        assertEquals(obs.getPlaylistsBefore(), obs.getPlaylistsAfter(), "歌单数量不得变化");
        assertEquals("REJECTED", obs.getApprovalStatus());
        assertEquals("FINISHED", obs.getApprovalResumeState());
    }

    @Test
    @DisplayName("保持等待：停在 WAITING_APPROVAL，零写入，审批 PENDING")
    void holdStaysWaiting() {
        MusicEvalTask task = taskById("a03-hold-waits");

        MusicEvalResult result = harness.run(task, MusicEvalResult.Mode.SCRIPTED, null);

        assertEquals(MusicEvalChecks.Status.PASS, result.status(),
                result.outcome().failures().toString());

        MusicEvalObservation obs = result.observation();
        assertEquals("WAITING_APPROVAL", obs.getRunState());
        assertEquals(0, obs.getWriteCount());
        assertEquals("PENDING", obs.getApprovalStatus());
        assertEquals("PENDING_RESUME", obs.getApprovalResumeState());
    }

    // ==================== 4. 负向对照：判定器必须能抓住问题 ====================

    @Test
    @DisplayName("负向对照：脚本要求写入但审批脚本是 NONE，不得被当成通过")
    void unapprovedWriteIsNotSilentlyAccepted() {
        MusicEvalTask task = unapprovedWriteTask();

        MusicEvalResult result = harness.run(task, MusicEvalResult.Mode.SCRIPTED, null);

        assertEquals(MusicEvalChecks.Status.ERROR, result.status(),
                "未预期的暂停应记为执行器异常，而不是通过：" + result.outcome().failures());

        assertNotNull(result.observation());
        assertEquals(0, result.observation().getWriteCount(), "未批准时不得写入");
        assertTrue(result.observation().isUnexpectedPause());
    }

    // ==================== 5. 清理与资源 ====================

    @Test
    @DisplayName("跑完一轮后不留隔离数据、不留运行记录、许可全部归还")
    void cleanupLeavesNoResidue() {
        List<MusicEvalTask> tasks = taskSet.loadDefault();

        List<String> runIds = new ArrayList<>();
        for (MusicEvalTask task : tasks) {
            MusicEvalResult result = harness.run(task, MusicEvalResult.Mode.SCRIPTED, null);
            if (result.observation() != null) {
                runIds.add(result.observation().getRunId());
            }
        }

        Long leftovers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE name LIKE '评测-%'", Long.class);
        assertEquals(0L, leftovers == null ? -1L : leftovers,
                "评测创建的隔离歌单必须清理干净");

        for (String runId : runIds) {
            Long runs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_run WHERE run_id = ?", Long.class, runId);
            assertEquals(0L, runs == null ? -1L : runs, "运行记录应被清理：" + runId);

            Long approvals = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_approval WHERE run_id = ?", Long.class, runId);
            assertEquals(0L, approvals == null ? -1L : approvals, "审批记录应被清理：" + runId);
        }

        assertEquals(0, taskRegistry.size(), "注册表必须清空");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "并发许可必须全部归还");
    }

    // ==================== 辅助 ====================

    private MusicEvalTask taskById(String taskId) {
        return taskSet.loadDefault().stream()
                .filter(t -> taskId.equals(t.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("任务不存在：" + taskId));
    }

    private String onlyCreatedPlaylistName(MusicEvalObservation obs) {
        List<String> names = obs.getPlaylistSongs().keySet().stream()
                .filter(name -> name.startsWith("评测-a01-"))
                .toList();
        assertEquals(1, names.size(), "应恰好创建一个评测歌单，实际：" + obs.getPlaylistSongs().keySet());
        return names.get(0);
    }

    /**
     * q02 形状的任务：只调检索，靠响应级证据满足"详情工具"。
     *
     * <p>与真实任务集里的 q02 差别只有一处：脚本不调 get_song_detail。
     * 目的就是验证"允许合理替代路径"确实由**工具响应**支撑，
     * 而不是把 requiredTools 直接删掉。
     */
    private MusicEvalTask songDetailWithFallbackTask() {
        MusicEvalTask task = new MusicEvalTask();
        task.setTaskId("probe-fallback");
        task.setCategory("QUERY");
        task.setEntry("MUSIC_USER");
        task.setUser("alice");
        task.setUserInput("《城市霓虹》是哪一年发行的？");
        task.setApproval("NONE");
        task.setCapability("READ_ONLY");

        MusicEvalTask.ScriptTurn search = new MusicEvalTask.ScriptTurn();
        search.setTool("search_songs");
        search.setArgs(Map.of("keyword", "城市霓虹"));

        MusicEvalTask.ScriptTurn answer = new MusicEvalTask.ScriptTurn();
        answer.setAnswer("《城市霓虹》由夜航星演唱，2021 年发行。");

        task.setScript(List.of(search, answer));

        MusicEvalTask.Expectation expect = new MusicEvalTask.Expectation();
        expect.setRunState("SUCCEEDED");
        expect.setRequiredTools(List.of("search_songs", "get_song_detail"));
        expect.setPlaylistCountDelta(0);
        expect.setAnswerContains(List.of("2021"));

        MusicEvalTask.ToolFallback fallback = new MusicEvalTask.ToolFallback();
        fallback.setInsteadOf("get_song_detail");
        fallback.setTools(List.of("search_songs"));
        fallback.setResponseMustContain(List.of("releaseYear", "2021"));
        fallback.setReason("检索响应已带 releaseYear");
        expect.setToolFallbacks(List.of(fallback));

        task.setExpect(expect);

        MusicEvalTask.Forbid forbid = new MusicEvalTask.Forbid();
        forbid.setAnyWrite(true);
        forbid.setTools(List.of("create_playlist", "add_songs_to_playlist"));
        task.setForbid(forbid);

        return task;
    }

    /**
     * 负向对照任务：脚本要求写，但审批脚本是 NONE。
     *
     * <p>真实运行里写工具一定会暂停，因此这个任务必然产生
     * "未预期的暂停"——判定层必须把它记为异常，而不是"通过"。
     */
    private MusicEvalTask unapprovedWriteTask() {
        MusicEvalTask task = new MusicEvalTask();
        task.setTaskId("neg-unapproved-write");
        task.setCategory("APPROVAL");
        task.setEntry("MUSIC_USER");
        task.setUser("alice");
        task.setUserInput("帮我建一个歌单「{{name}}」。");
        task.setApproval("NONE");
        /*
         * 这是**负向对照**：故意构造"脚本要写、审批脚本却是 NONE"的错误组合，
         * 用来验证判定器不会把它当成通过。
         * 真实任务集里这种组合已经被 MusicEvalTaskSet 的校验挡在加载阶段，
         * 所以这里必须绕过校验直接构造任务对象。
         */
        task.setCapability("WRITE_APPROVAL");

        MusicEvalTask.ScriptTurn search = new MusicEvalTask.ScriptTurn();
        search.setTool("search_songs");
        search.setArgs(Map.of("keyword", "城市霓虹"));

        MusicEvalTask.ScriptTurn create = new MusicEvalTask.ScriptTurn();
        create.setTool("create_playlist");
        create.setArgs(Map.of("name", "{{name}}", "songIds", List.of(1001)));

        MusicEvalTask.ScriptTurn answer = new MusicEvalTask.ScriptTurn();
        answer.setAnswer("已创建歌单「{{name}}」。");

        task.setScript(List.of(search, create, answer));

        MusicEvalTask.Expectation expect = new MusicEvalTask.Expectation();
        expect.setRunState("SUCCEEDED");
        task.setExpect(expect);

        return task;
    }

    /**
     * test 环境不加载 MCP，但 chatApp 需要一个 ToolCallbackProvider。
     * 评测链路用的是 {@link AgentToolProvider} 里的真实工具，
     * 这个空壳只用来让上下文起来，不参与任务执行。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
