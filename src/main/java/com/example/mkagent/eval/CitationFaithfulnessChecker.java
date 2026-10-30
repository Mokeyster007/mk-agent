package com.example.mkagent.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用忠实度检查（确定性，不调用模型）。
 *
 * ================== 要解决的问题 ==================
 *
 * 真实运行里模型编造了不存在的 URL：web_scrape 只给正文不给链接，
 * 模型就"根据页面标题自己拼"一个地址写进回答。
 * 这类幻觉最危险的地方在于**看起来完全可信**——
 * 域名对、路径像、点开是 404。
 *
 * ================== 判定规则 ==================
 *
 * 从最终回答里提取所有 URL，逐个检查是否在
 * "本次任务的工具结果 ∪ 用户输入" 中出现过。
 * 只要有一个 URL 找不到出处，本条用例判失败，
 * 并明确指出是哪个地址（报告里可直接定位）。
 *
 * 刻意采用**子串包含**而不是严格相等：
 * 工具结果里的链接可能带查询串、锚点或尾部斜杠，
 * 而模型引用时常常去掉这些装饰。过度严格会制造大量假失败，
 * 反而让这条检查失去意义。
 */
public final class CitationFaithfulnessChecker {

    /**
     * URL 提取正则。
     *
     * 只匹配 http/https；遇到空白、引号、尖括号、
     * 以及常见中英文标点即视为 URL 结束
     * （模型写中文时 URL 后面经常紧跟"，"或"。"，不能把它们算进 URL）。
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[^\\s<>\"'`\\[\\]{}|\\\\^，。；：、！？（）【】《》“”‘’]+",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * URL 末尾可能粘上的标点，需要剥掉。
     */
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}";

    /**
     * 执行检查。
     *
     * @param evalCase  用例（提供用户输入，用户自己给的链接也算合法出处）
     * @param execution 执行快照（最终回答 + 工具结果）
     */
    public CheckResult check(EvalCase evalCase, EvalExecution execution) {
        List<String> citedUrls = extractUrls(execution.finalAnswer());

        if (citedUrls.isEmpty()) {
            // 没引用链接就没有可幻觉的对象，直接通过。
            return new CheckResult(true, List.of());
        }

        List<String> sources = collectSources(evalCase, execution);

        List<String> unsupported = new ArrayList<>();
        for (String url : citedUrls) {
            if (!appearsInSources(url, sources)) {
                unsupported.add(url);
            }
        }

        if (unsupported.isEmpty()) {
            return new CheckResult(true, List.of());
        }

        return new CheckResult(
                false,
                List.of("最终回答引用了工具结果与用户输入中都不存在的 URL："
                        + unsupported)
        );
    }

    /**
     * 从文本里提取去重后的 URL（保持出现顺序）。
     */
    public static List<String> extractUrls(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(text);

        while (matcher.find()) {
            String url = trimTrailingPunctuation(matcher.group());
            if (!url.isEmpty()) {
                urls.add(url);
            }
        }

        return List.copyOf(urls);
    }

    /**
     * 合法出处 = 用户输入 + 全部工具结果。
     */
    private List<String> collectSources(EvalCase evalCase, EvalExecution execution) {
        List<String> sources = new ArrayList<>();
        if (evalCase != null && evalCase.getUserPrompt() != null) {
            sources.add(evalCase.getUserPrompt());
        }
        sources.addAll(execution.toolResults());
        return sources;
    }

    /**
     * 判断 URL 是否出现在任一出处里。
     *
     * 比较前把两边的尾部斜杠与大小写统一（host 大小写不敏感，
     * 路径大小写敏感——这里为了减少假失败统一按不敏感处理，
     * 代价是极少数路径大小写不同的情况会漏判，可接受）。
     */
    private boolean appearsInSources(String url, List<String> sources) {
        String normalizedUrl = normalize(url);

        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            if (normalize(source).contains(normalizedUrl)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 去掉尾部斜杠并转小写，用于宽松比较。
     */
    private String normalize(String value) {
        String result = value.toLowerCase(Locale.ROOT);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 剥掉粘在 URL 末尾的标点（"见 https://a.com/x。" 里的句号）。
     */
    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0
                && TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * 检查结果。
     */
    public record CheckResult(
            boolean passed,
            List<String> failures
    ) {
        public CheckResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }
}
