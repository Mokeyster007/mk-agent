package com.example.mkagent.tools;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 网页抓取工具。
 *
 * 安全要点：
 * 1. 抓取前用 UrlSafetyValidator 做 SSRF 校验
 *    （解析全部 IP，拦截回环 / 内网 / 链路本地 / 组播 / 云元数据等）；
 * 2. 不自动跟随重定向（followRedirects(false)）：
 *    3xx 时不静默跳到内网，而是把最终 URL 交还给模型，
 *    且该 URL 必须重新通过同一套校验才会被告知；
 * 3. 非 2xx / 3xx 的错误页不返回正文，只返回状态码，
 *    避免把网关错误页、登录页当成网页内容喂给模型。
 */
@Component
public class WebScrapingTool {

    /**
     * 可重试失败前缀。
     *
     * 必须与 application.yml 中
     * mkagent.tool-retry.failure-markers 的 web_scrape 标记保持一致，
     * 否则 RetryableToolCallback 识别不到失败、不会重试。
     */
    private static final String RETRYABLE_FAILURE_PREFIX = "网页抓取失败：";

    /**
     * 不可重试的拒绝前缀。
     *
     * 刻意不包含 "网页抓取失败"，因此不会命中 failure-markers：
     * 这类失败（SSRF 校验被拒、4xx、重定向目标被拒）
     * 重试多少次结果都一样，只会浪费配额与时间。
     */
    private static final String REJECTED_PREFIX = "网页抓取被拒绝：";

    /**
     * 默认单页抓取超时（毫秒）。
     *
     * 10 秒对中文站点通常够用，但对 GitHub、MDN 这类
     * 首字节返回慢的境外站点会稳定超时——真实运行里
     * 就是因为这个固定值把 web_scrape 拖进了重试耗尽。
     * 因此改成可配置，默认值保持 10 秒不变。
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 10_000L;

    /**
     * 默认单页输出上限（字符）。
     *
     * 与改动前硬编码的 12000 保持一致，行为不变；
     * 现在改成可配置，且**链接区块优先于正文尾部**保留
     * （见 {@link #renderPage}）。
     */
    private static final int DEFAULT_MAX_CHARS = 12_000;

    /**
     * 正文被截断时的标记。
     */
    private static final String TRUNCATED_MARKER = "\n\n[内容过长，已截断]";

    private final UrlSafetyValidator urlSafetyValidator;

    /**
     * 单页抓取超时（毫秒），来自 mkagent.tools.web-scrape.timeout-ms。
     */
    private final long timeoutMillis;

    /**
     * 单页输出上限（字符），来自 mkagent.tools.web-scrape.max-chars。
     */
    private final int maxChars;

    /**
     * 默认构造器：使用真实 DNS 解析。
     * 保留无参构造，兼容既有的直接 new 用法与测试。
     */
    public WebScrapingTool() {
        this(new UrlSafetyValidator(), DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_CHARS);
    }

    /**
     * Spring 注入入口：超时与输出上限都可配置。
     *
     * UrlSafetyValidator 不是容器 Bean（它需要真实 DNS，
     * 测试里要替换成桩），所以这里直接 new，保持原有行为。
     */
    @Autowired
    public WebScrapingTool(
            @Value("${mkagent.tools.web-scrape.timeout-ms:10000}")
            long timeoutMillis,
            @Value("${mkagent.tools.web-scrape.max-chars:12000}")
            int maxChars
    ) {
        this(new UrlSafetyValidator(), timeoutMillis, maxChars);
    }

    /**
     * 可注入校验器的构造器，便于单元测试替换 DNS 解析结果。
     */
    public WebScrapingTool(UrlSafetyValidator urlSafetyValidator) {
        this(urlSafetyValidator, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_CHARS);
    }

    /**
     * 完整构造器：校验器与超时（测试用）。
     */
    public WebScrapingTool(
            UrlSafetyValidator urlSafetyValidator,
            long timeoutMillis
    ) {
        this(urlSafetyValidator, timeoutMillis, DEFAULT_MAX_CHARS);
    }

    /**
     * 完整构造器：校验器、超时与输出上限都可替换（测试用）。
     */
    public WebScrapingTool(
            UrlSafetyValidator urlSafetyValidator,
            long timeoutMillis,
            int maxChars
    ) {
        this.urlSafetyValidator = urlSafetyValidator;
        // 非正数视为没配，回退默认值，避免 0 超时导致必然失败。
        this.timeoutMillis = timeoutMillis > 0
                ? timeoutMillis
                : DEFAULT_TIMEOUT_MILLIS;
        // 太小会把正文挤没、链接区块也放不下，低于 500 直接回退默认值。
        this.maxChars = maxChars >= 500 ? maxChars : DEFAULT_MAX_CHARS;
    }

    /**
     * 当前生效的单页抓取超时（毫秒）。
     *
     * 暴露出来是为了让测试能断言"配置真的落到了工具上"：
     * Jsoup 的 Connection 不提供 timeout 的 getter，
     * 想断言只能靠真实发一次慢请求——那是 live 测试，
     * 不适合放在默认测试集里。
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 当前生效的单页输出上限（字符）。
     */
    public int getMaxChars() {
        return maxChars;
    }

