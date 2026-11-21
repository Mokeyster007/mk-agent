package com.example.mkagent.eval.music;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.ConfiguredModelNameResolver;
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
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实模型下的音乐任务评测入口。
 *
 * <p><b>默认绝不运行</b>（会真实消耗模型额度）：
 * 1. {@code @Tag("live")} 被 surefire 默认排除；
 * 2. 只有 {@code -Dmkagent.eval.music.enabled=true} 时才启用。
 *
 * <p>运行命令（两道门都要显式打开）：
 * <pre>
 * mvn -Plive -Dtest=MusicEvalLiveRunnerTest -Dmkagent.eval.music.enabled=true test
 * </pre>
 *
 * <p>建议先跑冒烟子集（4 条，覆盖查询 / 批准 / 拒绝 / 等待）：
 * <pre>
 * mvn -Plive -Dtest=MusicEvalLiveRunnerTest \
 *     -Dmkagent.eval.music.enabled=true \
 *     -Dmkagent.eval.music.task-ids=q01-artist-search,a01-approve-once,a02-reject-no-write,a03-hold-waits \
 *     test
 * </pre>
 *
 * <p>隔离：使用独立 H2 内存库（不是业务库），评测前后不写生产白名单，
 * 每个任务结束后删除自己创建的歌单、审批、检查点与运行记录。
 */
@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude=com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.datasource.url=jdbc:h2:mem:mk_agent_music_eval;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "mkagent.rag.pgvector.enabled=false",
                "mkagent.eval.music.enabled=true"
        }
)
@ActiveProfiles("local")
@Tag("live")
@EnabledIfSystemProperty(named = "mkagent.eval.music.enabled", matches = "true")
@Timeout(3600)
class MusicEvalLiveRunnerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class EvalContextConfig {

        /**
         * 排除 MCP 自动配置后，chatApp 注入的 ToolCallbackProvider 由此桩提供。
         */
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private ChatModel chatModel;

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

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 替换 RAG 向量库，避免启动时调用真实 Embedding API。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    @Test
    @DisplayName("真实模型跑任务集并写出报告")
    void runLiveTaskSet() throws Exception {
        printRuntimeFacts();

        List<MusicEvalTask> tasks = selectTasks();

        MusicEvalHarness harness = new MusicEvalHarness(
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
        ).setTaskTimeoutSeconds(180);

        List<MusicEvalResult> results = new ArrayList<>();

        /*
         * 停止预案（写在跑之前，避免跑完再"事后解释"）：
         * 1. 一旦某条任务出现安全违规，**停止后续所有写能力任务**（只读任务继续，
         *    因为只读装配本身不产生业务写入，继续跑不会扩大风险）；
         * 2. 连续 3 条执行器异常（例如模型服务不可用）视为环境无法继续，
         *    剩余任务全部记"未执行"，不做手工重试；
         * 3. 未执行的任务保留在结果列表里，不从统计中删除。
         */
        boolean stopWrites = false;
        int consecutiveErrors = 0;
        String stopReason = "";

        for (MusicEvalTask task : tasks) {
            MusicEvalTaskSet.Capability capability = MusicEvalTaskSet.capabilityOf(task);

            if (stopWrites && capability == MusicEvalTaskSet.Capability.WRITE_APPROVAL) {
                results.add(MusicEvalResult.skipped(task, MusicEvalResult.Mode.LIVE, stopReason));
                System.out.printf("[music-eval-live] %s → 未执行（%s）%n",
                        task.getTaskId(), stopReason);
                continue;
            }

            if (consecutiveErrors >= 3) {
                String reason = "连续 3 条执行异常，判定环境无法继续，停止后续任务（不手工重试）";
                results.add(MusicEvalResult.skipped(task, MusicEvalResult.Mode.LIVE, reason));
                System.out.printf("[music-eval-live] %s → 未执行（%s）%n",
                        task.getTaskId(), reason);
                continue;
            }

            MusicEvalResult result = harness.run(task, MusicEvalResult.Mode.LIVE, chatModel);
            results.add(result);

            if (result.status() == MusicEvalChecks.Status.ERROR) {
                consecutiveErrors++;
            } else {
                consecutiveErrors = 0;
            }

            if (!result.safetyViolations().isEmpty() && !stopWrites) {
                stopWrites = true;
                stopReason = "前序任务出现安全违规，按预案停止后续写能力任务（证据已保留）";
                System.out.printf("[music-eval-live] ⚠️ 检测到安全违规，停止后续写任务：%s%n",
                        task.getTaskId());
            }

            System.out.printf(
                    "[music-eval-live] %s → %s（终态=%s，工具=%s，步数=%s，Token=%s）%n",
                    task.getTaskId(),
                    result.status(),
                    result.observation() == null ? "-" : result.observation().getRunState(),
                    result.observation() == null ? "-" : result.observation().getToolTrace(),
                    result.observation() == null ? "-" : result.observation().getSteps(),
                    result.observation() == null || result.observation().getTokens() == null
                            ? "未知" : result.observation().getTokens()
            );
        }

        String model = results.stream()
                .map(r -> r.observation() == null ? "" : r.observation().getModel())
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("（未取到模型名）");

        MusicEvalReport report = new MusicEvalReport(
                results,
                MusicEvalResult.Mode.LIVE,
                model,
                "H2 内存库（mk_agent_music_eval，独立于业务库）",
                LocalDateTime.now(),
                List.of("真实模型评测：结果只代表本次运行，不构成版本间回归结论。",
                        "能力模式按任务显式声明区分：生产只读 / 审批写能力（隔离评测启用），"
                                + "两组的成功率不可合并解读。",
                        "安全违规发生后只停止后续写能力任务，只读任务继续执行——"
                                + "只读装配不产生业务写入，继续跑不会扩大风险。",
                        "每个任务只跑一轮，不做手工重复直到通过；未执行的任务保留在统计里。"),
                provenance()
        );

        /*
         * 默认文件名带运行标识：历史结论只能追加，不能被新一次运行覆盖。
         */
        Path path = Path.of(System.getProperty(
                "mkagent.eval.music.report-path",
                "target/eval-report/music-eval-report-live-"
                        + LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + ".md"));
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, report.toMarkdown(), StandardCharsets.UTF_8);

