package com.example.mkagent.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等身份与请求哈希单元测试（纯函数，不启动 Spring、不连库）。
 */
class IdempotencyKeysTest {

    private static final String USER = "shop-user-1";

    private static final UUID RUN = UUID.fromString(
            "11111111-2222-3333-4444-555555555555");

    private static final String TOOL = "submit_checkout_order";

    private static final String CALL_ID = "call-abc-1";

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

    @Test
    @DisplayName("同名同参、不同调用 ID → 不同的键（不能被合并）")
    void differentCallIdsNeverMerge() {
        assertNotEquals(
                IdempotencyKeys.callKey(USER, RUN, 3, "call-1"),
                IdempotencyKeys.callKey(USER, RUN, 3, "call-2")
        );
    }

    @Test
    @DisplayName("不同步号 → 不同的键")
    void differentStepProducesDifferentKey() {
        assertNotEquals(
                IdempotencyKeys.callKey(USER, RUN, 1, CALL_ID),
                IdempotencyKeys.callKey(USER, RUN, 2, CALL_ID)
        );
    }

    @Test
    @DisplayName("不同任务 → 不同的键")
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
                IdempotencyKeys.callKey("shop-user-2", RUN, 1, CALL_ID)
        );
    }

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

    @Test
    @DisplayName("参数 JSON 键顺序不同 → 同一请求哈希")
    void keyOrderDoesNotMatterForRequestHash() {
        assertEquals(
                IdempotencyKeys.requestHash(TOOL, "{\"productId\":2004,\"quantity\":1}"),
                IdempotencyKeys.requestHash(TOOL, "{\"quantity\":1,\"productId\":2004}")
        );
    }

    @Test
    @DisplayName("空白与数字写法不同 → 同一请求哈希")
    void formattingDoesNotMatterForRequestHash() {
        String base = IdempotencyKeys.requestHash(TOOL, "{\"quantity\":1,\"ids\":[1,2]}");

        assertEquals(base, IdempotencyKeys.requestHash(
                TOOL, "{ \"quantity\" : 1 ,\n  \"ids\" : [ 1 , 2 ] }"));
        assertEquals(base, IdempotencyKeys.requestHash(
                TOOL, "{\"quantity\":1.0,\"ids\":[1,2]}"));
        assertEquals(base, IdempotencyKeys.requestHash(
                TOOL, "{\"quantity\":1e0,\"ids\":[1,2]}"));
    }

    @Test
    @DisplayName("数组顺序不同 → 不同请求哈希")
    void arrayOrderMatters() {
        assertNotEquals(
                IdempotencyKeys.requestHash(TOOL, "{\"ids\":[2004,2005]}"),
                IdempotencyKeys.requestHash(TOOL, "{\"ids\":[2005,2004]}")
        );
    }

    @Test
    @DisplayName("参数不同 / 工具名不同 → 不同请求哈希")
    void differentRequestProducesDifferentHash() {
        String base = IdempotencyKeys.requestHash(TOOL, "{\"productId\":2004}");

        assertNotEquals(base,
                IdempotencyKeys.requestHash(TOOL, "{\"productId\":2005}"));
        assertNotEquals(base,
                IdempotencyKeys.requestHash("submit_return", "{\"productId\":2004}"));
    }

    @Test
    @DisplayName("null / 空白参数按 {} 处理")
    void nullArgumentsTreatedAsEmptyObject() {
        String empty = IdempotencyKeys.requestHash(TOOL, "{}");

        assertEquals(empty, IdempotencyKeys.requestHash(TOOL, null));
        assertEquals(empty, IdempotencyKeys.requestHash(TOOL, "   "));
    }

    @Test
    @DisplayName("缺少关键输入 → 直接抛异常")
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
    @DisplayName("参数不是合法 JSON → 抛异常")
    void invalidJsonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.requestHash(TOOL, "{not json"));
    }
}