    /**
     * 把已解析的页面渲染成给模型的文本。
     *
     * 结构：
     * <pre>
     * 页面标题：…
     *
     * 页面正文：
     * …
     *
     * 页面链接（均来自本页实际链接，可直接引用）：
     * 1. 文字：https://…
     * </pre>
     *
     * ================== 预算分配：链接优先 ==================
     *
     * 总输出受 {@link #maxChars} 约束。空间不够时，
     * **先保住链接区块，再截断正文尾部**——
     * 正文尾部通常是页脚/相关推荐，信息密度最低；
     * 而链接是"回答里能不能给出可信来源"的关键，
     * 一旦被截掉，模型就只能自己编 URL（真实发生过的幻觉）。
     *
     * 单独抽成包级方法是为了能离线测试：
     * 直接喂一个 Jsoup.parse 出来的 Document，不触网。
     */
    String renderPage(Document document) {
        String title = document.title() == null ? "" : document.title();
        String bodyText = document.body() == null
                ? ""
                : document.body().text();

        String header = "页面标题：" + title + "\n\n页面正文：\n";

        String linksBlock = PageLinkExtractor.render(
                PageLinkExtractor.extract(document)
        );
        String linksSection = linksBlock.isEmpty() ? "" : "\n" + linksBlock;

        /*
         * 极端情况：链接区块本身就超预算（maxChars 配得很小）。
         * 先裁链接，再算正文预算，保证总长度不超上限。
         */
        int linksBudget = maxChars - header.length();
        if (linksSection.length() > linksBudget) {
            linksSection = trimLinksSection(linksSection, Math.max(0, linksBudget));
        }

        int bodyBudget = maxChars - header.length() - linksSection.length();
        String body = truncateBody(bodyText, bodyBudget);

        return header + body + linksSection;
    }

    /**
     * 正文按预算截断，截断处加标记。
     */
    private String truncateBody(String bodyText, int budget) {
        if (budget <= 0) {
            return "";
        }
        if (bodyText.length() <= budget) {
            return bodyText;
        }
        // 预算连标记都放不下时，宁可不加标记也不要超上限。
        if (budget <= TRUNCATED_MARKER.length()) {
            return bodyText.substring(0, budget);
        }
        int keep = budget - TRUNCATED_MARKER.length();
        return bodyText.substring(0, keep) + TRUNCATED_MARKER;
    }

    /**
     * 链接区块超预算时按行裁剪：保留区块标题与前若干条链接。
     */
    private String trimLinksSection(String linksSection, int budget) {
        if (budget <= 0) {
            return "";
        }
        if (linksSection.length() <= budget) {
            return linksSection;
        }

        StringBuilder builder = new StringBuilder();
        for (String line : linksSection.split("\n")) {
            // +1 是换行符本身
            if (builder.length() + line.length() + 1 > budget) {
                break;
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

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
            /*
             * 第一步：安全校验（协议、host、全部解析 IP）。
             */
            URI uri = urlSafetyValidator.validate(url);

            /*
             * 第二步：发起请求。
             *
             * ignoreHttpErrors(true)：不因 4xx/5xx 抛异常，
             * 由下方状态码分支统一处理（错误页正文一律不返回）。
             */
            Connection.Response response = Jsoup.connect(uri.toString())
                    .timeout((int) timeoutMillis)
                    .maxBodySize(1_000_000)
                    .followRedirects(false)
                    .ignoreHttpErrors(true)
                    .execute();

            int statusCode = response.statusCode();

            /*
             * 第三步：3xx 不自动跟随。
             *
             * 把最终地址交还给模型，但前提是该地址同样通过安全校验，
             * 防止“公网 URL 302 到内网”的 SSRF 绕过。
             */
            if (statusCode >= 300 && statusCode < 400) {
                return handleRedirect(uri, response);
            }

            /*
             * 第四步：错误状态码分两类处理。
             *
             * 4xx：请求本身有问题（404、403、401 等），重试无意义 → 不可重试；
             * 5xx：服务端临时故障，重试可能成功 → 可重试。
             */
            if (statusCode >= 400 && statusCode < 500) {
                return REJECTED_PREFIX + "HTTP 状态码 " + statusCode;
            }

            if (statusCode < 200 || statusCode >= 300) {
                return RETRYABLE_FAILURE_PREFIX + "HTTP 状态码 " + statusCode;
            }

            Document document = response.parse();

            return renderPage(document);

        } catch (IllegalArgumentException e) {
            /*
             * 安全校验不通过：直接拒绝，不再发起任何网络请求。
             *
             * 用“被拒绝”前缀而不是“失败”，
             * 让重试装饰器识别为不可重试（重试结果必然相同）。
             */
            return REJECTED_PREFIX + e.getMessage();
        } catch (Exception e) {
            // 网络超时、连接失败等：属于可重试的临时故障。
            return RETRYABLE_FAILURE_PREFIX + e.getMessage();
        }
    }

    /**
     * 处理 3xx 重定向：解析 Location 并重新做安全校验。
     *
     * 不自动跟随的原因：
     * 自动跟随会把“目标地址是否安全”的判断交给 HttpClient，
     * 而它不做 SSRF 校验。
     */
    private String handleRedirect(URI baseUri, Connection.Response response) {
        String location = response.header("Location");

        if (location == null || location.isBlank()) {
            /*
             * 服务端异常行为（3xx 却没有 Location），
             * 归为可重试失败。
             */
            return RETRYABLE_FAILURE_PREFIX + "页面发生重定向但缺少 Location 响应头";
        }

        String finalUrl;
        try {
            finalUrl = baseUri.resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
            return REJECTED_PREFIX + "重定向地址格式非法";
        }

        /*
         * 重定向目标必须重新走同一套校验：
         * 通过才把地址告知模型，不通过则直接拒绝且不回显目标地址。
         */
        try {
            urlSafetyValidator.validate(finalUrl);
        } catch (IllegalArgumentException e) {
            return REJECTED_PREFIX + "重定向目标地址不被允许";
        }

        return "页面发生重定向，请使用最终 URL：" + finalUrl;
    }
}