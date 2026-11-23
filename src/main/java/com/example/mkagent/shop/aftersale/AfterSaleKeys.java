package com.example.mkagent.shop.aftersale;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 普通表单的幂等身份（纯函数，不碰数据库）。
 *
 * <h2>两条写路径，两套身份</h2>
 *
 * <table border="1">
 *   <caption>幂等身份来源</caption>
 *   <tr><th>路径</th><th>身份</th><th>由谁保证</th></tr>
 *   <tr>
 *     <td>普通表单</td>
 *     <td>{@code sha256("v1|aftersale-form|u{len}:{userId}|k{len}:{clientRequestId}")}</td>
 *     <td>前端在打开表单时生成一个 {@code clientRequestId}，提交期间不变</td>
 *   </tr>
 *   <tr>
 *     <td>Agent 写工具</td>
 *     <td>{@code IdempotencyKeys.callKey(userId, runId, step, toolCallId)}</td>
 *     <td>框架既有的写工具幂等机制（检查点原样重建，跨恢复不变）</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么表单不用"参数哈希"当身份</b>：
 * 用户先后两次真的各退 1 件，参数完全一样，但那是两笔申请。
 * 用参数当键会把第二笔静默合并掉——用户以为退了两件，系统只记了一件。
 * 因此身份必须来自"这一次提交动作"，而不是"提交了什么"。
 *
 * <p><b>为什么 Agent 侧不复用这个键</b>：
 * 工具回调拿不到 {@code clientRequestId}，而 {@code runId + step + toolCallId}
 * 才是"同一次逻辑工具调用"的既有标识。硬把两条路径塞进同一个键格式，
 * 会让"同一次确认重放"与"两次独立申请"变得无法区分。
 *
 * <h2>编码为什么带长度前缀</h2>
 *
 * {@code userId} 与 {@code clientRequestId} 都是自由文本，
 * 直接拼接时 {@code ("ab","c")} 与 {@code ("a","bc")} 会算出同一个串。
 * 长度前缀消除这种歧义。
 */
public final class AfterSaleKeys {

    /** 键格式版本：改了派生规则必须换版本，否则新旧记录会互相误判。 */
    private static final String KEY_VERSION = "v1";

    /** 表单键前缀，避免与 Agent 侧的键空间混淆。 */
    private static final String FORM_PREFIX = "aftersale-form";

    /** 收货键前缀（B2）：与买家提交的键空间分开。 */
    private static final String RECEIPT_PREFIX = "aftersale-receipt";

    /** {@code clientRequestId} 允许的字符集（UUID / nanoid / 自增序号都能用）。 */
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");

    private AfterSaleKeys() {
    }

    /**
     * 派生表单提交的幂等键。
     *
     * @param userId          服务端解析出的买家身份
     * @param clientRequestId 前端生成的提交标识
     * @throws IllegalArgumentException 任一为空或 {@code clientRequestId} 格式非法
     */
    public static String formKey(String userId, String clientRequestId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("幂等键缺少 userId");
        }
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("幂等键缺少 clientRequestId");
        }
        if (!CLIENT_ID_PATTERN.matcher(clientRequestId).matches()) {
            throw new IllegalArgumentException(
                    "clientRequestId 必须是 8~64 位字母、数字或 _ . : - 组成的字符串");
        }

        String material = KEY_VERSION
                + "|" + FORM_PREFIX
                + "|u" + lengthPrefix(userId)
                + "|k" + lengthPrefix(clientRequestId);

        return sha256Hex(material);
    }

    /**
     * 请求哈希：工具语义 + 规范化后的业务参数。
     *
     * <p>只用于"同键不同参数"的冲突检测，**不参与身份派生**。
     * 命中同一个键但哈希不同，说明客户端复用了一个提交标识却改了内容，
     * 这是 bug，必须明确拒绝（409 {@code IDEMPOTENCY_KEY_REUSED}），
     * 不能静默返回上一单——那会让用户以为改的内容生效了。
     *
     * @param orderNo   订单号
     * @param quantity  数量
     * @param reason    归一化后的原因
     */
    public static String requestHash(String orderNo, int quantity, String reason) {
        String material = "submit_return"
                + "|o" + lengthPrefix(orderNo == null ? "" : orderNo)
                + "|q" + quantity
                + "|r" + lengthPrefix(reason == null ? "" : reason);
        return sha256Hex(material);
    }

    /**
     * 派生**收货动作**的幂等键（B2）。
     *
     * <p>与表单键同一套纪律：身份来自"这一次操作"，而不是"操作了什么"。
     * 不同的合法分批收货用不同的 {@code clientRequestId}，
     * 因此它们各自入库；同一次点击的重试则命中同一个键、返回同一条记录。
     *
     * @param operatorId      服务端解析出的客服身份
     * @param clientRequestId 前端在打开收货面板时生成的标识
     */
    public static String receiptKey(String operatorId, String clientRequestId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("幂等键缺少 operatorId");
        }
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("幂等键缺少 clientRequestId");
        }
        if (!CLIENT_ID_PATTERN.matcher(clientRequestId).matches()) {
            throw new IllegalArgumentException(
                    "clientRequestId 必须是 8~64 位字母、数字或 _ . : - 组成的字符串");
        }

        String material = KEY_VERSION
                + "|" + RECEIPT_PREFIX
                + "|u" + lengthPrefix(operatorId)
                + "|k" + lengthPrefix(clientRequestId);

        return sha256Hex(material);
    }

    /**
     * 收货请求哈希：同键不同参数必须被拒绝。
     *
     * <p>哈希里包含退货单号与三个数量：如果同一次"收货动作"被复用于另一张单，
     * 或改了合格/损坏的拆分，静默返回上一条记录会让操作员以为改动生效了。
     */
    public static String receiptRequestHash(
            String returnNo,
            int receivedQty,
            int qualifiedQty,
            int damagedQty,
            String note
    ) {
        String material = "receive_return"
                + "|r" + lengthPrefix(returnNo == null ? "" : returnNo)
                + "|a" + receivedQty
                + "|q" + qualifiedQty
                + "|d" + damagedQty
                + "|n" + lengthPrefix(note == null ? "" : note);
        return sha256Hex(material);
    }

    /** {@code {utf8字节数}:{原文}}，消除分隔符歧义。 */
    private static String lengthPrefix(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }

    private static String sha256Hex(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JLS 要求必须提供的算法，不可能走到这里。
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
