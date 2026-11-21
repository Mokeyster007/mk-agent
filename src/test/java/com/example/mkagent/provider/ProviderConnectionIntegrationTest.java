package com.example.mkagent.provider;

import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台连接（音乐账号）的端到端行为。
 *
 * <p>覆盖四件事：
 * <ol>
 *   <li>零 / 单 / 多连接的目录状态；</li>
 *   <li>跨用户隔离：看不到、也读不到别人的连接；</li>
 *   <li>解绑；</li>
 *   <li>真实来源在没有可信身份时**不能**绑定（不做假登录）。</li>
 * </ol>
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class ProviderConnectionIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    private static final String OTHER = MusicSeedInitializer.USER_BOB;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void cleanConnections() {
        jdbcTemplate.update(
                "DELETE FROM music_provider_connection WHERE user_id IN (?, ?)", USER, OTHER);
    }

    @Test
    @DisplayName("零连接：目录仍列出可用来源，但都未连接")
    void zeroConnections() throws Exception {
        JsonNode catalog = getJson("/api/ai/music/providers", USER);

        assertTrue(catalog.size() >= 2, "至少应列出两个模拟来源");

        boolean alpha = false;
        boolean beta = false;
        for (JsonNode node : catalog) {
            String id = node.path("provider").asText();
            assertFalse(node.path("connected").asBoolean(), "初始状态不应有连接");
            if ("demo-alpha".equals(id)) {
                alpha = true;
                assertTrue(node.path("simulated").asBoolean(), "演示来源必须标注 simulated");
                assertTrue(node.path("displayName").asText().contains("模拟"),
                        "展示名要写明这是模拟来源");
            }
            if ("demo-beta".equals(id)) {
                beta = true;
            }
        }
        assertTrue(alpha && beta, "两个模拟来源都应出现");
    }

    @Test
    @DisplayName("单连接：连接后目录显示 connected，并记录只读范围")
    void singleConnection() throws Exception {
        HttpResponse<String> response = post("/api/ai/music/providers/demo-alpha/connect", USER);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode node = JSON.readTree(response.body());
        assertTrue(node.path("connected").asBoolean());
        assertEquals("CONNECTED", node.path("status").asText());
        assertTrue(node.path("scopes").asText().contains("read:liked"));

        JsonNode catalog = getJson("/api/ai/music/providers", USER);
        for (JsonNode item : catalog) {
            if ("demo-alpha".equals(item.path("provider").asText())) {
                assertTrue(item.path("connected").asBoolean());
            }
        }
    }

    @Test
    @DisplayName("多连接：两个来源可以同时连接")
    void multipleConnections() throws Exception {
        assertEquals(200, post("/api/ai/music/providers/demo-alpha/connect", USER).statusCode());
        assertEquals(200, post("/api/ai/music/providers/demo-beta/connect", USER).statusCode());

        JsonNode connections = getJson("/api/ai/music/providers/connections", USER);
        assertEquals(2, connections.size());
    }

    @Test
    @DisplayName("跨用户隔离：B 看不到 A 的连接")
    void connectionsAreScopedToUser() throws Exception {
        post("/api/ai/music/providers/demo-alpha/connect", USER);

        assertEquals(0, getJson("/api/ai/music/providers/connections", OTHER).size());

        JsonNode catalog = getJson("/api/ai/music/providers", OTHER);
        for (JsonNode node : catalog) {
            assertFalse(node.path("connected").asBoolean(), "别人的连接不能出现在我的目录里");
        }
    }

    @Test
    @DisplayName("解绑：连接消失，目录回到未连接")
    void disconnectRemovesConnection() throws Exception {
        post("/api/ai/music/providers/demo-alpha/connect", USER);

        HttpResponse<String> deleted = delete("/api/ai/music/providers/demo-alpha/connection", USER);
        assertEquals(200, deleted.statusCode(), deleted.body());

        assertEquals(0, getJson("/api/ai/music/providers/connections", USER).size());

        // 幂等：再删一次也不报错
        assertEquals(200, delete("/api/ai/music/providers/demo-alpha/connection", USER).statusCode());
    }

    @Test
    @DisplayName("未知来源：404")
    void unknownProviderIs404() throws Exception {
        assertEquals(404, post("/api/ai/music/providers/nope/connect", USER).statusCode());
    }

    @Test
    @DisplayName("真实来源 + 不可信身份：拒绝绑定（不做假登录）")
    void realProviderRequiresTrustedIdentity() throws Exception {
        HttpResponse<String> response = post("/api/ai/music/providers/test-real/connect", USER);

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("IDENTITY_NOT_TRUSTED", JSON.readTree(response.body()).path("code").asText());

        assertEquals(0, getJson("/api/ai/music/providers/connections", USER).size(),
                "被拒绝的绑定不能留下连接记录");
    }

    @Test
    @DisplayName("缺身份：401")
    void missingIdentityIs401() throws Exception {
        assertEquals(401, get("/api/ai/music/providers", null).statusCode());
    }

    /* ==================== 辅助 ==================== */

    private JsonNode getJson(String path, String userId) throws Exception {
        HttpResponse<String> response = get(path, userId);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET();
        if (userId != null) {
            builder.header("X-User-Id", userId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (userId != null) {
            builder.header("X-User-Id", userId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> delete(String path, String userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .DELETE();
        if (userId != null) {
            builder.header("X-User-Id", userId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class Adapters {

        @Bean
        MusicProviderAdapter testRealProvider() {
            return new TestProviderAdapters.RealAdapter("test-real", true);
        }
    }
}
