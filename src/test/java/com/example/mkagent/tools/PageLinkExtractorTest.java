package com.example.mkagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面链接抽取（离线，不联网）。
 *
 * 背景：web_scrape 过去只返回正文，模型据此"根据标题自己拼 URL"，
 * 编出了不存在的链接。现在把页面上真实存在的链接一起返回。
 */
class PageLinkExtractorTest {

    private static final String BASE = "https://example.com/page/index.html";

    @Test
    @DisplayName("相对路径解析成绝对 URL，并保持页面顺序")
    void resolvesRelativeUrls() {
        String html = """
                <html><head><title>文档</title></head><body>
                <a href="/docs/tool-calling">Tool Calling</a>
                <a href="docs/relative.html">相对路径</a>
                <a href="https://other.example.org/abs">绝对地址</a>
                </body></html>
                """;

        List<PageLinkExtractor.Link> links = extract(html);

        assertEquals(3, links.size());
        assertEquals("https://example.com/docs/tool-calling", links.get(0).url());
        assertEquals(
                "https://example.com/page/docs/relative.html",
                links.get(1).url(),
                "相对路径必须基于页面地址解析"
        );
        assertEquals("https://other.example.org/abs", links.get(2).url());
        assertEquals("Tool Calling", links.get(0).text());
    }

    @Test
    @DisplayName("同一绝对 URL 只保留第一次出现")
    void deduplicatesByAbsoluteUrl() {
        String html = """
                <html><body>
                <a href="/docs/x">第一次</a>
                <a href="https://example.com/docs/x">第二次（绝对写法）</a>
                <a href="/docs/x">第三次</a>
                </body></html>
                """;

        List<PageLinkExtractor.Link> links = extract(html);

        assertEquals(1, links.size(), "三种写法指向同一地址，应只留一条");
        assertEquals("第一次", links.get(0).text());
    }

    @Test
    @DisplayName("过滤 javascript:、纯锚点、mailto: 与空 href")
    void filtersNonCitableHrefs() {
        String html = """
                <html><body>
                <a href="javascript:void(0)">脚本</a>
                <a href="JavaScript:alert(1)">大写脚本</a>
                <a href="#section">锚点</a>
                <a href="mailto:a@b.com">邮件</a>
                <a href="tel:+8610000">电话</a>
                <a href="">空</a>
                <a href="/real">真实链接</a>
                </body></html>
                """;

        List<PageLinkExtractor.Link> links = extract(html);

        assertEquals(1, links.size(), "只应留下真实网页链接");
        assertEquals("https://example.com/real", links.get(0).url());
    }

    @Test
    @DisplayName("没有文字时用 URL 兜底")
    void usesUrlAsTextWhenAnchorHasNoText() {
        String html = """
                <html><body>
                <a href="https://cdn.example.com/a.pdf"><img src="a.png"></a>
                </body></html>
                """;

        List<PageLinkExtractor.Link> links = extract(html);

        assertEquals(1, links.size());
        assertEquals(
                "https://cdn.example.com/a.pdf",
                links.get(0).text(),
                "图片链接没有文字，应回退成 URL"
        );
    }

    @Test
    @DisplayName("最多保留 50 条")
    void capsAtFiftyLinks() {
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < 60; i++) {
            html.append("<a href=\"/doc/").append(i).append("\">链接")
                    .append(i).append("</a>");
        }
        html.append("</body></html>");

        List<PageLinkExtractor.Link> links = extract(html.toString());

        assertEquals(
                PageLinkExtractor.MAX_LINKS,
                links.size(),
                "上限必须硬性生效"
        );
        assertEquals("https://example.com/doc/0", links.get(0).url());
        assertEquals("https://example.com/doc/49", links.get(49).url());
    }

    @Test
    @DisplayName("渲染成带序号的链接区块")
    void rendersNumberedBlock() {
        String html = """
                <html><body>
                <a href="/a">A 文档</a>
                <a href="/b">B 文档</a>
                </body></html>
                """;

        String block = PageLinkExtractor.render(extract(html));

        assertTrue(block.startsWith("页面链接"), "实际：" + block);
        assertTrue(block.contains("1. A 文档：https://example.com/a"), "实际：" + block);
        assertTrue(block.contains("2. B 文档：https://example.com/b"), "实际：" + block);
    }

    @Test
    @DisplayName("没有链接时渲染为空串，不产生空标题")
    void rendersEmptyWhenNoLinks() {
        assertEquals("", PageLinkExtractor.render(List.of()));
        assertEquals("", PageLinkExtractor.render(null));
    }

    @Test
    @DisplayName("null 文档安全返回空列表")
    void nullDocumentIsSafe() {
        assertTrue(PageLinkExtractor.extract(null).isEmpty());
    }

    @Test
    @DisplayName("非 http/https 协议（ftp、data）被过滤")
    void filtersNonHttpSchemes() {
        String html = """
                <html><body>
                <a href="ftp://files.example.com/a.zip">FTP</a>
                <a href="data:text/html,hello">DATA</a>
                <a href="https://ok.example.com/x">OK</a>
                </body></html>
                """;

        List<PageLinkExtractor.Link> links = extract(html);

        assertEquals(1, links.size());
        assertEquals("https://ok.example.com/x", links.get(0).url());
        assertFalse(links.get(0).url().startsWith("ftp"));
    }

    private static List<PageLinkExtractor.Link> extract(String html) {
        Document document = Jsoup.parse(html, BASE);
        return PageLinkExtractor.extract(document);
    }
}
