package com.example.mkagent.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等键派生（纯函数，不启动 Spring、不连库）。
 *
 * 三条性质决定这个键能不能用：
 * 1. **稳定**：同样的调用必须算出同样的键，否则幂等失效；
 * 2. **区分**：不同参数 / 不同任务必须算出不同的键，
 *    否则用户隔天说的"加这首歌"会被当成重复请求吞掉；
 * 3. **不依赖格式**：JSON 键顺序、空白、数字写法不能影响结果，
 *    否则同一意图会因为序列化差异被算成两次。
 */
class IdempotencyKeysTest {

    private static final String USER = "music-user-1";

    private static final UUID RUN = UUID.fromString(
            "11111111-2222-3333-4444-555555555555");

    private static final String TOOL = "add_songs_to_playlist";

    @Test
    @DisplayName("同一调用 → 同一键")
    void sameCallProducesSameKey() {
        String args = "{\"playlistId\":2001,\"songIds\":[1001,1002]}";

        assertEquals(
                IdempotencyKeys.derive(USER, RUN, TOOL, args),
                IdempotencyKeys.derive(USER, RUN, TOOL, args)
        );
    }

    @Test
    @DisplayName("参数 JSON 键顺序不同 → 同一键")
    void keyOrderDoesNotMatter() {
        String a = "{\"playlistId\":2001,\"songIds\":[1001]}";
        String b = "{\"songIds\":[1001],\"playlistId\":2001}";

        assertEquals(
                IdempotencyKeys.derive(USER, RUN, TOOL, a),
                IdempotencyKeys.derive(USER, RUN, TOOL, b)
        );
    }

    @Test
    @DisplayName("空白与换行不同 → 同一键")
    void whitespaceDoesNotMatter() {
        String a = "{\"playlistId\":2001,\"songIds\":[1001]}";
        String b = "{ \"playlistId\" : 2001 ,\n  \"songIds\" : [ 1001 ] }";

        assertEquals(
                IdempotencyKeys.derive(USER, RUN, TOOL, a),
                IdempotencyKeys.derive(USER, RUN, TOOL, b)
        );
    }

    @Test
    @DisplayName("数字写法不同（1 / 1.0 / 1e0）→ 同一键")
    void numberRepresentationIsNormalized() {
        String a = "{\"limit\":1}";
        String b = "{\"limit\":1.0}";
        String c = "{\"limit\":1e0}";
        String d = "{\"limit\":1.000}";

        String base = IdempotencyKeys.derive(USER, RUN, TOOL, a);
        assertEquals(base, IdempotencyKeys.derive(USER, RUN, TOOL, b));
        assertEquals(base, IdempotencyKeys.derive(USER, RUN, TOOL, c));
        assertEquals(base, IdempotencyKeys.derive(USER, RUN, TOOL, d));

        // 真正不同的小数不能被归一掉。
        assertNotEquals(base,
                IdempotencyKeys.derive(USER, RUN, TOOL, "{\"limit\":1.5}"));
    }

    @Test
    @DisplayName("数组顺序不同 → 不同键（顺序可能改变语义）")
    void arrayOrderMatters() {
        String a = "{\"songIds\":[1001,1002]}";
        String b = "{\"songIds\":[1002,1001]}";

        assertNotEquals(
                IdempotencyKeys.derive(USER, RUN, TOOL, a),
                IdempotencyKeys.derive(USER, RUN, TOOL, b),
                "数组顺序决定 position，不能排序"
        );
    }

    @Test
    @DisplayName("参数不同 → 不同键")
    void differentArgsProduceDifferentKeys() {
        assertNotEquals(
                IdempotencyKeys.derive(USER, RUN, TOOL,
                        "{\"playlistId\":2001,\"songIds\":[1001]}"),
                IdempotencyKeys.derive(USER, RUN, TOOL,
                        "{\"playlistId\":2001,\"songIds\":[1002]}")
        );
    }

    @Test
    @DisplayName("不同 runId → 不同键（跨任务不会被回放）")
    void differentRunProducesDifferentKey() {
        String args = "{\"playlistId\":2001,\"songIds\":[1001]}";

        assertNotEquals(
                IdempotencyKeys.derive(USER, RUN, TOOL, args),
                IdempotencyKeys.derive(USER, UUID.randomUUID(), TOOL, args)
        );
    }

    @Test
    @DisplayName("不同用户 / 不同工具 → 不同键")
    void userAndToolParticipateInKey() {
        String args = "{\"playlistId\":2001,\"songIds\":[1001]}";
        String base = IdempotencyKeys.derive(USER, RUN, TOOL, args);

        assertNotEquals(base,
                IdempotencyKeys.derive("music-user-2", RUN, TOOL, args));
        assertNotEquals(base,
                IdempotencyKeys.derive(USER, RUN, "create_playlist", args));
    }

    @Test
    @DisplayName("无参数工具：null 与 {} 等价")
    void nullArgsEqualEmptyObject() {
        assertEquals(
                IdempotencyKeys.derive(USER, RUN, "get_user_playlists", null),
                IdempotencyKeys.derive(USER, RUN, "get_user_playlists", "{}")
        );
        assertEquals(
                IdempotencyKeys.derive(USER, RUN, "get_user_playlists", "  "),
                IdempotencyKeys.derive(USER, RUN, "get_user_playlists", "{}")
        );
    }

    @Test
    @DisplayName("键是 64 位小写十六进制")
    void keyIsSha256Hex() {
        String key = IdempotencyKeys.derive(USER, RUN, TOOL, "{}");

        assertEquals(64, key.length());
        assertTrue(key.matches("[0-9a-f]{64}"), key);
    }

    @Test
    @DisplayName("缺少必填要素 → 抛异常（不静默生成一个可能碰撞的键）")
    void missingInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.derive(null, RUN, TOOL, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.derive("  ", RUN, TOOL, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.derive(USER, null, TOOL, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.derive(USER, RUN, "", "{}"));
    }

    @Test
    @DisplayName("参数不是合法 JSON → 抛异常（不回退成原始字符串）")
    void malformedArgsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.derive(USER, RUN, TOOL, "{not json"));
    }
}
