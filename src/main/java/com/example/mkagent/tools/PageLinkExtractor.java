package com.example.mkagent.tools;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从已解析的页面里抽取可引用的链接。
 *
 * ================== 为什么需要它 ==================
 *
 * 真实运行里 web_scrape 只返回正文，不返回链接；
 * 模型于是"根据页面标题/导航文字自己拼 URL"，
 * 编出了根本不存在的地址——引用链接幻觉。
 *
 * 把页面上**真实存在**的链接一起交给模型，
 * 模型就不需要（也不应该）自己拼。
 *
 * 过滤规则（都是"宁可少给，也不给错"）：
 * 1. 只保留 http / https；
 * 2. 丢掉 javascript:、mailto:、tel: 等非网页协议；
 * 3. 丢掉纯锚点（href 以 # 开头）与空 href；
 * 4. 相对路径必须能解析成绝对 URL，解析不出来就丢；
 * 5. 按绝对 URL 去重（同一个地址只留第一次出现的文字）；
 * 6. 最多 {@link #MAX_LINKS} 条。
 */
public final class PageLinkExtractor {

    /**
     * 单页最多返回的链接条数。
     *
     * 50 条足够覆盖文档页的导航与正文引用，
     * 又不至于把工具结果撑成一份站点地图。
     */
    public static final int MAX_LINKS = 50;

    private static final Set<String> ALLOWED_SCHEMES =
            Set.of("http", "https");

    private PageLinkExtractor() {
    }

    /**
     * 一条可引用链接：显示文字 + 绝对 URL。
     */
    public record Link(String text, String url) {
    }

    /**
     * 抽取链接，保持页面中的出现顺序。
     */
    public static List<Link> extract(Document document) {
        if (document == null) {
            return List.of();
        }

        List<Link> links = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href").trim();

            if (!isCandidateHref(href)) {
                continue;
            }

            /*
             * absUrl 依赖 Document 的 baseUri。
             * 解析不出绝对地址（没有 baseUri、协议不支持）时返回空串，
             * 这种链接一律丢弃——半截地址比没有地址更糟。
             */
            String absolute = anchor.absUrl("href").trim();
            if (!isAllowedUrl(absolute)) {
                continue;
            }

            if (!seenUrls.add(absolute)) {
                continue;
            }

            String text = anchor.text().trim();
            if (text.isEmpty()) {
                // 图片链接、图标链接没有文字，用 URL 兜底，至少可读。
                text = absolute;
            }

            links.add(new Link(text, absolute));

            if (links.size() >= MAX_LINKS) {
                break;
            }
        }

        return List.copyOf(links);
    }

    /**
     * 渲染成给模型看的区块；没有链接时返回空串（不输出空标题）。
     */
    public static String render(List<Link> links) {
        if (links == null || links.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("页面链接（均来自本页实际链接，可直接引用）：\n");

        int index = 1;
        for (Link link : links) {
            builder.append(index++)
                    .append(". ")
                    .append(link.text())
                    .append("：")
                    .append(link.url())
                    .append('\n');
        }

        return builder.toString();
    }

    /**
     * href 的初筛：空、纯锚点、脚本协议都直接排除。
     */
    private static boolean isCandidateHref(String href) {
        if (href.isEmpty() || href.startsWith("#")) {
            return false;
        }

        String lower = href.toLowerCase(Locale.ROOT);
        return !lower.startsWith("javascript:")
                && !lower.startsWith("mailto:")
                && !lower.startsWith("tel:")
                && !lower.startsWith("data:");
    }

    /**
     * 绝对 URL 的复筛：必须是 http / https 且能被 URI 解析。
     */
    private static boolean isAllowedUrl(String absolute) {
        if (absolute.isEmpty()) {
            return false;
        }

        try {
            URI uri = URI.create(absolute);
            String scheme = uri.getScheme();
            return scheme != null
                    && ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            // 非法 URL：丢弃，不抛异常打断整页处理。
            return false;
        }
    }
}
