package com.example.mkagent.tools;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

    private final UrlSafetyValidator urlSafetyValidator;

    /**
     * 默认构造器：使用真实 DNS 解析。
     * 保留无参构造，兼容既有的直接 new 用法与测试。
     */
    public WebScrapingTool() {
        this(new UrlSafetyValidator());
    }

    /**
     * 可注入校验器的构造器，便于单元测试替换 DNS 解析结果。
     */
    public WebScrapingTool(UrlSafetyValidator urlSafetyValidator) {
        this.urlSafetyValidator = urlSafetyValidator;
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
                    .timeout(10_000)
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