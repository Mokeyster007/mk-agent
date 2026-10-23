package com.example.mkagent.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文档片段的稳定 ID 生成规则。
 *
 * 形如：{@code AI学习常见问题和回答 - 入门篇.md#3-9f2c1a4b7e6d0c33}
 * 由三部分组成：来源文件名 + 片段序号 + 内容 SHA-256 前 16 位。
 *
 * 为什么需要稳定 ID：
 * 1. SimpleVectorStore 的 add 是"按 ID upsert"，但每次都会重新调用
 *    EmbeddingModel#embed —— 仅靠 upsert 并不能省钱，必须先在入库前比对 ID；
 * 2. 内容哈希进 ID 后，"内容变了"天然表现为"ID 变了"，
 *    旧 ID 会被识别为过期并删除，新 ID 重新入库；
 * 3. 同一份文档重复加载得到同一批 ID，因此第二次启动可以整批跳过。
 */
public final class RagChunkIds {

    /**
     * 内容哈希截断长度：16 位十六进制（64 bit），
     * 对本项目这种文档规模碰撞概率可忽略。
     */
    private static final int HASH_LENGTH = 16;

    private RagChunkIds() {
    }

    /**
     * 生成片段稳定 ID。
     *
     * @param source 来源标识（通常为文件名）
     * @param index  该来源内的片段序号（从 0 开始）
     * @param text   片段正文
     */
    public static String of(String source, int index, String text) {
        String safeSource = (source == null || source.isBlank())
                ? "unknown"
                : source;
        return safeSource + "#" + index + "-" + contentHash(text);
    }

    /**
     * 片段正文的 SHA-256 前 16 位（小写十六进制）。
     */
    public static String contentHash(String text) {
        byte[] digest = sha256(text == null ? "" : text);
        StringBuilder hex = new StringBuilder(HASH_LENGTH);

        for (byte b : digest) {
            if (hex.length() >= HASH_LENGTH) {
                break;
            }
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }

        return hex.substring(0, HASH_LENGTH);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然内置 SHA-256，这里只做兜底，不把异常抛给业务链路。
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
