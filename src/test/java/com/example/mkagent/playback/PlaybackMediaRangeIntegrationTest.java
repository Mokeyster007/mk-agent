package com.example.mkagent.playback;

import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体交付的真实 HTTP 验证：Content-Type、Content-Length、Range / 206 / 416。
 *
 * <p>为什么必须打真实端口而不是 MockMvc：
 * 拖动进度条依赖的是**传输层**行为（206 + Content-Range + 精确的字节区间），
 * MockMvc 不会真正按区间写 body，测不出"seek 到了错误偏移"这类问题。
 *
 * <p>本类同时证明"签名地址可用、篡改地址不可用"：
 * 媒体接口不读 X-User-Id（{@code <audio>} 加不了自定义头），身份在令牌里。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class PlaybackMediaRangeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 测试音 A 的完整字节（用于逐字节比对区间）。 */
    private byte[] full;

    private String streamUrl;

    @BeforeEach
    void resolveOnce() throws Exception {
        String body = "{\"songId\":" + PlaybackFixtureInitializer.SONG_TEST_A + "}";

        HttpRequest resolveRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/ai/music/playback/resolve"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-User-Id", MusicSeedInitializer.USER_ALICE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resolveResponse =
                client.send(resolveRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, resolveResponse.statusCode(), resolveResponse.body());

        JsonNode json = JSON.readTree(resolveResponse.body());
        streamUrl = json.path("streamUrl").asText();
        assertTrue(streamUrl.startsWith("/api/"));

        HttpResponse<byte[]> media = get(streamUrl, null);
        assertEquals(200, media.statusCode());
        full = media.body();
    }

    @Test
    @DisplayName("无 Range：200 + 完整长度 + audio/wav + Accept-Ranges")
    void fullRequestReturnsWholeFile() {
        assertTrue(full.length > 100_000, "测试音应当是一段真实音频，而不是空文件");
        assertEquals("audio/wav", mediaTypeOfLastResponse);
        assertEquals(String.valueOf(full.length), lastContentLength);
        assertEquals("bytes", lastAcceptRanges);
    }

    @Test
    @DisplayName("Range 0-99：206 + Content-Range + 恰好 100 字节")
    void rangeRequestReturnsPartialContent() throws Exception {
        HttpResponse<byte[]> response = get(streamUrl, "bytes=0-99");

        assertEquals(206, response.statusCode());
        assertEquals("bytes 0-99/" + full.length, response.headers()
                .firstValue("Content-Range").orElse(null));
        assertEquals(100, response.body().length);
        assertArrayEquals(Arrays.copyOfRange(full, 0, 100), response.body());
    }

    @Test
    @DisplayName("Range 1000-1999：区间偏移正确（seek 拖到中段不会串音）")
    void rangeOffsetIsCorrect() throws Exception {
        HttpResponse<byte[]> response = get(streamUrl, "bytes=1000-1999");

        assertEquals(206, response.statusCode());
        assertEquals(1000, response.body().length);
        assertArrayEquals(Arrays.copyOfRange(full, 1000, 2000), response.body(),
                "区间字节不一致：说明服务端没有从正确的偏移开始读");
    }

    @Test
    @DisplayName("后缀 Range（bytes=-100）：返回最后 100 字节")
    void suffixRangeReturnsTail() throws Exception {
        HttpResponse<byte[]> response = get(streamUrl, "bytes=-100");

        assertEquals(206, response.statusCode());
        assertEquals(100, response.body().length);
        assertArrayEquals(
                Arrays.copyOfRange(full, full.length - 100, full.length),
                response.body());
    }

    @Test
    @DisplayName("越界 Range：416 + Content-Range: bytes */总长")
    void unsatisfiableRangeReturns416() throws Exception {
        HttpResponse<byte[]> response = get(streamUrl, "bytes=99999999-100000000");

        assertEquals(416, response.statusCode());
        assertEquals("bytes */" + full.length, response.headers()
                .firstValue("Content-Range").orElse(null));
    }

    @Test
    @DisplayName("篡改令牌：403，且只返回错误说明、不返回任何媒体字节")
    void tamperedTokenIsRejected() throws Exception {
        String tampered = streamUrl.substring(0, streamUrl.length() - 4) + "AAAA";

        HttpResponse<byte[]> response = get(tampered, null);

        assertEquals(403, response.statusCode());

        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("MEDIA_TOKEN_INVALID"), body);
        assertTrue(response.body().length < 512,
                "403 时只能是一小段 JSON，不能把音频内容吐出来");
    }

    @Test
    @DisplayName("完全伪造的令牌：403")
    void forgedTokenIsRejected() throws Exception {
        HttpResponse<byte[]> response = get("/api/ai/music/playback/media/v1.Zm9v.YmFy", null);

        assertEquals(403, response.statusCode());
    }

    /* ==================== 辅助 ==================== */

    private String mediaTypeOfLastResponse;
    private String lastContentLength;
    private String lastAcceptRanges;

    private HttpResponse<byte[]> get(String path, String range) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();

        if (range != null) {
            builder.header("Range", range);
        }

        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

        mediaTypeOfLastResponse = response.headers().firstValue("Content-Type").orElse(null);
        lastContentLength = response.headers().firstValue("Content-Length").orElse(null);
        lastAcceptRanges = response.headers().firstValue("Accept-Ranges").orElse(null);

        assertNotNull(response);
        return response;
    }
}
