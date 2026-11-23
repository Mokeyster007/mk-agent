package com.example.mkagent.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 写工具幂等键与请求哈希（纯函数，不碰数据库）。
 *
 * <p>逻辑身份 = 一次模型发起的工具调用：
 * <pre>
 * idempotency_key = sha256("v2|u{len}:{userId}|r{runId}|s{step}|c{len}:{toolCallId}")
 * request_hash    = sha256("{toolName}\n{canonicalJson(args)}")
 * </pre>
 */
public final class IdempotencyKeys {

    private static final String KEY_VERSION = "v2";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IdempotencyKeys() {
    }

    public static String callKey(
            String userId,
            UUID runId,
            int step,
            String toolCallId
    ) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("幂等键缺少 userId");
        }
        if (runId == null) {
            throw new IllegalArgumentException("幂等键缺少 runId");
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("幂等键缺少 toolCallId");
        }

        String material = KEY_VERSION
                + "|u" + lengthPrefix(userId)
                + "|r" + runId
                + "|s" + step
                + "|c" + lengthPrefix(toolCallId);

        return sha256Hex(material);
    }

    public static String requestHash(String toolName, String argsJson) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("请求哈希缺少 toolName");
        }
        return sha256Hex(toolName.trim() + "\n" + canonicalJson(argsJson));
    }

    public static String canonicalJson(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "{}";
        }

        try {
            JsonNode root = MAPPER.readTree(argsJson);
            if (root == null || root.isNull()) {
                return "{}";
            }
            return MAPPER.writeValueAsString(canonicalize(root));
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON", e);
        }
    }

    private static String lengthPrefix(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> names = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);

            ObjectNode sorted = MAPPER.createObjectNode();
            for (String name : names) {
                sorted.set(name, canonicalize(objectNode.get(name)));
            }
            return sorted;
        }

        if (node instanceof ArrayNode arrayNode) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode child : arrayNode) {
                copy.add(canonicalize(child));
            }
            return copy;
        }

        if (node.isNumber()) {
            return canonicalNumber(node);
        }

        return node;
    }

    private static JsonNode canonicalNumber(JsonNode node) {
        BigDecimal value = node.decimalValue().stripTrailingZeros();

        if (value.scale() <= 0) {
            BigInteger integer = value.toBigIntegerExact();
            return BigIntegerNode.valueOf(integer);
        }
        return DecimalNode.valueOf(value);
    }

    private static String sha256Hex(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
