package com.example.mkagent.conversation;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 会话与消息的读写（同一窗口连续聊天）。
 *
 * <p>三个 ID 的边界（写在这里，避免以后又被混用）：
 * <ul>
 *   <li>{@code conversationId}：会话。一个窗口里的多轮，长期存在；</li>
 *   <li>{@code runId}：一次提交。**每轮新建**，终态 run 绝不复用；</li>
 *   <li>{@code epoch}：某个 run 的执行代次，只在审批恢复时 +1，由既有代码维护。</li>
 * </ul>
 *
 * <p>这个类不碰审批：审批仍然绑定 run 与 checkpoint，
 * 加会话不会改变审批的幂等身份（幂等键由 run + step + tool 派生）。
 */
@Service
public class ConversationService {

    private static final Logger log =
            LoggerFactory.getLogger(ConversationService.class);

    /**
     * 带进模型的历史消息条数（用户 + 助手合计）。
     *
     * <p>6 条 ≈ 最近 3 轮。再多会挤占本轮的工具结果空间，
     * 而且"很久以前那首歌"本来就不该靠上下文猜——那需要长期记忆，本轮不做。
     */
    private static final int MAX_HISTORY_MESSAGES = 6;

    /**
     * 单条历史消息进入上下文时的长度上限。
     *
     * <p>助手回答动辄上千字（尤其是列举歌单），全文回灌会让每轮输入线性膨胀。
     * 截断只影响"模型看到的措辞"，不影响任何业务事实——
     * 事实走 {@link ConversationRefs}。
     */
    private static final int MAX_HISTORY_CHARS = 800;

    private static final int MAX_TITLE_CHARS = 40;

    private static final int MAX_CONTENT_CHARS = 20000;

    /**
     * "活跃 run"的判定窗口。
     *
     * <p>库里可能出现"进程被杀、留下 RUNNING"的行（启动收尾扫描默认关闭）。
     * 只按状态判断会让这个会话永久卡死，所以再加一条时间窗：
     * 超过这个时间还没更新的 RUNNING，视为已经死掉，不再阻塞新消息。
     */
    private static final Duration ACTIVE_RUN_WINDOW = Duration.ofMinutes(10);

    private static final RowMapper<ConversationRow> CONVERSATION_MAPPER =
            (rs, rowNum) -> new ConversationRow(
                    rs.getString("conversation_id"),
                    rs.getString("user_id"),
                    rs.getString("title"),
                    rs.getString("refs_json"),
                    rs.getString("last_run_id"),
                    rs.getString("agent_type"),
                    toLocal(rs, "created_at"),
                    toLocal(rs, "updated_at")
            );

    private static final RowMapper<MessageRow> MESSAGE_MAPPER =
            (rs, rowNum) -> new MessageRow(
                    rs.getString("run_id"),
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getString("state"),
                    toLocal(rs, "created_at")
            );

    /**
     * 详情用的映射：多带一个"该轮还有没有待审批"。
     *
     * <p>刷新页面后审批卡要能重建——否则用户看到"等待审批"却没有任何按钮，
     * 只能新建会话重来。approvalId 是重建卡片的唯一入口
     * （决定接口只收 approvalId，不接受参数）。
     */
    private static final RowMapper<MessageDetailRow> MESSAGE_DETAIL_MAPPER =
            (rs, rowNum) -> new MessageDetailRow(
                    rs.getString("run_id"),
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getString("state"),
                    toLocal(rs, "created_at"),
                    rs.getString("approval_id")
            );

    private final JdbcTemplate jdbcTemplate;

    public ConversationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 会话本身 ====================

    /**
     * 新建会话（音乐入口的兼容重载）。
     */
    public String create(String userId) {
        return create(userId, AgentType.MUSIC_USER);
    }

