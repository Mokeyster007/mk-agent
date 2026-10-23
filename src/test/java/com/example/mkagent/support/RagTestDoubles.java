package com.example.mkagent.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG 启动/可用性测试用的最小模型桩。
 *
 * 全部在内存中完成，不联网、不读取任何真实密钥：
 * 1. failingChatModel / failingEmbeddingModel：调用即抛异常，
 *    模拟百炼欠费（Arrearage）或 Embedding 服务不可用；
 * 2. fixedTextChatModel：固定返回一段文本，
 *    用于"模型正常时 RAG 可用"的正向路径；
 * 3. countingEmbeddingModel：固定向量 + 调用计数，
 *    用于证明 lazy 模式在第一次 RAG 调用前没有发生 Embedding。
 */
public final class RagTestDoubles {

    /**
     * 固定向量：全 0.5，任意两个文档的余弦相似度都是 1，
     * 保证相似度阈值（0.5）一定通过。
     */
    private static final float[] FIXED_VECTOR = {
            0.5f, 0.5f, 0.5f, 0.5f
    };

    private RagTestDoubles() {
    }

    /**
     * 调用即失败的对话模型，每次调用都会累加计数器。
     */
    public static ChatModel failingChatModel(AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                throw new IllegalStateException("模拟百炼欠费（Arrearage）");
            }
        };
    }

    /**
     * 固定返回同一段文本的对话模型。
     *
     * 同一段文本既会被查询改写使用，也会被最终回答使用，
     * 因此内容保持"短且无副作用"。
     */
    public static ChatModel fixedTextChatModel(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(text)))
                );
            }
        };
    }

    /**
     * 固定返回关键词文本并计数的对话模型，用于统计富化调用次数。
     */
    public static ChatModel countingChatModel(AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage("关键词A,关键词B")))
                );
            }
        };
    }

    /**
     * 调用即失败的 Embedding 模型。
     */
    public static EmbeddingModel failingEmbeddingModel(AtomicInteger calls) {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("模拟 Embedding 服务不可用");
            }

            @Override
            public float[] embed(Document document) {
                calls.incrementAndGet();
                throw new IllegalStateException("模拟 Embedding 服务不可用");
            }
        };
    }

    /**
     * 前 N 次成功、之后固定失败的 Embedding 模型。
     *
     * 用于模拟"入库到一半 Embedding 服务挂了"：
     * 前 N 次（含维度探针）落盘成功，之后抛异常。
     */
    public static EmbeddingModel failingEmbeddingModelAfter(
            AtomicInteger calls,
            int successCount,
            int dimensions
    ) {
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                calls.incrementAndGet();
                if (calls.get() > successCount) {
                    throw new IllegalStateException("模拟 Embedding 服务中途不可用");
                }

                List<String> instructions = request.getInstructions();
                List<Embedding> embeddings = new ArrayList<>(
                        instructions == null ? 0 : instructions.size()
                );
                if (instructions != null) {
                    for (int i = 0; i < instructions.size(); i++) {
                        embeddings.add(new Embedding(vector(dimensions), i));
                    }
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                calls.incrementAndGet();
                if (calls.get() > successCount) {
                    throw new IllegalStateException("模拟 Embedding 服务中途不可用");
                }
                return vector(dimensions);
            }

            /**
             * 必须显式实现：EmbeddingModel#dimensions 的默认实现会真的发一次
             * Embedding 请求，会污染"调用次数"断言。
             */
            @Override
            public int dimensions() {
                return dimensions;
            }
        };
    }

    /**
     * 固定向量的 Embedding 模型，记录调用次数。
     */
    public static EmbeddingModel countingEmbeddingModel(AtomicInteger calls) {
        return countingEmbeddingModel(calls, FIXED_VECTOR.length);
    }

    /**
     * 指定维度的固定向量 Embedding 模型，记录调用次数。
     */
    public static EmbeddingModel countingEmbeddingModel(
            AtomicInteger calls,
            int dimensions
    ) {
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                calls.incrementAndGet();

                List<String> instructions = request.getInstructions();
                List<Embedding> embeddings = new ArrayList<>(
                        instructions == null ? 0 : instructions.size()
                );

                if (instructions != null) {
                    for (int i = 0; i < instructions.size(); i++) {
                        embeddings.add(new Embedding(vector(dimensions), i));
                    }
                }

                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                calls.incrementAndGet();
                return vector(dimensions);
            }

            /**
             * 必须显式实现：EmbeddingModel#dimensions 的默认实现会真的发一次
             * Embedding 请求，会污染"调用次数"断言。
             */
            @Override
            public int dimensions() {
                return dimensions;
            }
        };
    }

    /**
     * 生成固定向量：全部为 0.5，保证余弦相似度为 1，检索一定命中。
     */
    private static float[] vector(int dimensions) {
        float[] vector = new float[dimensions];
        java.util.Arrays.fill(vector, 0.5f);
        return vector;
    }
}
