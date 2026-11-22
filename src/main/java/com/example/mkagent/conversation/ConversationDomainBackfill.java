package com.example.mkagent.conversation;

import com.example.mkagent.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话领域的一次性回填：把"领域字段出现之前的售后会话"从音乐改回售后。
 *
 * <h2>要解决的问题</h2>
 *
 * {@code agent_conversation.agent_type} 是 A1 收尾时加的列，
 * 迁移语句是 {@code ADD COLUMN ... NOT NULL DEFAULT 'MUSIC_USER'}。
 * 默认值对**音乐**历史数据是正确的（那一列出现之前，唯一支持会话的入口
 * 就是 {@code /ai/music/chat}），但对**售后**历史数据是错的：
 * A1 期间的售后入口写会话时不带领域，这些行会被一并标成音乐，后果是
 * <ol>
 *   <li>买家在售后页刷新时恢复不了会话（领域不符 → 404），对话看起来"丢了"；</li>
 *   <li>这些会话出现在音乐侧列表里——虽然同属一个人，但那是错的入口。</li>
 * </ol>
 *
 * <h2>为什么可以确定地回填</h2>
 *
 * 不猜：{@code agent_run.agent_type} 从第一天就记录了每一轮属于哪个 Agent，
 * 售后入口写的是 {@code AFTER_SALES}。于是"这个会话里跑过售后 run"
 * 就是一个可以查证的事实，而不是启发式判断。
 *
 * <h2>边界（必须一起读）</h2>
 *
 * <ul>
 *   <li>只改 {@code agent_type = 'MUSIC_USER'} 的行：已经是 AFTER_SALES 的不动；</li>
 *   <li>只改"存在售后 run"的会话：没有任何 run 的会话保持原样；</li>
 *   <li>一个会话里同时有音乐 run 与售后 run 时会被判为售后。正常路径做不到
 *       （入口钉死领域），真出现了说明有人手工写过库，此时"有售后 run"
 *       比"默认值"更接近事实；</li>
 *   <li>会话创建了但 run 从未落库（启动即失败）的极端情况无法回填——
 *       这种会话本来也没有回答，买家重开一个即可。</li>
 * </ul>
 *
 * <p>语句是幂等的：回填过的行不再满足 {@code agent_type = 'MUSIC_USER'}，
 * 每次启动重跑影响 0 行。放在这里而不是 schema.sql，是为了让这段
 * "需要解释的判断"能被测试直接调用，而不是只存在于一段启动脚本里。
 */
@Component
public class ConversationDomainBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConversationDomainBackfill.class);

    /**
     * 回填语句。
     *
     * <p>{@code EXISTS} 而不是 join + GROUP BY：只关心"有没有"，
     * 而且不会因为一个会话有多轮售后 run 而产生重复行。
     */
    static final String BACKFILL_SQL = """
            UPDATE agent_conversation c
               SET agent_type = ?
             WHERE c.agent_type = ?
               AND EXISTS (
                   SELECT 1 FROM agent_run r
                    WHERE r.conversation_id = c.conversation_id
                      AND r.agent_type = ?
               )
            """;

    private final JdbcTemplate jdbcTemplate;

    public ConversationDomainBackfill(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    /**
     * 执行回填。
     *
     * @return 被修正的会话数（幂等重跑为 0）
     */
    public int backfill() {
        try {
            int fixed = jdbcTemplate.update(
                    BACKFILL_SQL,
                    AgentType.AFTER_SALES.name(),
                    AgentType.MUSIC_USER.name(),
                    AgentType.AFTER_SALES.name());
            if (fixed > 0) {
                log.info("会话领域回填：{} 条历史售后会话由 MUSIC_USER 修正为 AFTER_SALES", fixed);
            }
            return fixed;
        } catch (Exception e) {
            /*
             * 回填失败不能让应用起不来：它只影响"旧会话能不能恢复"，
             * 新会话按入口正确写入，业务不受影响。留一条 WARN 供排查。
             */
            log.warn("会话领域回填失败（不影响启动与新会话）：{}", e.getMessage());
            return 0;
        }
    }
}
