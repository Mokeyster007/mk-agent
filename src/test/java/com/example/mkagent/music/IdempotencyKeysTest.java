package com.example.mkagent.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等身份与请求哈希（纯函数，不启动 Spring、不连库）。
 *
 * 四条性质决定这套派生能不能用：
 * 1. **稳定**：同一次逻辑调用跨恢复、跨 epoch 必须算出同一个键；
 * 2. **区分**：不同用户 / 不同任务 / 不同调用 ID 必须算出不同的键，
 *    尤其"同名同参、不同 ID"的两次独立调用不能被合并；
 * 3. **无歧义**：自由文本字段用长度前缀编码，拼接不产生碰撞；
 * 4. **哈希只做一致性校验**：参数变化不改变身份，但会让请求哈希不同，
 *    从而在回放时被识别为冲突。
 */
class IdempotencyKeysTest {

    private static final String USER = "music-user-1";

    private static final UUID RUN = UUID.fromString(
            "11111111-2222-3333-4444-555555555555");

    private static final String TOOL = "add_songs_to_playlist";

    private static final String CALL_ID = "call-abc-1";

    // ==================== 1. 稳定性 ====================

    @Test
    @DisplayName("同一逻辑调用 → 同一键")
    void sameCallProducesSameKey() {
        assertEquals(
                IdempotencyKeys.callKey(USER, RUN, 3, CALL_ID),
                IdempotencyKeys.callKey(USER, RUN, 3, CALL_ID)
        );
    }

    @Test
    @DisplayName("键是 64 位十六进制 sha256，可直接存 VARCHAR(128)")
    void keyShapeIsStable() {
        String key = IdempotencyKeys.callKey(USER, RUN, 3, CALL_ID);
        assertEquals(64, key.length(), "sha256 十六进制长度");
        assertTrue(key.matches("[0-9a-f]{64}"), key);
    }

    // ==================== 2. 区分 ====================

    @Test
    @DisplayName("同名同参、不同调用 ID → 不同的键（不能被合并）")
    void differentCallIdsNeverMerge() {
        assertNotEquals(
                IdempotencyKeys.callKey(USER, RUN, 3, "call-1"),
                IdempotencyKeys.callKey(USER, RUN, 3, "call-2")
        );
    }

    @Test
    @DisplayName("不同步号 → 不同的键（模型跨轮复用同一个 ID 时靠它分开）")
    void differentStepProducesDifferentKey() {
        assertNotEquals(
                IdempotencyKeys.callKey(USER, RUN, 1, CALL_ID),
                IdempotencyKeys.callKey(USER, RUN, 2, CALL_ID)
        );
    }

    @Test
    @DisplayName("不同任务 → 不同的键（用户隔天再说一次会真的执行）")
    void differentRunProducesDifferentKey() {
        assertNotEquals(
                IdempotencyKeys.callKey(USER, RUN, 1, CALL_ID),
                IdempotencyKeys.callKey(USER, UUID.randomUUID(), 1, CALL_ID)
        );
    }

    @Test
    @DisplayName("不同用户 → 不同的键")
    void differentUserProducesDifferentKey() {
        assertNotEquals(
                IdempotencyKeys.callKey(USER, RUN, 1, CALL_ID),
                IdempotencyKeys.callKey("music-user-2", RUN, 1, CALL_ID)
        );
    }

    // ==================== 3. 编码无歧义 ====================

    @Test
    @DisplayName("长度前缀消除拼接歧义：(ab,c) 与 (a,bc) 不得碰撞")
    void lengthPrefixAvoidsAmbiguity() {
        assertNotEquals(
                IdempotencyKeys.callKey("ab", RUN, 1, "c"),
                IdempotencyKeys.callKey("a", RUN, 1, "bc")
        );
    }

    @Test
    @DisplayName("含分隔符的自由文本也不会串味")
    void separatorCharactersAreSafe() {
        assertNotEquals(
                IdempotencyKeys.callKey("u|1", RUN, 1, CALL_ID),
                IdempotencyKeys.callKey("u", RUN, 1, "1|" + CALL_ID)
        );
    }

    // ==================== 4. 请求哈希 ====================

    @Test
    @DisplayName("参数 JSON 键顺序不同 → 同一请求哈希")
    void keyOrderDoesNotMatterForRequestHash() {
        assertEquals(
                IdempotencyKeys.requestHash(TOOL, "{\"playlistId\":2001,\"songIds\":[1001]}"),
                IdempotencyKeys.requestHash(TOOL, "{\"songIds\":[1001],\"playlistId\":2001}")
        );
    }

    @Test
    @DisplayName("空白与数字写法不同 → 同一请求哈希")
    void formattingDoesNotMatterForRequestHash() {
        String base = IdempotencyKeys.requestHash(TOOL, "{\"limit\":1,\"x\":[1,2]}");

        assertEquals(base, IdempotencyKeys.requestHash(
                TOOL, "{ \"limit\" : 1 ,\n  \"x\" : [ 1 , 2 ] }"));
        assertEquals(base, IdempotencyKeys.requestHash(
                TOOL, "{\"limit\":1.0,\"x\":[1,2]}"));
        assertEquals(base, IdempotencyKeys.requestHash(
                TOOL, "{\"limit\":1e0,\"x\":[1,2]}"));
    }

    @Test
    @DisplayName("数组顺序不同 → 不同请求哈希（顺序可能改变语义）")
    void arrayOrderMatters() {
        assertNotEquals(
                IdempotencyKeys.requestHash(TOOL, "{\"songIds\":[1001,1002]}"),
                IdempotencyKeys.requestHash(TOOL, "{\"songIds\":[1002,1001]}")
        );
    }

    @Test
    @DisplayName("参数不同 / 工具名不同 → 不同请求哈希")
    void differentRequestProducesDifferentHash() {
        String base = IdempotencyKeys.requestHash(TOOL, "{\"playlistId\":2001}");

        assertNotEquals(base,
                IdempotencyKeys.requestHash(TOOL, "{\"playlistId\":2002}"));
        assertNotEquals(base,
                IdempotencyKeys.requestHash("create_playlist", "{\"playlistId\":2001}"));
    }

    @Test
    @DisplayName("null / 空白参数按 {} 处理")
    void nullArgumentsTreatedAsEmptyObject() {
        String empty = IdempotencyKeys.requestHash(TOOL, "{}");

        assertEquals(empty, IdempotencyKeys.requestHash(TOOL, null));
        assertEquals(empty, IdempotencyKeys.requestHash(TOOL, "   "));
    }

    // ==================== 5. 失败关闭 ====================

    @Test
    @DisplayName("缺少关键输入 → 直接抛异常，不产出'半个键'")
    void missingInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.callKey(null, RUN, 1, CALL_ID));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.callKey("  ", RUN, 1, CALL_ID));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.callKey(USER, null, 1, CALL_ID));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.callKey(USER, RUN, 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.callKey(USER, RUN, 1, "   "));

        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.requestHash(null, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.requestHash("  ", "{}"));
    }

    @Test
    @DisplayName("参数不是合法 JSON → 抛异常，不回退成原始字符串")
    void invalidJsonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.requestHash(TOOL, "{not json"));
    }
}
