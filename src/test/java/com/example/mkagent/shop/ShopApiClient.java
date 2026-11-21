package com.example.mkagent.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 商城接口的测试客户端（真实 HTTP，不走 MockMvc）。
 *
 * <p>为什么用真端口而不是 MockMvc：
 * 发货的并发用例需要**两个真正独立的事务**。MockMvc 在同一个线程里跑，
 * 事务传播与连接获取都和线上不一致，"并发争抢最后一件库存"就测不出真实行为。
 * 走 HTTP 才能保证每个请求各自开事务、各自拿连接。
 */
final class ShopApiClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;

    private final String base;

    ShopApiClient(int port) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.base = "http://localhost:" + port + "/api";
    }

    Reply get(String path, String userId) {
        return send("GET", path, userId, null);
    }

    Reply post(String path, String userId, Object body) {
        return send("POST", path, userId, body);
    }

    Reply delete(String path, String userId) {
        return send("DELETE", path, userId, null);
    }

    private Reply send(String method, String path, String userId, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");

        if (userId != null) {
            builder.header("X-User-Id", userId);
        }

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            try {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("测试请求体序列化失败", e);
            }
        }

        try {
            HttpResponse<String> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            JsonNode parsed = null;
            if (response.body() != null && !response.body().isBlank()) {
                parsed = JSON.readTree(response.body());
            }
            return new Reply(response.statusCode(), parsed, response.body());
        } catch (IOException e) {
            throw new IllegalStateException("测试请求失败：" + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试请求被中断：" + method + " " + path, e);
        }
    }

    /**
     * 一次响应。
     *
     * @param status HTTP 状态码
     * @param body   解析后的 JSON；空响应体为 null
     * @param raw    原始文本（断言"响应里不含敏感内容"时用）
     */
    record Reply(int status, JsonNode body, String raw) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String text(String field) {
            JsonNode node = body == null ? null : body.get(field);
            return node == null || node.isNull() ? null : node.asText();
        }

        int number(String field) {
            JsonNode node = body == null ? null : body.get(field);
            return node == null || node.isNull() ? -1 : node.asInt();
        }

        long longNumber(String field) {
            JsonNode node = body == null ? null : body.get(field);
            return node == null || node.isNull() ? -1L : node.asLong();
        }

        boolean bool(String field) {
            JsonNode node = body == null ? null : body.get(field);
            return node != null && node.asBoolean();
        }

        /** 发货 / 签收结果里的 {@code applied}：本次请求是否真的改变了状态。 */
        boolean applied() {
            return bool("applied");
        }
    }
}
