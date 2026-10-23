package com.example.mkagent.rag;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库可用性标记（进程内）。
 *
 * 语义：
 * 1. 初始为可用（尚未尝试构建，不代表一定成功）；
 * 2. 构建成功 → 可用；
 * 3. 构建失败 → 标记不可用，并记录脱敏原因。
 *
 * 为什么要单独抽一个 Bean：
 * 向量库构建发生在 Bean 创建阶段（eager）或第一次调用（lazy），
 * 而"是否可用"这个结论要同时被 chatApp 与日志/测试读取。
 * 放在 RagAvailability 里可以避免 chatApp 依赖 SimpleVectorStore 的实现细节，
 * 也便于后续接入 pgvector 时复用同一套失败语义。
 *
 * 注意：不可用是"粘性"的（fail-closed）——
 * 构建失败后不再每次请求都重试，避免持续消耗模型配额；
 * 需要恢复只能重启应用或显式调用 reset()。
 */
@Component
public class RagAvailability {

    private final AtomicBoolean available = new AtomicBoolean(true);

    /**
     * 不可用原因（只写脱敏描述，不含文件路径、密钥、异常原文）。
     */
    private volatile String unavailableReason = "";

    public boolean isAvailable() {
        return available.get();
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    /**
     * 标记为可用（构建成功时调用）。
     */
    public void markAvailable() {
        this.unavailableReason = "";
        this.available.set(true);
    }

    /**
     * 标记为不可用。
     *
     * @param reason 脱敏原因，仅用于日志与排查
     */
    public void markUnavailable(String reason) {
        this.unavailableReason = reason == null ? "" : reason;
        this.available.set(false);
    }

    /**
     * 重置为初始状态，仅供单元测试使用。
     */
    public void reset() {
        this.unavailableReason = "";
        this.available.set(true);
    }
}
