package com.example.mkagent.playback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体地址签名：只测"能不能被伪造/复用"，不测业务。
 *
 * <p>这些断言对应四条真实风险：
 * 1. 改 songId 换别人的歌 → 验签必须失败；
 * 2. 改 userId 换身份 → 验签必须失败；
 * 3. 过期后继续用 → 必须失败（用固定密钥自己签一个过期令牌，不 sleep）；
 * 4. 换密钥后旧地址还能用 → 必须失败（重启/换实例的场景）。
 */
class PlaybackSignerTest {

    private static final String SECRET = "test-secret-for-signer-unit-test";

    private static PlaybackSigner signer(String secret, long ttl) {
        return new PlaybackSigner(secret, ttl);
    }

    @Test
    @DisplayName("签发后能验签，并解出 songId / userId / 过期时间")
    void roundTrip() {
        PlaybackSigner signer = signer(SECRET, 300);

        PlaybackSigner.IssuedToken issued = signer.issue(1901L, "music-user-1");
        PlaybackSigner.Claims claims = signer.verify(issued.token()).orElseThrow();

        assertEquals(1901L, claims.songId());
        assertEquals("music-user-1", claims.userId());
        assertEquals(issued.expiresAtEpochSecond(), claims.expiresAtEpochSecond());
    }

    @Test
    @DisplayName("篡改 payload 里的 songId 会验签失败")
    void tamperedSongIdRejected() {
        PlaybackSigner signer = signer(SECRET, 300);
        String token = signer.issue(1901L, "music-user-1").token();

        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"s\":1903,\"u\":\"music-user-1\",\"e\":" + (Instant.now().getEpochSecond() + 300) + "}")
                        .getBytes(StandardCharsets.UTF_8));

        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertTrue(signer.verify(forged).isEmpty(), "改了 songId 仍然验签通过，说明签名没覆盖 payload");
    }

    @Test
    @DisplayName("篡改 userId 会验签失败")
    void tamperedUserIdRejected() {
        PlaybackSigner signer = signer(SECRET, 300);
        String token = signer.issue(1903L, "music-user-2").token();

        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"s\":1903,\"u\":\"music-user-1\",\"e\":" + (Instant.now().getEpochSecond() + 300) + "}")
                        .getBytes(StandardCharsets.UTF_8));

        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertTrue(signer.verify(forged).isEmpty());
    }

    @Test
    @DisplayName("签名有效但已过期：仍然拒绝")
    void expiredTokenRejected() throws Exception {
        PlaybackSigner signer = signer(SECRET, 300);

        // 用同一个密钥手工签一个"一小时前就过期"的令牌
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"s\":1901,\"u\":\"music-user-1\",\"e\":" + (Instant.now().getEpochSecond() - 3600) + "}")
                        .getBytes(StandardCharsets.UTF_8));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));

        String token = "v1." + payload + "." + signature;

        assertTrue(signer.verify(token).isEmpty(), "过期令牌必须被拒绝，否则短期地址等于永久地址");
    }

    @Test
    @DisplayName("换密钥后旧地址失效（重启 / 多实例未共享密钥的场景）")
    void tokenFromOtherSecretRejected() {
        String token = signer("secret-a", 300).issue(1901L, "music-user-1").token();

        assertTrue(signer("secret-b", 300).verify(token).isEmpty());
    }

    @Test
    @DisplayName("格式错误 / 空 / 未知版本一律拒绝，且不抛异常")
    void malformedTokensRejected() {
        PlaybackSigner signer = signer(SECRET, 300);

        assertTrue(signer.verify(null).isEmpty());
        assertTrue(signer.verify("").isEmpty());
        assertTrue(signer.verify("garbage").isEmpty());
        assertTrue(signer.verify("v2.a.b").isEmpty());
        assertTrue(signer.verify("v1.!!!.???").isEmpty());
    }

    @Test
    @DisplayName("同一首歌不同用户签出的令牌不同（绑定身份）")
    void tokensAreBoundToUser() {
        PlaybackSigner signer = signer(SECRET, 300);

        String a = signer.issue(1901L, "music-user-1").token();
        String b = signer.issue(1901L, "music-user-2").token();

        assertNotEquals(a, b);
        assertFalse(signer.verify(a).orElseThrow().userId().equals("music-user-2"));
    }

    @Test
    @DisplayName("TTL 被夹到 [30, 3600] 秒，避免配置成永久地址")
    void ttlIsClamped() {
        assertEquals(30, signer(SECRET, 0).ttlSeconds());
        assertEquals(3600, signer(SECRET, 99_999).ttlSeconds());
    }
}
