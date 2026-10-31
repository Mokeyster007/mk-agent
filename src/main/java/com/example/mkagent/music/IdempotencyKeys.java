package com.example.mkagent.music;

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
 * 写工具幂等键（纯函数，不碰数据库）。
 *
 * 键 = {@code sha256(userId | runId | toolName | canonicalJson(args))}
 *
 * 为什么带 runId（而不是早期方案里的 step）：
 * <ul>
 *   <li><b>同一任务内</b>（含跨步骤）重复调用同一工具、同一参数 → 同一个键 → 命中回放，
 *       模型重复请求不会重复写库；</li>
 *   <li><b>不同任务</b>的 runId 不同 → 键不同 → 用户隔天再说"加这首歌"仍然会真的执行，
 *       不会被静默回放；</li>
 *   <li>不需要 TTL：幂等记录的语义边界就是"一次任务"，
 *       任务结束即失效，天然没有过期清理问题；</li>
 *   <li>M3 的审批恢复沿用同一 runId，恢复后的执行仍然落在同一条幂等记录上。</li>
 * </ul>
 *
 * 为什么带 userId：幂等表的物理主键是 {@code (user_id, idempotency_key)}，
 * 但键本身也带上用户，避免"两个用户在同一次 runId 下"这种不可能但
 * 一旦发生就很难查的碰撞。
 *
 * 语义层面的重复（同一个人两次要求建同名歌单）**不靠这个键**，
 * 靠业务唯一约束兜底：{@code uk_music_playlist_user_name}、
 * {@code music_playlist_song} 的主键。
 *
 * 参数规范化（{@link #canonicalJson}）：
 * 1. 对象键排序（JSON 对象的键顺序无语义）；
 * 2. 去掉所有格式空白；
 * 3. 数字统一表示：{@code 1}、{@code 1.0}、{@code 1e0} 都归一成 {@code 1}；
 * 4. **数组保持原顺序**——数组顺序可能改变语义（"先加 A 再加 B"），
 *    排序会让不同的调用算出同一个键。
 */
public final class IdempotencyKeys {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IdempotencyKeys() {
    }

    /**
     * 派生幂等键。
     *
     * @param userId   服务端固化的用户标识，不能为空
     * @param runId    当前任务 ID（{@code AgentRunContext.getRunId()}），不能为空
     * @param toolName 工具名，不能为空
     * @param argsJson 工具参数 JSON；null / 空白按 {@code {}} 处理
     * @return 64 位小写十六进制 sha256
     */
    public static String derive(
            String userId,
            UUID runId,
            String toolName,
            String argsJson
    ) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("幂等键缺少 userId");
        }
        if (runId == null) {
            throw new IllegalArgumentException("幂等键缺少 runId");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("幂等键缺少 toolName");
        }

        String material = userId.trim()
                + "|" + runId
                + "|" + toolName.trim()
                + "|" + canonicalJson(argsJson);

        return sha256Hex(material);
    }

    /**
     * 参数规范化：对象键排序、去空白、数字统一表示。
     *
     * @param argsJson 原始参数 JSON；null / 空白返回 {@code {}}
     */
    static String canonicalJson(String argsJson) {
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
            /*
             * 参数不是合法 JSON 属于编程错误（工具参数由框架序列化）。
             * 这里不静默回退成原始字符串：回退会让 "1.0" 与 "1"
             * 算出不同的键，幂等形同虚设，而且问题会被藏起来。
             */
            throw new IllegalArgumentException("工具参数不是合法 JSON", e);
        }
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

    /**
     * 数字归一：整数值统一成 BigInteger，其余用去掉尾零的 BigDecimal。
     */
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
            // SHA-256 是 JLS 要求必须提供的算法，不可能走到这里。
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
