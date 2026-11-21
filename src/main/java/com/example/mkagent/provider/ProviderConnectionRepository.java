package com.example.mkagent.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 平台连接的读写。
 *
 * <p>主键是 {@code (user_id, provider)}：一个用户对一个平台最多一条连接。
 * 所有查询都带 {@code user_id}，不存在"按 provider 全局查"的方法——
 * 跨用户读取不是"忘了加条件"，而是**没有这条路径**。
 */
@Repository
public class ProviderConnectionRepository {

    private static final RowMapper<ProviderConnection> MAPPER = (rs, rowNum) ->
            new ProviderConnection(
                    rs.getString("user_id"),
                    rs.getString("provider"),
                    rs.getString("provider_user_id"),
                    rs.getString("display_name"),
                    ProviderConnectionStatus.parse(rs.getString("status")),
                    rs.getString("scopes"),
                    rs.getString("credential_ref"),
                    toInstant(rs.getTimestamp("connected_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );

    private final JdbcTemplate jdbcTemplate;

    public ProviderConnectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProviderConnection> findAll(String userId) {
        return jdbcTemplate.query(
                "SELECT user_id, provider, provider_user_id, display_name, status, scopes, "
                        + "       credential_ref, connected_at, updated_at "
                        + "FROM music_provider_connection WHERE user_id = ? ORDER BY provider",
                MAPPER,
                userId
        );
    }

    public Optional<ProviderConnection> find(String userId, String provider) {
        List<ProviderConnection> rows = jdbcTemplate.query(
                "SELECT user_id, provider, provider_user_id, display_name, status, scopes, "
                        + "       credential_ref, connected_at, updated_at "
                        + "FROM music_provider_connection WHERE user_id = ? AND provider = ?",
                MAPPER,
                userId,
                provider
        );
        return rows.stream().findFirst();
    }

    /**
     * 写入/覆盖连接。
     *
     * <p>同样走 UPDATE→INSERT：与 {@code music_now_playing} 一致，
     * 避免依赖 H2 与 PostgreSQL 之间不一致的 upsert 方言。
     */
    public void upsert(ProviderConnection connection, Instant now) {
        Timestamp at = Timestamp.from(now);

        int updated = jdbcTemplate.update(
                "UPDATE music_provider_connection "
                        + "SET provider_user_id = ?, display_name = ?, status = ?, scopes = ?, "
                        + "    credential_ref = ?, updated_at = ? "
                        + "WHERE user_id = ? AND provider = ?",
                connection.providerUserId(),
                connection.displayName(),
                connection.status().name(),
                connection.scopes(),
                connection.credentialRef(),
                at,
                connection.userId(),
                connection.provider()
        );

        if (updated > 0) {
            return;
        }

        try {
            jdbcTemplate.update(
                    "INSERT INTO music_provider_connection "
                            + "(user_id, provider, provider_user_id, display_name, status, scopes, "
                            + " credential_ref, connected_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    connection.userId(),
                    connection.provider(),
                    connection.providerUserId(),
                    connection.displayName(),
                    connection.status().name(),
                    connection.scopes(),
                    connection.credentialRef(),
                    at,
                    at
            );
        } catch (org.springframework.dao.DuplicateKeyException race) {
            jdbcTemplate.update(
                    "UPDATE music_provider_connection "
                            + "SET provider_user_id = ?, display_name = ?, status = ?, scopes = ?, "
                            + "    credential_ref = ?, updated_at = ? "
                            + "WHERE user_id = ? AND provider = ?",
                    connection.providerUserId(),
                    connection.displayName(),
                    connection.status().name(),
                    connection.scopes(),
                    connection.credentialRef(),
                    at,
                    connection.userId(),
                    connection.provider()
            );
        }
    }

    /** 解绑：只删自己那一条。 */
    public boolean delete(String userId, String provider) {
        return jdbcTemplate.update(
                "DELETE FROM music_provider_connection WHERE user_id = ? AND provider = ?",
                userId, provider) > 0;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
