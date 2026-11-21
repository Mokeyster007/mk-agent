package com.example.mkagent.provider;

import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
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

/**
 * 可信身份已就绪、但真实平台凭据/回调链路未就绪时的绑定行为。
 *
 * <p>这一组是"不做假登录"的第二道门：即使身份可信，
 * 没有凭据就不能绑；有凭据但没有 OAuth 回调也不能绑。
 * 两条都必须**明确失败**，而不是写一条"已连接"的空记录。
 *
 * <p>真实 OAuth 回调（state / PKCE / 令牌刷新）本轮未实现，
 * 因为既没有平台凭据，也没有可信登录态；相关设计见
 * {@code docs/music-provider-capabilities.md}。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mkagent.providers.trusted-identity=true")
@Timeout(120)
class ProviderBindingTrustedIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        jdbcTemplate.update(
                "DELETE FROM music_provider_connection WHERE user_id = ?", USER);
    }

    @Test
    @DisplayName("可信身份 + 凭据未配置：409 PROVIDER_NOT_CONFIGURED")
    void unconfiguredProviderIsRejected() throws Exception {
        HttpResponse<String> response = connect("test-real-unconfigured");

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("PROVIDER_NOT_CONFIGURED",
                JSON.readTree(response.body()).path("code").asText());
    }

    @Test
    @DisplayName("可信身份 + 凭据已配置：没有 OAuth 回调就不能绑（501）")
    void configuredProviderStillNeedsOauth() throws Exception {
        HttpResponse<String> response = connect("test-real-configured");

        assertEquals(501, response.statusCode(), response.body());
        assertEquals("PROVIDER_OAUTH_NOT_IMPLEMENTED",
                JSON.readTree(response.body()).path("code").asText());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_provider_connection WHERE user_id = ?",
                Integer.class, USER);
        assertEquals(0, rows, "失败的真实绑定不能留下连接记录");
    }

    private HttpResponse<String> connect(String provider) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/api/ai/music/providers/" + provider + "/connect"))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-User-Id", USER)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class Adapters {

        @Bean
        MusicProviderAdapter realUnconfiguredProvider() {
            return new TestProviderAdapters.RealAdapter("test-real-unconfigured", false);
        }

        @Bean
        MusicProviderAdapter realConfiguredProvider() {
            return new TestProviderAdapters.RealAdapter("test-real-configured", true);
        }
    }
}
