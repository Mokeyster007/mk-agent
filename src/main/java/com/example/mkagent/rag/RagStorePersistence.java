package com.example.mkagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地向量库持久化：store 文件 + 侧车索引文件。
 *
 * 写入顺序（顺序即正确性）：
 * 1. 先写 store 临时文件 → 原子替换 store 文件；
 * 2. 再写索引临时文件 → 原子替换索引文件。
 *
 * 为什么必须先 store 后 index：
 * 如果反过来，崩溃点落在"索引已写、store 未写"时，
 * 下次启动会认为这些片段已入库而跳过，造成知识库缺数据（静默丢数据）；
 * 而按当前顺序崩溃，只会出现"索引落后于 store"，
 * 下次启动最多重复入库几个片段（upsert + 重新 Embedding），不会丢。
 *
 * 读取时的容错：store 文件或索引文件任一缺失、解析失败，
 * 一律返回 null / false，由上层按"整库重建"处理。
 */
public class RagStorePersistence {

    private static final Logger log =
            LoggerFactory.getLogger(RagStorePersistence.class);

    private final Path storePath;

    private final Path indexPath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagStorePersistence(String storePath) {
        if (storePath == null || storePath.isBlank()) {
            throw new IllegalArgumentException("mkagent.rag.store-path 不能为空");
        }
        this.storePath = Path.of(storePath);
        this.indexPath = resolveIndexPath(this.storePath);
    }

    public Path getStorePath() {
        return storePath;
    }

    public Path getIndexPath() {
        return indexPath;
    }

    /**
     * store 文件是否存在。
     */
    public boolean storeExists() {
        return Files.isRegularFile(storePath);
    }

    /**
     * 读取侧车索引。
     *
     * @return 索引；文件缺失或解析失败返回 null（调用方按整库重建处理）
     */
    public RagIndex loadIndex() {
        if (!Files.isRegularFile(indexPath)) {
            return null;
        }

        try {
            RagIndex index = objectMapper.readValue(indexPath.toFile(), RagIndex.class);
            if (index == null || index.getChunks() == null) {
                return null;
            }
            return index;
        } catch (Exception e) {
            log.warn("向量库索引文件解析失败，将整库重建：path={}, error={}",
                    indexPath, e.toString());
            return null;
        }
    }

    /**
     * 把 store 文件加载进给定实例。
     *
     * @return true 表示加载成功
     */
    public boolean loadStore(SimpleVectorStore store) {
        if (!storeExists()) {
            return false;
        }

        try {
            store.load(storePath.toFile());
            return true;
        } catch (Exception e) {
            log.warn("向量库文件加载失败，将整库重建：path={}, error={}",
                    storePath, e.toString());
            return false;
        }
    }

    /**
     * 落盘：先 store，后索引，均为"临时文件 + 原子替换"。
     */
    public void save(SimpleVectorStore store, RagIndex index) {
        try {
            Path parent = storePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            index.touch();
            writeStore(store);
            writeIndex(index);

        } catch (IOException e) {
            throw new IllegalStateException("向量库持久化失败", e);
        }
    }

    private void writeStore(SimpleVectorStore store) throws IOException {
        Path parent = storePath.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, "chat-app-vector-store-", ".tmp");

        try {
            store.save(temp.toFile());
            atomicMove(temp, storePath.toAbsolutePath());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void writeIndex(RagIndex index) throws IOException {
        Path parent = indexPath.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, "chat-app-vector-store-index-", ".tmp");

        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), index);
            atomicMove(temp, indexPath.toAbsolutePath());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            // 部分文件系统 / 网络盘不支持原子移动，退化为普通替换。
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 由 store 路径推导索引路径：xxx.json -> xxx.index.json。
     */
    private static Path resolveIndexPath(Path storePath) {
        String name = storePath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String indexName = (dot > 0 ? name.substring(0, dot) : name) + ".index.json";
        Path parent = storePath.getParent();
        return parent == null ? Path.of(indexName) : parent.resolve(indexName);
    }
}
