package com.example.mkagent.conversation;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 会话领域回填：旧库升级时"已有售后会话会不会被归成音乐"。
 *
 * <h2>要证明的两件事</h2>
 * <ol>
 *   <li><b>缺陷是真的</b>：{@code ADD COLUMN ... DEFAULT 'MUSIC_USER'} 会把
 *       领域字段出现之前的售后会话标成音乐，于是买家刷新后恢复不了会话
 *       （领域不符 → 404），而这些会话还会出现在音乐侧列表里；</li>
 *   <li><b>回填有据可依</b>：{@code agent_run.agent_type} 记录了每一轮属于哪个 Agent，
 *       所以"这个会话跑过售后 run"是可查证的事实，不是启发式猜测。</li>
 * </ol>
 *
 * <p>用例直接调用 {@link ConversationDomainBackfill#backfill()}（而不是等应用启动），
 * 这样断言的是真实迁移代码，而不是"某个脚本大概跑过"。
 */
@HermeticSpringBootTest
class ConversationDomainBackfillTest {

    private static final String USER = "backfill-user-1";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationDomainBackfill backfill;

    @Autowired
    private ConversationService conversations;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agent_conversation_message WHERE conversation_id IN "
                + "(SELECT conversation_id FROM agent_conversation WHERE user_id = ?)", USER);
        jdbc.update("DELETE FROM agent_run WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM agent_conversation WHERE user_id = ?", USER);
    }

    @Test
    @DisplayName("旧售后会话（默认值 MUSIC_USER）按 run 的领域回填为 AFTER_SALES")
    void legacyAfterSaleConversationIsReclassified() {
        String conversationId = insertConversation(AgentType.MUSIC_USER);
        insertRun(conversationId, AgentType.AFTER_SALES);

        /*
         * 回填之前：买家在售后页恢复这个会话会被 404——
         * 这就是"旧库升级把售后会话归成音乐"的实际后果。
         */
        BusinessException before = assertThrows(BusinessException.class,
                () -> conversations.requireOwned(USER, conversationId, AgentType.AFTER_SALES));
        assertEquals(404, before.getHttpStatus(), "领域不符必须表现为不存在");

        assertEquals(1, backfill.backfill(), "应当修正 1 条会话");

        ConversationService.ConversationRow row = conversations.requireOwned(
                USER, conversationId, AgentType.AFTER_SALES);
        assertEquals(conversationId, row.conversationId());
        assertEquals(AgentType.AFTER_SALES.name(), row.agentType());

        // 回填之后它也不再属于音乐入口（不会两边都能读）
        assertThrows(BusinessException.class,
                () -> conversations.requireOwned(USER, conversationId, AgentType.MUSIC_USER));
    }

    @Test
    @DisplayName("音乐会话不被误改：有 MUSIC_USER run 的会话保持原样")
    void musicConversationIsUntouched() {
        String conversationId = insertConversation(AgentType.MUSIC_USER);
        insertRun(conversationId, AgentType.MUSIC_USER);

        assertEquals(0, backfill.backfill(), "没有售后 run 的会话不得被改动");
        assertEquals(AgentType.MUSIC_USER.name(), agentTypeOf(conversationId));
        conversations.requireOwned(USER, conversationId, AgentType.MUSIC_USER);
    }

    @Test
    @DisplayName("没有任何 run 的会话保持原样（不猜）")
    void conversationWithoutRunsIsUntouched() {
        String conversationId = insertConversation(AgentType.MUSIC_USER);

        assertEquals(0, backfill.backfill());
        assertEquals(AgentType.MUSIC_USER.name(), agentTypeOf(conversationId));
    }

    @Test
    @DisplayName("已经是 AFTER_SALES 的会话不动；回填幂等（第二次 0 行）")
    void backfillIsIdempotent() {
        String legacy = insertConversation(AgentType.MUSIC_USER);
        insertRun(legacy, AgentType.AFTER_SALES);

        String already = insertConversation(AgentType.AFTER_SALES);
        insertRun(already, AgentType.AFTER_SALES);

        assertEquals(1, backfill.backfill(), "只修正那一条旧的");
        assertEquals(0, backfill.backfill(), "再跑一次不应有任何改动");
        assertEquals(AgentType.AFTER_SALES.name(), agentTypeOf(legacy));
        assertEquals(AgentType.AFTER_SALES.name(), agentTypeOf(already));
    }

    @Test
    @DisplayName("一个会话里既有音乐 run 又有售后 run：判为售后（有售后 run 比默认值更接近事实）")
    void mixedRunsAreTreatedAsAfterSale() {
        String conversationId = insertConversation(AgentType.MUSIC_USER);
        insertRun(conversationId, AgentType.MUSIC_USER);
        insertRun(conversationId, AgentType.AFTER_SALES);

        assertEquals(1, backfill.backfill());
        assertEquals(AgentType.AFTER_SALES.name(), agentTypeOf(conversationId));
    }

    // ---------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------

    /**
     * 造一条"领域字段刚加上、默认值生效"的会话。
     *
     * <p>{@code agent_type} 显式写成 MUSIC_USER，就是旧库迁移后的真实状态：
     * 那一列出现之前写进来的行全部拿到默认值。
     */
    private String insertConversation(AgentType agentType) {
        String conversationId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_conversation
                    (id, conversation_id, user_id, agent_type, title, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                System.nanoTime(), conversationId, USER, agentType.name(), "旧会话");
        return conversationId;
    }

    /** 造一轮运行记录：领域回填的唯一依据。 */
    private void insertRun(String conversationId, AgentType agentType) {
        String runId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_run
                    (id, run_id, user_id, agent_type, state, conversation_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                System.nanoTime(), runId, USER, agentType.name(), conversationId);
    }

    private String agentTypeOf(String conversationId) {
        return jdbc.queryForObject(
                "SELECT agent_type FROM agent_conversation WHERE conversation_id = ?",
                String.class, conversationId);
    }
}
