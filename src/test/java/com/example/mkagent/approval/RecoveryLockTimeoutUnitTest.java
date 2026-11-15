package com.example.mkagent.approval;

import com.example.mkagent.agent.BaseAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事务级锁等待设置的**语句形状**证据（M3 批次 4B）。
 *
 * <p>本机没有可用 PostgreSQL（无 psql、无监听端口、Docker daemon 未运行），
 * 因此这里用假的 {@link DataSource} 元数据分别模拟两种数据库类型，断言：
 * <ul>
 *   <li>非 PG 上<b>不</b>发 {@code SET LOCAL}（H2 没有这个语法，发了就是启动即炸）；</li>
 *   <li>PG 上发出的语句带的是配置值，且下限被夹到 1ms（{@code lock_timeout=0} 是无限等）。</li>
 * </ul>
 *
 * <p><b>这只证明语句形状与分支选择</b>。PG 上"锁等待超时后事务回滚、被阻塞的 UPDATE
 * 重新求值 WHERE 后返回 0 行"这些语义，在本环境<b>未验证</b>，
 * 不能拿本测试当作 PG 并发行为的证据。
 */
class RecoveryLockTimeoutUnitTest {

    @Test
    @DisplayName("非 PG：不发 SET LOCAL（H2 无此语法）")
    void skipsSetLocalOnNonPostgres() throws SQLException {
        JdbcTemplate jdbcTemplate = spy(new JdbcTemplate());

        RecoverySweeper sweeper = newSweeper(jdbcTemplate, dataSourceReporting("H2"));

        assertDoesNotThrow(sweeper::applyLockTimeout);
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("PG：SET LOCAL lock_timeout 取配置毫秒值")
    void setsLockTimeoutOnPostgres() throws SQLException {
        JdbcTemplate jdbcTemplate = spy(new JdbcTemplate());
        doNothing().when(jdbcTemplate).execute(anyString());

        ApprovalProperties properties = new ApprovalProperties();
        properties.setRecoveryLockTimeout(Duration.ofMillis(2000));

        RecoverySweeper sweeper = newSweeper(jdbcTemplate, dataSourceReporting("PostgreSQL"), properties);

        sweeper.applyLockTimeout();

        verify(jdbcTemplate).execute("SET LOCAL lock_timeout = 2000");
    }

    @Test
    @DisplayName("PG：0 毫秒被夹到 1，不会退化成无限等")
    void clampsZeroToFiniteWait() throws SQLException {
        JdbcTemplate jdbcTemplate = spy(new JdbcTemplate());
        doNothing().when(jdbcTemplate).execute(anyString());

        ApprovalProperties properties = new ApprovalProperties();
        properties.setRecoveryLockTimeout(Duration.ZERO);

        RecoverySweeper sweeper = newSweeper(jdbcTemplate, dataSourceReporting("PostgreSQL"), properties);

        sweeper.applyLockTimeout();

        verify(jdbcTemplate).execute("SET LOCAL lock_timeout = 1");
    }

    @Test
    @DisplayName("数据库类型探测失败：跳过设置而不是让启动崩掉")
    void survivesMetadataFailure() throws SQLException {
        JdbcTemplate jdbcTemplate = spy(new JdbcTemplate());
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));

        RecoverySweeper sweeper = newSweeper(jdbcTemplate, dataSource);

        assertDoesNotThrow(sweeper::applyLockTimeout);
        verify(jdbcTemplate, never()).execute(anyString());
    }

    private RecoverySweeper newSweeper(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        return newSweeper(jdbcTemplate, dataSource, new ApprovalProperties());
    }

    private RecoverySweeper newSweeper(
            JdbcTemplate jdbcTemplate, DataSource dataSource, ApprovalProperties properties) {
        return new RecoverySweeper(
                jdbcTemplate,
                mock(PlatformTransactionManager.class),
                mock(ApprovalClaimService.class),
                properties,
                List.<BaseAgent>of(),
                dataSource
        );
    }

    private DataSource dataSourceReporting(String productName) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        doReturn(metaData).when(connection).getMetaData();
        when(metaData.getDatabaseProductName()).thenReturn(productName);

        return dataSource;
    }
}
