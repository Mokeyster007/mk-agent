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
     * 固定向量的 Embedding 模型，记录调用次数。
     */
    public static EmbeddingModel countingEmbeddingModel(AtomicInteger calls) {
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
                        embeddings.add(new Embedding(FIXED_VECTOR.clone(), i));
                    }
                }

                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                calls.incrementAndGet();
                return FIXED_VECTOR.clone();
            }
        };
    }
}
