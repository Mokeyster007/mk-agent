package com.example.mkagent.playback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 媒体地址签名。
 *
 * <p>为什么必须签名而不是"带个 songId 就能取"：
 * {@code <audio src>} **不能**附加业务接口的自定义身份头（X-User-Id 加不上去），
 * 所以媒体请求无法走请求头鉴权。可选方案只有两种：
 * <ol>
 *   <li>把文件放进公开静态目录——受保护文件等于公开，直接排除；</li>
 *   <li>把"身份 + 曲目 + 过期时间"签进 URL，服务端验签后再读文件。</li>
 * </ol>
 * 这里用第二种：签名覆盖 songId、userId、过期时间，任何一项被改都会验签失败，
 * 因此不能靠改 URL 换别人的歌，也不能无限期复用旧地址。
 *
 * <p>令牌格式：{@code v1.<base64url(payload)>.<base64url(hmac)>}，
 * payload 是 JSON（{@code {"s":songId,"u":userId,"e":epochSecond}}）。
 * 用 JSON 而不是分隔符拼接：userId 是外部可控字符串，
 * 含分隔符时拼接方案会被解析成不同的字段。
 *
 * <p>密钥来源：{@code mkagent.playback.signing-secret}。
 * 没配置时**随机生成**（进程内有效）：重启后旧地址全部失效，
 * 这对本地演示是可接受的，而且比"写死一个默认密钥"安全得多。
 * 多实例部署必须显式配置同一个密钥，否则 A 实例签的地址在 B 实例验不过。
 */
@Component
public class PlaybackSigner {

    private static final Logger log = LoggerFactory.getLogger(PlaybackSigner.class);

    private static final String VERSION = "v1";

    private static final String ALGORITHM = "HmacSHA256";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] secret;

    private final long ttlSeconds;

    public PlaybackSigner(
            @Value("${mkagent.playback.signing-secret:}") String configuredSecret,
            @Value("${mkagent.playback.url-ttl-seconds:300}") long ttlSeconds
    ) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
            log.warn("未配置 mkagent.playback.signing-secret，已生成进程内随机密钥："
                    + "重启后旧的媒体地址会失效（多实例部署必须显式配置同一密钥）");
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }

        // 上限一小时：再长就失去"短期地址"的意义
        this.ttlSeconds = Math.max(30, Math.min(ttlSeconds, 3600));
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 为某用户签一个某首歌的媒体地址令牌。
     */
    public IssuedToken issue(long songId, String userId) {
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("s", songId);
        payload.put("u", userId);
        payload.put("e", expiresAt);

        String encodedPayload = base64Url(payload.toString().getBytes(StandardCharsets.UTF_8));
        String signature = base64Url(hmac(encodedPayload));

        return new IssuedToken(VERSION + "." + encodedPayload + "." + signature, expiresAt);
    }

    /**
     * 验签并解出声明。
     *
     * <p>任何异常都返回 empty（失败关闭）：过期、签名不符、格式错误、
     * 版本不认识，对调用方都是同一件事——这个地址不能用。
     * 不区分原因是为了不给探测者额外信息。
     */
    public Optional<Claims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }

        String encodedPayload = parts[1];
        byte[] expected = hmac(encodedPayload);

        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        // 定长比较：避免按字节短路泄露签名前缀
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }

        try {
            JsonNode payload = MAPPER.readTree(
                    new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8));

            long songId = payload.path("s").asLong(-1);
            String userId = payload.path("u").asText(null);
            long expiresAt = payload.path("e").asLong(0);

            if (songId <= 0 || userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            if (expiresAt <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }

            return Optional.of(new Claims(songId, userId, expiresAt));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            /*
             * 算法或密钥不可用属于装配错误，不该静默降级成"没有签名"。
             * 抛出去让调用方 500，比签出一个谁都能伪造的地址安全。
             */
            throw new IllegalStateException("媒体地址签名不可用", e);
        }
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 签发的令牌 + 过期时间（前端据此在过期前重新解析）。 */
    public record IssuedToken(String token, long expiresAtEpochSecond) {
    }

    /** 验签后的声明。 */
    public record Claims(long songId, String userId, long expiresAtEpochSecond) {
    }
}
