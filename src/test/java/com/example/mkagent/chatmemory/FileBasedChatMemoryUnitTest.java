package com.example.mkagent.chatmemory;

import com.example.mkagent.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileBasedChatMemory 单元测试。
 *
 * 覆盖四类风险（全部离线，使用临时目录）：
 * 1. 并发写同一会话不丢消息（Kryo 线程安全 + 会话级锁）；
 * 2. 非法 conversationId 被拒绝（路径穿越防护）；
 * 3. 写入中途失败不破坏原文件（临时文件 + 原子替换）；
 * 4. get() 返回副本，调用方修改不影响已持久化内容。
 */
class FileBasedChatMemoryUnitTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentAddOnSameConversationKeepsExactlyAllMessages() throws Exception {
        int threads = 20;

        FileBasedChatMemory memory =
                new FileBasedChatMemory(tempDir.resolve("memory").toString());

        String conversationId = "conv-concurrent";

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);

        List<Throwable> errors = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        memory.add(
                                conversationId,
                                List.of(new UserMessage("并发消息-" + index))
                        );
                        successCount.incrementAndGet();
                    } catch (Throwable t) {
                        synchronized (errors) {
                            errors.add(t);
                        }
                    } finally {
                        doneGate.countDown();
                    }
                }));
            }

            // 所有线程同时开跑，制造最大竞争。
            startGate.countDown();
            assertTrue(
                    doneGate.await(30, TimeUnit.SECONDS),
                    "并发写入未在超时时间内完成"
            );

            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "并发写入不应抛异常：" + errors);
        assertEquals(threads, successCount.get());

        List<Message> messages = memory.get(conversationId);
        assertEquals(
                threads,
                messages.size(),
                "20 个线程各写 1 条，最终必须恰好 20 条（不能丢也不能重）"
        );
    }

    @Test
    void rejectsIllegalConversationIdToPreventPathTraversal() {
        FileBasedChatMemory memory =
                new FileBasedChatMemory(tempDir.resolve("memory").toString());

        List<String> illegalIds = List.of(
                "../etc/passwd",
                "..",
                "a/b",
                "a\\b",
                "a.b",
                "会话",
                "",
                "x".repeat(65)
        );

        for (String illegalId : illegalIds) {
            BusinessException e = assertThrows(
                    BusinessException.class,
                    () -> memory.add(illegalId, List.of(new UserMessage("x"))),
                    "非法会话 ID 必须被拒绝：" + illegalId
            );
            assertEquals(
                    400,
                    e.getHttpStatus(),
                    "非法会话 ID 应返回 400 而不是 500"
            );
        }

        // null 同样拒绝。
        assertThrows(
                BusinessException.class,
                () -> memory.get(null)
        );

        // 合法 UUID 形式不受影响。
        String uuid = "0211b6bc-a813-4127-be1e-cdf2cea745ff";
        memory.add(uuid, List.of(new UserMessage("ok")));
        assertEquals(1, memory.get(uuid).size());
    }

    @Test
    void failedWriteKeepsOriginalFileIntact() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        FileBasedChatMemory memory = new FileBasedChatMemory(memoryDir.toString());

        String conversationId = "conv-broken";

        /*
         * 把目标路径做成“非空目录”：
         * 临时文件可以正常写出，但最后的替换一定失败，
         * 从而稳定复现“写入中途失败”的场景（不依赖操作系统权限差异）。
         */
        Path target = memoryDir.resolve(conversationId + ".kryo");
        Files.createDirectories(target);
        Path sentinel = target.resolve("keep.txt");
        Files.writeString(sentinel, "原有内容");

        BusinessException e = assertThrows(
                BusinessException.class,
                () -> memory.add(
                        conversationId,
                        List.of(new UserMessage("这次写入会失败"))
                )
        );

        assertEquals(500, e.getHttpStatus());
        assertFalse(
                e.getMessage().contains(memoryDir.toString()),
                "异常信息不得包含文件绝对路径"
        );

        // 原目标未被破坏。
        assertTrue(Files.isDirectory(target), "原目标路径应保持原样");
        assertEquals("原有内容", Files.readString(sentinel));

        // 不残留临时文件。
        try (Stream<Path> files = Files.list(memoryDir)) {
            assertTrue(
                    files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "写入失败后不应残留临时文件"
            );
        }
    }

    @Test
    void getReturnsDefensiveCopyWithContentPersistedAcrossInstances() {
        String dir = tempDir.resolve("memory").toString();
        FileBasedChatMemory memory = new FileBasedChatMemory(dir);

        String conversationId = "conv-copy";
        memory.add(conversationId, List.of(new UserMessage("第一条")));

        List<Message> firstRead = memory.get(conversationId);
        assertEquals(1, firstRead.size());

        // 修改返回值不应影响已持久化内容。
        firstRead.clear();

        assertEquals(
                1,
                memory.get(conversationId).size(),
                "get() 必须返回副本，外部修改不能影响存储"
        );

        // 换一个实例读取同一目录，验证内容确实落盘。
        FileBasedChatMemory another = new FileBasedChatMemory(dir);
        assertEquals(1, another.get(conversationId).size());

        // clear 后为空。
        memory.clear(conversationId);
        assertTrue(memory.get(conversationId).isEmpty());
    }
}