    /**
     * 新建会话，并钉死它属于哪个业务领域。
     *
     * <p>{@code agentType} 由**入口**决定（音乐入口 MUSIC_USER、售后入口 AFTER_SALES），
     * 不接受请求参数。它和 {@code user_id} 一起构成会话的归属：
     * 同一个人的音乐会话与售后会话是两条互不可见的记录，
     * 否则"能读自己的音乐会话"就等于"能拿它当售后会话继续聊"。
     */
    public String create(String userId, AgentType agentType) {
        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        jdbcTemplate.update(
                "INSERT INTO agent_conversation "
                        + "(id, conversation_id, user_id, agent_type, title, refs_json, last_run_id, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                IdWorker.getId(), conversationId, userId, agentType.name(),
                null, null, null, now, now);

        return conversationId;
    }

    /**
     * 取会话并校验归属；不存在、不是本人、领域不符三种情况共用同一句文案。
     *
     * <p>领域不符也走 404 而不是 403：403 等于告诉调用方
     * "这个 conversationId 确实存在，只是不属于这个入口"，
     * 于是可以用音乐入口枚举出别人的售后会话 id。
     */
    public ConversationRow requireOwned(String userId, String conversationId) {
        return requireOwned(userId, conversationId, AgentType.MUSIC_USER);
    }

