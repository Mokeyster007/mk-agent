package com.example.mkagent.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 审批链路用到的哈希工具（纯函数）。
 *
 * 统一放一处，避免"参数哈希"和"整轮哈希"各写一份、
 * 一处加了分隔符另一处没加，导致同一组参数算出两个值。
 */
public final class ApprovalHashes {

    private ApprovalHashes() {
    }

    /**
     * SHA-256，64 位小写十六进制。
     */
    public static String sha256Hex(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (material == null ? "" : material).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然提供 SHA-256，走到这里说明运行环境被裁剪过。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 单个工具调用的参数哈希。
     *
     * 直接对参数原文取哈希：执行时用的就是这段原文，
     * 哈希相同的含义是"字节完全一致"，不存在规范化后碰撞的歧义。
     */
    public static String argsHash(String arguments) {
        return sha256Hex(arguments == null ? "" : arguments);
    }

    /**
     * 整轮 pending 工具调用的绑定哈希（按顺序）。
     *
     * 顺序参与哈希：`[A, B]` 与 `[B, A]` 是两组不同的操作，
     * 不能因为排序被当成同一组批准。
     */
    public static String roundHash(List<ApprovalCheckpoint.PendingToolCall> calls) {
        StringBuilder material = new StringBuilder();
        for (ApprovalCheckpoint.PendingToolCall call : calls) {
            material.append(call.id()).append('|')
                    .append(call.name()).append('|')
                    .append(call.arguments() == null ? "" : call.arguments())
                    .append('\n');
        }
        return sha256Hex(material.toString());
    }

    /**
     * 白名单哈希：工具名排序后取哈希。
     *
     * 恢复时用它确认"暂停时能调用的工具"与"现在能调用的工具"是同一组，
     * 防止运维中途删了某个工具、旧检查点却仍按旧白名单执行。
     */
    public static String whitelistHash(List<String> sortedToolNames) {
        return sha256Hex(String.join("\n", sortedToolNames));
    }
}
