package com.example.mkagent.tools;

import com.example.mkagent.resilience.RetryableToolCallback;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebScrapingTool 的重试语义测试。
 *
 * 核心诉求：**该重试的才重试**。
 * - 临时故障（5xx、网络异常）→ 前缀 "网页抓取失败"，命中 failure-markers，应当重试；
 * - 确定性拒绝（SSRF 校验、4xx、重定向目标被拒）→ 前缀 "网页抓取被拒绝"，
 *   不命中 failure-markers，必须**只调用一次**。
 *
 * 全部用例只访问本机回环桩服务，不访问外部网络。
 */
class WebScrapingToolRetrySemanticsTest {

    /**
     * 与 application.yml 的 mkagent.tool-retry.failure-markers 保持一致。
     */
    private static final List<String> FAILURE_MARKERS =
            List.of("网页抓取失败");

    private static final int MAX_ATTEMPTS = 3;

    private HttpServer server;

    private String baseUrl;

    /**
     * 桩服务实际收到的请求次数。
     */
    private final AtomicInteger serverHits = new AtomicInteger();

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/notfound", exchange -> {
            serverHits.incrementAndGet();
            respond(exchange, 404, "<html><body>not found</body></html>");
        });

        server.createContext("/servererror", exchange -> {
            serverHits.incrementAndGet();
            respond(exchange, 500, "<html><body>server error</body></html>");
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStubServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void clientErrorIsRejectedAndNotRetried() {
        AtomicInteger resolverCalls = new AtomicInteger();

        RetryableToolCallback callback = retryableCallback(
                toolTreatingLoopbackAsPublic(resolverCalls)
        );

        String result = callback.call(baseUrl + "/notfound");

        assertTrue(
                result.contains("网页抓取被拒绝"),
                "4xx 应使用不可重试前缀，实际：" + result
        );
        assertTrue(
                !result.contains("网页抓取失败"),
                "被拒绝的文案不能包含可重试标记，否则会被无意义重试"
        );

        assertEquals(
                1,
                serverHits.get(),
                "4xx 属于确定性失败，只应请求一次"
        );
        assertEquals(
                0,
                callback.getRetryCount(),
                "4xx 不应产生任何重试"
        );
        assertEquals(1, resolverCalls.get());
    }

    @Test
    void serverErrorIsRetriedUntilMaxAttempts() {
        RetryableToolCallback callback = retryableCallback(
                toolTreatingLoopbackAsPublic(new AtomicInteger())
        );

        // 重试耗尽后装饰器会抛出最后一次失败。
        assertThrows(
                RuntimeException.class,
                () -> callback.call(baseUrl + "/servererror")
        );

        assertEquals(
                MAX_ATTEMPTS,
                serverHits.get(),
                "5xx 属于临时故障，应按 maxAttempts 重试"
        );
        /*
         * 注意：RetryableToolCallback 的 retryCount 统计的是“失败次数”，
         * 最后一次失败也会 +1（随后 break 抛异常），因此这里是 3 而不是 2。
         */
        assertEquals(
                MAX_ATTEMPTS,
                callback.getRetryCount(),
                "3 次尝试全部失败，计数器应记为 3 次失败"
        );
    }

    @Test
    void blockedUrlIsRejectedWithoutAnyNetworkCall() {
        AtomicInteger resolverCalls = new AtomicInteger();

        /*
         * 解析器返回回环地址 → 校验阶段就被拒绝，
         * 根本不会发起 HTTP 请求。
         */
        UrlSafetyValidator blockingValidator = new UrlSafetyValidator(host -> {
            resolverCalls.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        });

        RetryableToolCallback callback = retryableCallback(
                new WebScrapingTool(blockingValidator)
        );

        String result = callback.call("http://evil.example.com/admin");

        assertTrue(result.contains("网页抓取被拒绝"), "实际：" + result);
        assertEquals(1, resolverCalls.get(), "校验只做一次，不应重试");
        assertEquals(0, callback.getRetryCount());
        assertEquals(0, serverHits.get(), "被拒绝的 URL 不应发起任何请求");
    }

    // ===== 辅助方法 =====

    /**
     * 把工具包装成“可重试”的 ToolCallback，配置与生产一致（只认 "网页抓取失败"）。
     */
    private static RetryableToolCallback retryableCallback(
            WebScrapingTool tool
    ) {
        return new RetryableToolCallback(
                asToolCallback(tool),
                MAX_ATTEMPTS,
                Duration.ofMillis(1),
                1.0,
                FAILURE_MARKERS
        );
    }

    /**
     * 校验器把 127.0.0.1 视为公网，让请求能打到本机桩服务。
     */
    private static WebScrapingTool toolTreatingLoopbackAsPublic(
            AtomicInteger resolverCalls
    ) {
        UrlSafetyValidator validator = new UrlSafetyValidator(host -> {
            resolverCalls.incrementAndGet();
            if ("127.0.0.1".equals(host)) {
                return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
            }
            return InetAddress.getAllByName(host);
        });
        return new WebScrapingTool(validator);
    }

    /**
     * 把工具方法适配成 ToolCallback（不经过 Spring 的 MethodToolCallback，
     * 避免返回值被 JSON 序列化后影响前缀判断）。
     */
    private static ToolCallback asToolCallback(WebScrapingTool tool) {
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("web_scrape")
                        .description("测试用网页抓取")
                        .inputSchema("""
                                {"type":"object","properties":{"url":{"type":"string"}}}
                                """)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return tool.scrapeWebPage(toolInput);
            }
        };
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(
                "Content-Type",
                "text/html; charset=utf-8"
        );
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
