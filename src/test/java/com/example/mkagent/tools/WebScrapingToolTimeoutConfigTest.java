package com.example.mkagent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WebScrapingTool 超时配置（离线，不联网）。
 *
 * 真实运行暴露的问题：超时被写死成 10 秒，
 * 抓 GitHub / MDN 这类境外站点时稳定超时，
 * 直接导致 web_scrape 三次尝试全部失败、重试耗尽。
 *
 * 现在超时来自 mkagent.tools.web-scrape.timeout-ms，默认 10000（行为不变）。
 *
 * 局限：Jsoup 的 Connection 不暴露 timeout 的 getter，
 * 想从行为上断言"超时真的生效"必须发一次慢请求（live 测试）。
 * 这里断言的是"配置值真的落到了工具实例上"，
 * 以及 getTimeoutMillis() 与 Jsoup.connect(...).timeout(...) 用的是同一个字段。
 */
class WebScrapingToolTimeoutConfigTest {

    @Test
    @DisplayName("未配置时默认 10000ms（行为不变）")
    void defaultsToTenSeconds() {
        assertEquals(10_000L, new WebScrapingTool().getTimeoutMillis());
        assertEquals(
                10_000L,
                new WebScrapingTool(new UrlSafetyValidator()).getTimeoutMillis()
        );
    }

    @Test
    @DisplayName("超时取自配置")
    void timeoutComesFromConfiguration() {
        WebScrapingTool tool = new WebScrapingTool(new UrlSafetyValidator(), 30_000L);

        assertEquals(30_000L, tool.getTimeoutMillis());
    }

    @Test
    @DisplayName("非正数配置回退默认值，避免 0 超时导致必然失败")
    void nonPositiveTimeoutFallsBackToDefault() {
        assertEquals(
                10_000L,
                new WebScrapingTool(new UrlSafetyValidator(), 0L).getTimeoutMillis()
        );
        assertEquals(
                10_000L,
                new WebScrapingTool(new UrlSafetyValidator(), -1L).getTimeoutMillis()
        );
    }
}
