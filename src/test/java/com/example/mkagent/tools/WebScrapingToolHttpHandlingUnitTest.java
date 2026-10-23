package com.example.mkagent.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebScrapingTool 的 HTTP 状态码处理单元测试。
 *
 * 说明：
 * 1. 使用 JDK 自带 HttpServer 在 127.0.0.1 上起一个本地桩服务，
 *    只走本机回环，不访问任何外部网络；
 * 2. 为了让请求能通过 SSRF 校验（127.0.0.1 属于回环地址），
 *    这里注入一个“把 127.0.0.1 解析为公网 IP”的校验器 ——
 *    本测试关注的是状态码分支，不是校验逻辑本身（后者见 UrlSafetyValidatorTest）。
 */
class WebScrapingToolHttpHandlingUnitTest {

    private HttpServer server;

    private String baseUrl;

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/ok", exchange -> respond(
                exchange, 200, "<html><head><title>正常页面</title></head>"
                        + "<body>这是正文内容</body></html>"
        ));

        server.createContext("/notfound", exchange -> respond(
                exchange, 404, "<html><body>错误页正文不应被返回</body></html>"
        ));

        server.createContext("/servererror", exchange -> respond(
                exchange, 500, "<html><body>网关错误页正文不应被返回</body></html>"
        ));

        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        server.createContext("/redirect-to-metadata", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location",
                    "http://169.254.169.254/latest/meta-data/"
            );
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
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
    void returnsHttpStatusInsteadOfErrorPageBodyFor4xx() {
        String result = tool().scrapeWebPage(baseUrl + "/notfound");

        assertTrue(
                result.contains("HTTP 状态码 404"),
                "4xx 应返回状态码提示，实际：" + result
        );
        assertFalse(
                result.contains("错误页正文不应被返回"),
                "4xx 错误页正文不允许作为抓取结果返回"
        );
    }

    @Test
    void returnsHttpStatusInsteadOfErrorPageBodyFor5xx() {
        String result = tool().scrapeWebPage(baseUrl + "/servererror");

        assertTrue(result.contains("HTTP 状态码 500"));
        assertFalse(result.contains("网关错误页正文不应被返回"));
    }

    @Test
    void redirectIsNotFollowedAndFinalUrlIsReturned() {
        String result = tool().scrapeWebPage(baseUrl + "/redirect");

        assertTrue(
                result.contains("页面发生重定向，请使用最终 URL"),
                "3xx 应提示重定向，实际：" + result
        );
        assertTrue(
                result.contains(baseUrl + "/ok"),
                "应返回解析后的绝对 URL，实际：" + result
        );
    }

    @Test
    void redirectToInternalAddressIsRejected() {
        String result = tool().scrapeWebPage(baseUrl + "/redirect-to-metadata");

        assertTrue(
                result.contains("重定向目标地址不被允许"),
                "指向内网/元数据地址的重定向必须被拒绝，实际：" + result
        );
        assertFalse(
                result.contains("169.254.169.254"),
                "拒绝时不得回显被拦截的内网地址"
        );
    }

    @Test
    void normalPageReturnsTitleAndBody() {
        String result = tool().scrapeWebPage(baseUrl + "/ok");

        assertTrue(result.contains("正常页面"));
        assertTrue(result.contains("这是正文内容"));
    }

    /**
     * 构造工具：只把回环地址“视为公网”，其余 host 走真实解析。
     */
    private static WebScrapingTool tool() {
        UrlSafetyValidator validator = new UrlSafetyValidator(host -> {
            if ("127.0.0.1".equals(host)) {
                return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
            }
            return InetAddress.getAllByName(host);
        });
        return new WebScrapingTool(validator);
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