    /**
     * 取会话并校验归属 + 领域。
     *
     * @param agentType 当前入口对应的领域；与记录的领域不符时按"不存在"处理
     */
    public ConversationRow requireOwned(
            String userId,
            String conversationId,
            AgentType agentType
    ) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(400, "缺少 conversationId");
        }

        List<ConversationRow> rows = jdbcTemplate.query(
                "SELECT conversation_id, user_id, title, refs_json, last_run_id, agent_type, "
                        + "created_at, updated_at FROM agent_conversation "
                        + "WHERE conversation_id = ?",
                CONVERSATION_MAPPER,
                conversationId);

        if (rows.isEmpty() || !userId.equals(rows.get(0).userId())
                || !agentType.name().equals(rows.get(0).agentType())) {
            throw new BusinessException(404, "会话不存在或无权访问：" + conversationId);
        }

        return rows.get(0);
    }

    /**
     * 会话列表（按最近活动倒序），附带每条会话的最近一轮状态。
     */
    public List<ConversationSummary> list(String userId) {
        return list(userId, AgentType.MUSIC_USER);
    }

    /** 会话列表，只含当前领域的会话。 */
    public List<ConversationSummary> list(String userId, AgentType agentType) {
        return jdbcTemplate.query(
                "SELECT c.conversation_id, c.title, c.updated_at, "
                        + "(SELECT COUNT(*) FROM agent_conversation_message m "
                        + "  WHERE m.conversation_id = c.conversation_id) AS message_count, "
                        + "(SELECT r.state FROM agent_run r WHERE r.run_id = c.last_run_id) AS last_state "
                        + "FROM agent_conversation c WHERE c.user_id = ? AND c.agent_type = ? "
                        + "ORDER BY c.updated_at DESC LIMIT 50",
                (rs, rowNum) -> new ConversationSummary(
                        rs.getString("conversation_id"),
                        rs.getString("title"),
                        rs.getInt("message_count"),
                        rs.getString("last_state"),
                        toLocal(rs, "updated_at")
                ),
                userId,
                agentType.name());
    }

    /**
     * 会话详情：消息按时间正序（前端直接顺序渲染）。
     */
    public ConversationDetail detail(String userId, String conversationId) {
        return detail(userId, conversationId, AgentType.MUSIC_USER);
    }

    /** 会话详情（带领域校验）。 */
    public ConversationDetail detail(
            String userId,
            String conversationId,
            AgentType agentType
    ) {
        ConversationRow row = requireOwned(userId, conversationId, agentType);

        List<ConversationMessage> messages = jdbcTemplate.query(
                "SELECT m.run_id, m.role, m.content, m.state, m.created_at, "
                        + "(SELECT a.approval_id FROM agent_approval a "
                        + "  WHERE a.run_id = m.run_id AND a.status = 'PENDING') AS approval_id "
                        + "FROM agent_conversation_message m WHERE m.conversation_id = ? "
                        + "ORDER BY m.id ASC",
                MESSAGE_DETAIL_MAPPER,
                conversationId).stream()
                .map(m -> new ConversationMessage(
                        m.runId(), m.role(), m.content(), m.state(),
                        m.createdAt(), m.approvalId()))
                .toList();

        return new ConversationDetail(
                row.conversationId(),
                row.title(),
                messages,
                ConversationRefs.fromJson(row.refsJson()).renderBlock(),
                row.updatedAt());
    }

    // ==================== 一轮的开始 ====================

    /**
     * 开始一轮：校验会话、拦并发、组装上下文、落用户消息。
     *
     * @param conversationId 为空时新建一个会话（兼容老的独立任务入口）
     * @param runId          本轮 run（由调用方生成，便于先落消息再启动）
     */
    public ConversationSeed beginTurn(
            String userId,
            String conversationId,
            String message,
            UUID runId
    ) {
        return beginTurn(userId, conversationId, message, runId, AgentType.MUSIC_USER);
    }

    /**
     * 开始一轮（带领域校验）。
     *
     * <p>领域必须由调用方按**入口**传入：售后入口传 AFTER_SALES。
     * 传错不会"串台"，而是拿不到会话（404）——这正是要的效果。
     */
    public ConversationSeed beginTurn(
            String userId,
            String conversationId,
            String message,
            UUID runId,
            AgentType agentType
    ) {
        String effectiveConversationId = (conversationId == null || conversationId.isBlank())
                ? create(userId, agentType)
                : requireOwned(userId, conversationId, agentType).conversationId();

        assertNoActiveRun(effectiveConversationId);

        ConversationRow row = requireOwned(userId, effectiveConversationId, agentType);
        ConversationRefs refs = ConversationRefs.fromJson(row.refsJson());

        List<org.springframework.ai.chat.messages.Message> history =
                loadHistory(effectiveConversationId, runId);

        insertMessage(effectiveConversationId, runId.toString(), "USER", message, null);

        return new ConversationSeed(
                effectiveConversationId,
                runId,
                history,
                refs.renderBlock()
        );
    }

    /**
     * 同一会话不允许并行两个任务。
     *
     * <p>为什么不做排队：一个会话里两个 run 同时改同一个歌单，
     * 审批卡会同时出现两张，用户根本无法判断先批哪张。
     * 先做最简单的"一次一轮"，把等待说清楚；跨会话仍然互不影响。
     */
    private void assertNoActiveRun(String conversationId) {
        LocalDateTime threshold = LocalDateTime.now().minus(ACTIVE_RUN_WINDOW);

        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE conversation_id = ? "
                        + "AND state IN ('RUNNING', 'WAITING_APPROVAL') AND updated_at >= ?",
                Integer.class,
                conversationId,
                Timestamp.valueOf(threshold));

        if (active != null && active > 0) {
            throw new BusinessException(409,
                    "当前会话还有未完成的任务（执行中或等待审批），请先处理它，"
                            + "或新建一个会话继续。");
        }
    }

    /**
     * 启动失败时把刚落的用户消息撤掉，避免会话里留一条没有回答的提问。
     */
    public void dropUserMessage(String runId) {
        try {
            jdbcTemplate.update(
                    "DELETE FROM agent_conversation_message WHERE run_id = ? AND role = 'USER'",
                    runId);
        } catch (Exception e) {
            log.warn("回滚用户消息失败（忽略）：runId={}", runId, e);
        }
    }

    /**
     * 载入最近若干条历史（不含本轮）。
     *
     * <p>只取 USER / ASSISTANT 的**文本**，不取工具调用：
     * 回放旧工具调用等于让模型把历史指令当本轮指令再执行一遍。
     */
    private List<org.springframework.ai.chat.messages.Message> loadHistory(
            String conversationId,
            UUID currentRunId
    ) {
        List<MessageRow> rows = jdbcTemplate.query(
                "SELECT run_id, role, content, state, created_at "
                        + "FROM agent_conversation_message "
                        + "WHERE conversation_id = ? AND run_id <> ? "
                        + "ORDER BY id DESC LIMIT ?",
                MESSAGE_MAPPER,
                conversationId,
                currentRunId.toString(),
                MAX_HISTORY_MESSAGES);

        Collections.reverse(rows);

        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>();
        for (MessageRow row : rows) {
            String content = row.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            content = truncate(content, MAX_HISTORY_CHARS);

            if ("USER".equals(row.role())) {
                history.add(new org.springframework.ai.chat.messages.UserMessage(content));
            } else if ("ASSISTANT".equals(row.role())) {
                history.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
            }
            // 其他角色（将来可能扩展）直接跳过，不让未知类型进入模型输入
        }

        return history;
    }

    // ==================== 一轮的结束 ====================

    /**
     * 立刻把本轮已收集到的业务引用写回会话。
     *
     * <p>为什么不是只在终态写一次：审批暂停会切断上下文——
     * 恢复执行时 ctx 是从检查点重建的，暂停前搜索到的歌曲引用会丢。
     * 而"把前两首建个歌单"这类请求恰好是"先搜、再暂停、再恢复"，
     * 丢掉歌曲引用会让下一轮的"刚才第三首"无从解析。
     *
     * <p>失败只记日志：引用是辅助信息，不能影响工具执行本身。
     */
    public void saveRefs(AgentRunContext ctx) {
        String conversationId = ctx.getConversationId();
        ConversationRefs refs = ctx.getConversationRefs();

        if (conversationId == null || conversationId.isBlank()
                || refs == null || refs.isEmpty()) {
            return;
        }

        try {
            jdbcTemplate.update(
                    "UPDATE agent_conversation SET refs_json = ?, updated_at = ? "
                            + "WHERE conversation_id = ?",
                    refs.toJson(),
                    Timestamp.valueOf(LocalDateTime.now()),
                    conversationId);
        } catch (Exception e) {
            log.warn("业务引用落库失败（忽略）：conversationId={}", conversationId, e);
        }
    }

    /**
     * 一轮结束：落助手回答、合并业务引用、刷新会话标题与时间。
     *
     * <p>由终态持久化路径调用，且**幂等**：(run_id, role) 唯一，
     * 审批恢复重写时是更新而不是追加一条重复回答。
     */
    public void finishTurn(AgentRunContext ctx) {
        String conversationId = ctx.getConversationId();

        /*
         * 审批恢复的执行代次是新建 ctx（从检查点重建），
         * conversationId 不在检查点里，这里按 run 反查一次。
         * 单一路径写会话，比把会话塞进检查点格式更不容易出错。
         */
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = conversationIdOf(ctx.getRunId().toString());
        }

        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        String runId = ctx.getRunId().toString();
        String answer = ctx.getFinalAnswer();
        String state = ctx.getState() == null ? null : ctx.getState().name();

        try {
            /*
             * 即使没有回答也要落一条（content 为空）：会话详情靠这一行的 state
             * 才能如实显示"这轮失败了/被取消了"。loadHistory 会跳过空内容，
             * 所以空回答不会污染模型上下文。
             */
            upsertAssistantMessage(conversationId, runId, answer, state);

            ConversationRefs refs = ctx.getConversationRefs();
            if (refs != null && !refs.isEmpty()) {
                jdbcTemplate.update(
                        "UPDATE agent_conversation SET refs_json = ?, updated_at = ? "
                                + "WHERE conversation_id = ?",
                        refs.toJson(),
                        Timestamp.valueOf(LocalDateTime.now()),
                        conversationId);
            }

            jdbcTemplate.update(
                    "UPDATE agent_conversation SET last_run_id = ?, updated_at = ? "
                            + "WHERE conversation_id = ?",
                    runId,
                    Timestamp.valueOf(LocalDateTime.now()),
                    conversationId);

            fillTitleIfBlank(conversationId, runId);
        } catch (Exception e) {
            // 会话落库失败不能影响任务结论：run 已经写完了
            log.error("会话轮次落库失败（不影响任务结论）：conversationId={}, runId={}",
                    conversationId, runId, e);
        }
    }

    /**
     * 按 run 反查会话（审批恢复路径用）。
     */
    private String conversationIdOf(String runId) {
        try {
            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT conversation_id FROM agent_run WHERE run_id = ?",
                    String.class,
                    runId);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("反查 run 的会话失败：runId={}", runId, e);
            return null;
        }
    }

    private void upsertAssistantMessage(
            String conversationId,
            String runId,
            String answer,
            String state
    ) {
        String content = truncate(answer, MAX_CONTENT_CHARS);

        int updated = jdbcTemplate.update(
                "UPDATE agent_conversation_message SET content = ?, state = ? "
                        + "WHERE run_id = ? AND role = 'ASSISTANT'",
                content, state, runId);

        if (updated > 0) {
            return;
        }

        try {
            insertMessage(conversationId, runId, "ASSISTANT", content, state);
        } catch (DuplicateKeyException e) {
            // 并发收尾：另一个线程刚插入，改成更新即可
            jdbcTemplate.update(
                    "UPDATE agent_conversation_message SET content = ?, state = ? "
                            + "WHERE run_id = ? AND role = 'ASSISTANT'",
                    content, state, runId);
        }
    }

    /**
     * 首轮用用户输入生成标题，之后不再改：标题只是列表里的辨识线索。
     */
    private void fillTitleIfBlank(String conversationId, String runId) {
        List<String> titles = jdbcTemplate.queryForList(
                "SELECT title FROM agent_conversation WHERE conversation_id = ?",
                String.class,
                conversationId);

        if (!titles.isEmpty() && titles.get(0) != null && !titles.get(0).isBlank()) {
            return;
        }

        List<String> prompts = jdbcTemplate.queryForList(
                "SELECT content FROM agent_conversation_message "
                        + "WHERE run_id = ? AND role = 'USER'",
                String.class,
                runId);

        if (prompts.isEmpty() || prompts.get(0) == null) {
            return;
        }

        String title = truncate(prompts.get(0).replaceAll("\\s+", " ").trim(), MAX_TITLE_CHARS);

        jdbcTemplate.update(
                "UPDATE agent_conversation SET title = ? WHERE conversation_id = ?",
                title, conversationId);
    }

    private void insertMessage(
            String conversationId,
            String runId,
            String role,
            String content,
            String state
    ) {
        jdbcTemplate.update(
                "INSERT INTO agent_conversation_message "
                        + "(id, conversation_id, run_id, role, content, state, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                IdWorker.getId(),
                conversationId,
                runId,
                role,
                truncate(content, MAX_CONTENT_CHARS),
                state,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    private static LocalDateTime toLocal(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    // ==================== 行对象 ====================

    /** 会话行。 */
    public record ConversationRow(
            String conversationId,
            String userId,
            String title,
            String refsJson,
            String lastRunId,
            /** 会话所属业务领域（{@link AgentType} 的名字）；历史行为 MUSIC_USER。 */
            String agentType,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private record MessageRow(
            String runId,
            String role,
            String content,
            String state,
            LocalDateTime createdAt
    ) {
    }

    private record MessageDetailRow(
            String runId,
            String role,
            String content,
            String state,
            LocalDateTime createdAt,
            String approvalId
    ) {
    }

    /** 列表项。 */
    public record ConversationSummary(
            String conversationId,
            String title,
            int messageCount,
            String lastState,
            LocalDateTime updatedAt
    ) {
    }

    /** 详情里的一条消息。 */
    public record ConversationMessage(
            String runId,
            String role,
            String content,
            String state,
            LocalDateTime createdAt,
            /** 该轮仍有待审批时为 approvalId，否则 null。 */
            String approvalId
    ) {
    }

    /** 会话详情。 */
    public record ConversationDetail(
            String conversationId,
            String title,
            List<ConversationMessage> messages,
            String refsPreview,
            LocalDateTime updatedAt
    ) {
    }
}
