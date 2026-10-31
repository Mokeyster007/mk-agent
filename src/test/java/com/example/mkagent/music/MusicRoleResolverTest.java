package com.example.mkagent.music;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 角色解析（H2 + 种子数据）。
 *
 * 三条必须成立的性质：
 * 1. 表里没有的人 → UNKNOWN，**不能**回落成 USER；
 * 2. 角色列没有约束，脏值（大小写、空格、未知角色）不能升权；
 * 3. 没有上下文时 resolveCurrent() → UNKNOWN，而不是抛异常。
 */
@HermeticSpringBootTest
class MusicRoleResolverTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private MusicRoleResolver resolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        AgentRunContextHolder.clear();
        jdbcTemplate.update(
                "DELETE FROM music_user WHERE user_id LIKE 'role-test-%'");
    }

    @Test
    @DisplayName("种子数据：普通用户是 USER，管理员是 ADMIN")
    void seededUsersResolveAsExpected() {
        assertEquals(MusicRoleResolver.MusicRole.USER,
                resolver.resolve("music-user-1"));
        assertEquals(MusicRoleResolver.MusicRole.USER,
                resolver.resolve("music-user-2"));
        assertEquals(MusicRoleResolver.MusicRole.ADMIN,
                resolver.resolve("music-admin-1"));
    }

    @Test
    @DisplayName("表里没有的用户 → UNKNOWN（不回落 USER）")
    void unknownUserIsNotTreatedAsUser() {
        assertEquals(MusicRoleResolver.MusicRole.UNKNOWN,
                resolver.resolve("role-test-ghost"));
    }

    @Test
    @DisplayName("空 userId → UNKNOWN，不查库也不报错")
    void blankUserIdIsUnknown() {
        assertEquals(MusicRoleResolver.MusicRole.UNKNOWN, resolver.resolve(null));
        assertEquals(MusicRoleResolver.MusicRole.UNKNOWN, resolver.resolve(""));
        assertEquals(MusicRoleResolver.MusicRole.UNKNOWN, resolver.resolve("   "));
    }

    @Test
    @DisplayName("角色脏值：大小写与空格不影响判定，未知角色一律按 USER（不升权）")
    void dirtyRoleValuesNeverEscalate() {
        insertUser("role-test-lower", " admin ");
        insertUser("role-test-editor", "EDITOR");

        assertEquals(MusicRoleResolver.MusicRole.ADMIN,
                resolver.resolve("role-test-lower"));
        assertEquals(MusicRoleResolver.MusicRole.USER,
                resolver.resolve("role-test-editor"),
                "未知角色不能升成管理员");
    }

    @Test
    @DisplayName("resolveCurrent 读任务上下文；没有上下文 → UNKNOWN")
    void resolveCurrentUsesBoundContext() {
        assertEquals(MusicRoleResolver.MusicRole.UNKNOWN,
                resolver.resolveCurrent(), "没有绑定上下文时不能猜");

        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId("music-admin-1");
        AgentRunContextHolder.bind(ctx);

        assertEquals(MusicRoleResolver.MusicRole.ADMIN,
                resolver.resolveCurrent());
    }

    private void insertUser(String userId, String role) {
        jdbcTemplate.update(
                "INSERT INTO music_user (user_id, role, created_at) VALUES (?, ?, ?)",
                userId, role, Timestamp.valueOf(LocalDateTime.now()));
    }
}
