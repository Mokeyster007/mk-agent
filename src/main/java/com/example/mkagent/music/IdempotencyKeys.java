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
 * 写工具幂等键与请求哈希（纯函数，不碰数据库）。
 *
 * <h2>逻辑身份 = 一次模型发起的工具调用</h2>
 *
 * <pre>
 * idempotency_key = sha256("v2|u{len}:{userId}|r{runId}|s{step}|c{len}:{toolCallId}")
 * request_hash    = sha256("{toolName}\n{canonicalJson(args)}")
 * </pre>
 *
 * 为什么身份是 {@code toolCallId} 而不是参数：
 * <ul>
 *   <li>{@code toolCallId} 是"同一次逻辑工具调用"的既有标识，由模型在
 *       assistant 消息里给出，{@code ToolCallAgent} 与检查点都持有它；</li>
 *   <li>参数相同的两次**独立**调用（用户先后说了两次"加这首歌"）
 *       必须是两次操作，用参数做身份会把它们静默合并；</li>
 *   <li>检查点保存并在恢复时**原样重建** id，因此跨恢复、跨 epoch 身份不变。</li>
 * </ul>
 *
 * 为什么还要带 {@code step}：{@code toolCallId} 只在一次模型响应内唯一，
 * 模型跨轮复用同一个 id 时，靠 step 把两次调用分开。
 * 暂停轮在恢复后 step 不变（恢复路径不 {@code nextStep}），所以身份仍然稳定。
 *
 * 为什么不带 {@code execution_epoch}：epoch 每次恢复 +1，
 * 把它放进身份会让"同一次调用跨恢复"变成两条记录，幂等直接失效。
 * epoch 的职责是"这次执行有没有资格写"，由执行前的代次校验负责，不参与身份。
 *
 * <h2>编码为什么要长度前缀</h2>
 *
 * {@code userId} 与 {@code toolCallId} 都是自由文本，直接拼接时
 * {@code ("ab","c")} 与 {@code ("a","bc")} 会算出同一个字符串。
 * 这里对这两个字段写 UTF-8 字节长度，其余字段是 UUID / 整数，天然无歧义。
 *
 * <h2>参数哈希只做一致性校验</h2>
 *
 * {@link #requestHash} 含工具名与规范化参数，写入记录后用于比对：
 * 同一身份但工具名或参数不同 → 冲突失败关闭，绝不复用旧结果。
 * 它**不是**身份，不能单独当键用。
 *
 * 参数规范化（{@link #canonicalJson}）：
 * 1. 对象键排序（JSON 对象的键顺序无语义）；
 * 2. 去掉所有格式空白；
 * 3. 数字统一表示：{@code 1}、{@code 1.0}、{@code 1e0} 都归一成 {@code 1}；
 * 4. **数组保持原顺序**——数组顺序可能改变语义（"先加 A 再加 B"），
 *    排序会让不同的调用算出同一个哈希。
 */
public final class IdempotencyKeys {

    /**
     * 键格式版本。改了派生规则必须换版本，否则新旧记录会互相误判为"同一次调用"。
     */
    private static final String KEY_VERSION = "v2";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IdempotencyKeys() {
    }

    /**
     * 派生一次逻辑工具调用的幂等键。
     *
     * @param userId     服务端固化的用户标识，不能为空
     * @param runId      当前任务 ID，不能为空
     * @param step       本次调用所属的步号（暂停轮在恢复后不变）
     * @param toolCallId 模型给出的工具调用 ID，不能为空
     * @return 64 位小写十六进制 sha256
     */
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

    /**
     * 请求哈希：工具名 + 规范化参数。
     *
     * 用于"同一身份、不同请求"的冲突检测，不参与身份派生。
     *
     * @param toolName 工具名，不能为空
     * @param argsJson 工具参数 JSON；null / 空白按 {@code {}} 处理
     */
    public static String requestHash(String toolName, String argsJson) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("请求哈希缺少 toolName");
        }
        return sha256Hex(toolName.trim() + "\n" + canonicalJson(argsJson));
    }

    /**
     * 参数规范化：对象键排序、去空白、数字统一表示。
     *
     * <p>public 是因为工具调用计划（{@code ToolCallPlan}）也要用同一套
     * 规范化来比对"回调拿到的参数"与"计划里登记的参数"——
     * 两处各写一份迟早会不一致，那正是"身份对不上"最难查的来源。
     *
     * @param argsJson 原始参数 JSON；null / 空白返回 {@code {}}
     */
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
            /*
             * 参数不是合法 JSON 属于编程错误（工具参数由框架序列化）。
             * 这里不静默回退成原始字符串：回退会让 "1.0" 与 "1"
             * 算出不同的哈希，幂等形同虚设，而且问题会被藏起来。
             */
            throw new IllegalArgumentException("工具参数不是合法 JSON", e);
        }
    }

    /**
     * {@code {utf8字节数}:{原文}}，消除分隔符歧义。
     */
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
