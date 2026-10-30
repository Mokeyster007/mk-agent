package com.example.mkagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * web_scrape 的输出组装（离线，不联网）。
 *
 * 两个约束同时成立：
 * 1. 正文之外必须带"页面链接"区块（模型据此引用，而不是自己拼 URL）；
 * 2. 总输出受 max-chars 约束，且**链接区块优先于被截断的正文尾部**保留。
 */
class WebScrapingToolLinkBlockTest {

    private static final String BASE = "https://example.com/docs/page.html";

    @Test
    @DisplayName("输出包含页面标题、正文与链接区块")
    void includesTitleBodyAndLinks() {
        Document document = Jsoup.parse("""
                <html><head><title>Tool Calling 文档</title></head><body>
                <p>正文内容一段。</p>
                <a href="/docs/tool-calling">Tool Calling</a>
                </body></html>
                """, BASE);

        String output = newTool(12_000).renderPage(document);

        assertTrue(output.contains("页面标题：Tool Calling 文档"), output);
        assertTrue(output.contains("页面正文："), output);
        assertTrue(output.contains("正文内容一段。"), output);
        assertTrue(output.contains("页面链接"), output);
        assertTrue(
                output.contains("https://example.com/docs/tool-calling"),
                "必须给出可引用的绝对地址，实际：" + output
        );
    }

    @Test
    @DisplayName("页面没有链接时不输出链接区块")
    void omitsLinkBlockWhenNoLinks() {
        Document document = Jsoup.parse("""
                <html><head><title>纯文本页</title></head><body>
                <p>没有任何链接。</p>
                </body></html>
                """, BASE);

        String output = newTool(12_000).renderPage(document);

        assertFalse(output.contains("页面链接"), "无链接时不应出现空区块：" + output);
    }

    @Test
    @DisplayName("正文超长时截断，但链接区块必须完整保留")
    void keepsLinksWhenBodyIsTruncated() {
        String longBody = "正".repeat(20_000);

        Document document = Jsoup.parse(
                "<html><head><title>长文档</title></head><body><p>"
                        + longBody
                        + "</p><a href=\"/docs/official\">官方文档</a>"
                        + "</body></html>",
                BASE
        );

        String output = newTool(1_000).renderPage(document);

        assertTrue(
                output.length() <= 1_000,
                "总输出不得超过 max-chars，实际：" + output.length()
        );
        assertTrue(
                output.contains("https://example.com/docs/official"),
                "链接区块优先于正文尾部保留，实际：" + output
        );
        assertTrue(
                output.contains("已截断"),
                "正文被截断时应有标记，实际尾部：" + output.substring(Math.max(0, output.length() - 40))
        );
    }

    @Test
    @DisplayName("max-chars 很小时仍然不超上限")
    void respectsVerySmallBudget() {
        Document document = Jsoup.parse("""
                <html><head><title>小预算</title></head><body>
                <p>%s</p>
                <a href="/a">A</a><a href="/b">B</a><a href="/c">C</a>
                </body></html>
                """.formatted("文".repeat(5_000)), BASE);

        String output = newTool(500).renderPage(document);

        assertTrue(
                output.length() <= 500,
                "极小预算下也不能超上限，实际：" + output.length()
        );
    }

    @Test
    @DisplayName("正文刚好在预算内时不加截断标记")
    void noMarkerWhenBodyFits() {
        Document document = Jsoup.parse("""
                <html><head><title>短文</title></head><body>
                <p>很短的一段正文。</p>
                </body></html>
                """, BASE);

        String output = newTool(12_000).renderPage(document);

        assertFalse(output.contains("已截断"), "没截断就不该出现标记：" + output);
    }

    // ==================== 配置 ====================

    @Test
    @DisplayName("max-chars 默认 12000，与改动前行为一致")
    void defaultsToTwelveThousandChars() {
        assertEquals(12_000, new WebScrapingTool().getMaxChars());
        assertEquals(
                12_000,
                new WebScrapingTool(new UrlSafetyValidator()).getMaxChars()
        );
    }

    @Test
    @DisplayName("max-chars 取自配置")
    void maxCharsComesFromConfiguration() {
        WebScrapingTool tool = new WebScrapingTool(
                new UrlSafetyValidator(), 10_000L, 8_000);

        assertEquals(8_000, tool.getMaxChars());
    }

    @Test
    @DisplayName("过小的 max-chars 回退默认值")
    void tinyMaxCharsFallsBack() {
        assertEquals(
                12_000,
                new WebScrapingTool(new UrlSafetyValidator(), 10_000L, 100).getMaxChars(),
                "低于 500 的配置会把正文和链接都挤没，应回退默认值"
        );
    }

    private static WebScrapingTool newTool(int maxChars) {
        return new WebScrapingTool(new UrlSafetyValidator(), 10_000L, maxChars);
    }
}
