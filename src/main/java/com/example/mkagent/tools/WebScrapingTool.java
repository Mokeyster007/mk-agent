package com.example.mkagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class WebScrapingTool {

    @Tool(
            name = "web_scrape",
            description = """
                    抓取公开网页的正文文本内容。

                    当已经拥有一个公开网页 URL，
                    并且需要读取页面正文、文章内容或文档详情时使用。

                    不要用于搜索关键词；搜索关键词应该使用 web_search。
                    """
    )
    public String scrapeWebPage(
            @ToolParam(
                    description = """
                            要抓取的公开 HTTP/HTTPS 网页 URL。
                            不允许本机地址、内网地址或文件协议。
                            """
            )
            String url
    ) {
        try {
            validatePublicHttpUrl(url);

            Document document = Jsoup.connect(url)
                    .timeout(10_000)
                    .maxBodySize(1_000_000)
                    .followRedirects(false)
                    .get();

            String title = document.title();
            String bodyText = document.body() == null
                    ? ""
                    : document.body().text();

            if (bodyText.length() > 12_000) {
                bodyText = bodyText.substring(0, 12_000)
                        + "\n\n[内容过长，已截断]";
            }

            return """
                    页面标题：%s

                    页面正文：
                    %s
                    """.formatted(title, bodyText);

        } catch (Exception e) {
            return "网页抓取失败：" + e.getMessage();
        }
    }

    private void validatePublicHttpUrl(String url) {
        URI uri = URI.create(url);

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "只允许抓取 HTTP 或 HTTPS 网页"
            );
        }

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机地址");
        }

        String lowerHost = host.toLowerCase();

        if ("localhost".equals(lowerHost)
                || "127.0.0.1".equals(lowerHost)
                || "0.0.0.0".equals(lowerHost)
                || "::1".equals(lowerHost)
                || lowerHost.startsWith("169.254.")) {
            throw new IllegalArgumentException(
                    "不允许抓取本机或保留地址"
            );
        }
    }
}