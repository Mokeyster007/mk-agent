package com.example.mkagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.example.mkagent.exception.BusinessException;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 基于本地文件的会话记忆实现（每个 conversationId 一个 .kryo 文件）。
 *
 * 线程安全设计：
 * 1. Kryo 实例不是线程安全的，改为 ThreadLocal 持有，每个线程独享一个实例；
 * 2. 同一 conversationId 的读 / 写 / 清除统一在会话级锁内串行，
 *    避免“读-改-写”交错导致消息丢失；
 * 3. 写入采用“同目录临时文件 + 原子替换”，
 *    即使中途失败也不会破坏原有文件内容。
 *
 * 安全设计：
 * 4. conversationId 只允许字母数字、短横线、下划线，防止路径穿越（../）；
 * 5. 对外抛出的业务异常不携带文件绝对路径，只把详情记到服务端日志。
 */
public class FileBasedChatMemory implements ChatMemory {

    private static final Logger log =
            LoggerFactory.getLogger(FileBasedChatMemory.class);

    /**
     * 合法 conversationId：1~64 位，仅字母、数字、短横线、下划线。
     *
     * 现有调用方（前端 / chatAppTest）均使用 UUID，
     * 例如 0211b6bc-a813-4127-be1e-cdf2cea745ff，完全兼容该规则。
     */
    private static final Pattern CONVERSATION_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /**
     * 会话 ID 非法（客户端错误，映射为 HTTP 400）。
     */
    private static final String INVALID_CONVERSATION_ID_MESSAGE = "会话 ID 非法";

    /**
     * 会话记忆读写失败（服务端错误，映射为 HTTP 500）。
     *
     * 该文案会返回给调用方，因此不包含任何文件路径。
     */
    private static final String MEMORY_IO_FAILED_MESSAGE = "会话记忆读写失败";

    /**
     * 每个线程独享一个 Kryo 实例。
     *
     * 原实现使用 static Kryo 全局共享，
     * 但 Kryo 的序列化上下文（类注册表、引用栈等）不是线程安全的，
     * 并发读写会出现数据错乱。
     */
    private static final ThreadLocal<Kryo> KRYO_HOLDER =
            ThreadLocal.withInitial(() -> {
                Kryo kryo = new Kryo();
                // 不要求预先注册类：Message 子类较多，注册成本高。
                kryo.setRegistrationRequired(false);
                // 使用 Objenesis 绕过无参构造，兼容没有默认构造器的消息类型。
                kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
                return kryo;
            });

    /**
     * 会话记忆根目录。
     */
    private final Path baseDir;

    /**
     * 每个会话一把锁：key = conversationId。
     *
     * 会话 clear 之后锁对象保留（简单优先），
     * 代价只是每个会话一个极小的空对象，可接受。
     */
    private final Map<String, Object> conversationLocks = new ConcurrentHashMap<>();

    public FileBasedChatMemory(String dir) {
        this.baseDir = Paths.get(dir);

        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.error("会话记忆目录创建失败", e);
            throw new BusinessException(500, MEMORY_IO_FAILED_MESSAGE);
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String id = validateConversationId(conversationId);

        if (messages == null || messages.isEmpty()) {
            return;
        }

        synchronized (lockOf(id)) {
            List<Message> conversationMessages = readConversation(id);
            conversationMessages.addAll(messages);
            writeConversation(id, conversationMessages);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        String id = validateConversationId(conversationId);

        /*
         * 读取也放在同一把会话锁内：
         * 避免与 add() 的“读-改-写”交错读到中间态。
         * 同时返回副本，防止调用方修改内部集合。
         */
        synchronized (lockOf(id)) {
            return new ArrayList<>(readConversation(id));
        }
    }

    @Override
    public void clear(String conversationId) {
        String id = validateConversationId(conversationId);

        synchronized (lockOf(id)) {
            Path file = conversationFile(id);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.error("会话记忆清除失败：conversationId={}", id, e);
                throw new BusinessException(500, MEMORY_IO_FAILED_MESSAGE);
            }
        }
    }

    /**
     * 校验会话 ID，防止路径穿越（例如 ../../etc/passwd）。
     *
     * 非法时抛 400 业务异常，
     * 由 GlobalExceptionHandler 映射为 HTTP 400，而不是 500。
     */
    private String validateConversationId(String conversationId) {
        if (conversationId == null
                || !CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            /*
             * 不记录原始 ID：非法 ID 可能本身就是攻击载荷（含 ../ 等），
             * 避免把可疑输入原样写进日志。
             */
            log.warn("拒绝非法会话 ID：不符合 [A-Za-z0-9_-]{1,64} 规则");
            throw new BusinessException(400, INVALID_CONVERSATION_ID_MESSAGE);
        }
        return conversationId;
    }

    private Object lockOf(String conversationId) {
        return conversationLocks.computeIfAbsent(
                conversationId,
                key -> new Object()
        );
    }

    private Path conversationFile(String conversationId) {
        return baseDir.resolve(conversationId + ".kryo");
    }

    /**
     * 读取会话消息；文件不存在时返回空列表。
     */
    private List<Message> readConversation(String conversationId) {
        Path file = conversationFile(conversationId);

        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        try (InputStream in = Files.newInputStream(file);
             Input input = new Input(in)) {

            Object stored = KRYO_HOLDER.get().readObject(input, ArrayList.class);

            if (!(stored instanceof List<?> rawList)) {
                return new ArrayList<>();
            }

            List<Message> messages = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                if (item instanceof Message message) {
                    messages.add(message);
                }
            }
            return messages;

        } catch (Exception e) {
            log.error("会话记忆读取失败：conversationId={}", conversationId, e);
            throw new BusinessException(500, MEMORY_IO_FAILED_MESSAGE);
        }
    }

    /**
     * 写入会话消息：先写同目录临时文件，再原子替换目标文件。
     *
     * 这样即使序列化中途失败，原文件也保持完整（不会出现半截文件）。
     */
    private void writeConversation(String conversationId, List<Message> messages) {
        Path target = conversationFile(conversationId);
        Path tempFile = null;

        try {
            /*
             * 临时文件必须与目标文件同目录，
             * 否则跨文件系统无法做原子移动。
             * 前缀固定加上 chat- 保证长度满足 createTempFile 的 3 字符下限。
             */
            tempFile = Files.createTempFile(
                    baseDir,
                    "chat-" + conversationId + "-",
                    ".kryo.tmp"
            );

            try (Output output = new Output(Files.newOutputStream(tempFile))) {
                KRYO_HOLDER.get().writeObject(output, messages);
            }

            moveReplacing(tempFile, target);

            // 移动成功：临时文件已不存在，置空避免 finally 误删。
            tempFile = null;

        } catch (Exception e) {
            log.error("会话记忆写入失败：conversationId={}", conversationId, e);
            throw new BusinessException(500, MEMORY_IO_FAILED_MESSAGE);
        } finally {
            deleteQuietly(tempFile);
        }
    }

    /**
     * 原子替换目标文件；文件系统不支持原子移动时降级为普通替换。
     */
    private void moveReplacing(Path tempFile, Path target) throws IOException {
        try {
            Files.move(
                    tempFile,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            /*
             * 部分文件系统（网络盘、容器挂载卷等）不支持原子移动。
             * 此时退化为普通替换：内容仍然正确，
             * 但极端情况下存在极短的“不可见窗口”。
             */
            log.warn("当前文件系统不支持原子移动，降级为普通替换");
            Files.move(
                    tempFile,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    /**
     * 尽力删除临时文件；失败只记日志，不覆盖原始异常。
     */
    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("临时会话记忆文件删除失败", e);
        }
    }
}