        assertTrue(Files.exists(path), "报告应已写出：" + path);
        assertTrue(results.size() > 0, "至少执行一条任务");

        /*
         * 安全违规在真实模型下必须为零：这不是"效果"指标，而是底线。
         * 一旦出现，测试直接失败，但报告仍然已经落盘。
         */
        List<String> violations = new ArrayList<>();
        for (MusicEvalResult result : results) {
            result.safetyViolations().forEach(v ->
                    violations.add(result.task().getTaskId() + "：" + v));
        }
        assertTrue(violations.isEmpty(),
                "真实模型运行出现安全违规：\n" + String.join("\n", violations));

        assertEquals(0, taskRegistry.size(), "运行结束后注册表应清空");
    }

    /**
     * 打印本次运行的关键事实：profile、ChatModel 实现与 Bean 名、JDBC URL、模型名。
     *
     * <p>只打印类名/名称/URL，不打印任何凭据。目的是让"真实调用了什么"
     * 有运行期证据，而不是靠读配置推断。
     */
    private void printRuntimeFacts() {
        System.out.printf("[music-eval-live] profiles=%s%n",
                Arrays.toString(applicationContext.getEnvironment().getActiveProfiles()));
        System.out.printf("[music-eval-live] chatModel=%s%n",
                chatModel.getClass().getName());
        System.out.printf("[music-eval-live] chatModelBeans=%s%n",
                Arrays.toString(applicationContext.getBeanNamesForType(ChatModel.class)));
        System.out.printf("[music-eval-live] modelName=%s%n",
                ConfiguredModelNameResolver.resolve(chatModel));

        try (Connection connection = Objects.requireNonNull(
                jdbcTemplate.getDataSource()).getConnection()) {
            System.out.printf("[music-eval-live] jdbcUrl=%s%n",
                    connection.getMetaData().getURL());
        } catch (Exception e) {
            System.out.printf("[music-eval-live] jdbcUrl=unknown（%s）%n",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 收集本次运行的依据：代码状态、任务集指纹、提示词版本、预算与重试配置。
     *
     * <p>这些值在**运行开始时**采集，写进报告；不在报告生成时现算，
     * 否则记录的是"报告写出来那一刻"的状态，而不是"跑的时候"的状态。
     */
    private Map<String, String> provenance() {
        Map<String, String> facts = new LinkedHashMap<>();

        facts.put("代码版本（git）", gitValue("rev-parse", "--short", "HEAD"));
        facts.put("工作区状态", gitValue("status", "--porcelain").isBlank()
                ? "干净（无未提交改动）"
                : "有未提交改动：" + abbreviate(gitValue("status", "--porcelain"), 200));

        String taskSet = "版本 " + MusicEvalTaskSet.CURRENT_VERSION
                + "，共 " + taskCount() + " 条，sha256=" + taskSetDigest();
        facts.put("任务集", taskSet);

        facts.put("提示词版本", String.join("；",
                MusicAgent.USER_SYSTEM_PROMPT_VERSION,
                MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION,
                MusicAgent.ADMIN_SYSTEM_PROMPT_VERSION));

        facts.put("模型重试配置", "mkagent.model-retry.max-attempts=3（含首次调用）——沿用，未在本轮修改");
        facts.put("工具重试配置", "mkagent.tool-retry 仅作用于 web_search/web_scrape；音乐工具不自动重试");
        facts.put("预算配置", "单任务超时 180s；maxSteps / maxToolCalls 取自任务集，未在运行中修改");
        facts.put("数据库", "H2 内存库 mk_agent_music_eval；未连接业务库");

        return facts;
    }

    private String gitValue(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "未知（git 命令超时）";
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            return output.isBlank() ? "（空）" : output;
        } catch (Exception e) {
            return "未知（" + e.getClass().getSimpleName() + "）";
        }
    }

    private int taskCount() {
        try {
            return new MusicEvalTaskSet().loadDefault().size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private String taskSetDigest() {
        try (var input = new org.springframework.core.io.ClassPathResource(
                "eval/music-tasks.json").getInputStream()) {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.readAllBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "未知（" + e.getClass().getSimpleName() + "）";
        }
    }

    private String abbreviate(String text, int max) {
        String single = text.replace("\n", " / ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    /**
     * 选择要跑的任务：默认全量，可用系统属性指定子集。
     */
    private List<MusicEvalTask> selectTasks() {        List<MusicEvalTask> all = new MusicEvalTaskSet().loadDefault();

        String ids = System.getProperty("mkagent.eval.music.task-ids", "");
        if (ids.isBlank()) {
            return all;
        }

        List<String> wanted = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<MusicEvalTask> selected = all.stream()
                .filter(task -> wanted.contains(task.getTaskId()))
                .toList();

        assertEquals(wanted.size(), selected.size(),
                "指定的任务 ID 必须都存在：" + wanted);

        return selected;
    }
}
