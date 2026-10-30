package com.example.mkagent.eval;

import com.example.mkagent.model.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用忠实度检查（确定性，不调用模型）。
 *
 * 场景：web_scrape 只返回正文不返回链接，模型据此编造了不存在的 URL。
 * 这条检查把"回答里的 URL 必须能追溯到工具结果或用户输入"变成可执行的断言。
 */
class CitationFaithfulnessCheckerTest {

    private final CitationFaithfulnessChecker checker =
            new CitationFaithfulnessChecker();

    // ==================== URL 提取 ====================

    @Test
    @DisplayName("提取 http/https 链接并去重")
    void extractsAndDeduplicatesUrls() {
        String answer = """
                官方文档见 https://docs.spring.io/spring-ai/reference/
                同样的链接再说一次 https://docs.spring.io/spring-ai/reference/
                另一个 http://example.com/a
                """;

        List<String> urls = CitationFaithfulnessChecker.extractUrls(answer);

        assertEquals(2, urls.size(), "重复链接应去重，实际：" + urls);
        assertEquals("https://docs.spring.io/spring-ai/reference/", urls.get(0));
        assertEquals("http://example.com/a", urls.get(1));
    }

    @Test
    @DisplayName("中文标点紧跟在 URL 后面时不被算进 URL")
    void stripsChinesePunctuation() {
        String answer = "详见 https://example.com/docs，另外 https://example.com/faq。";

        List<String> urls = CitationFaithfulnessChecker.extractUrls(answer);

        assertEquals(
                List.of("https://example.com/docs", "https://example.com/faq"),
                urls
        );
    }

    @Test
    @DisplayName("英文标点与右括号也被剥掉")
    void stripsAsciiPunctuation() {
        String answer = "见 (https://example.com/a). 以及 https://example.com/b,";

        assertEquals(
                List.of("https://example.com/a", "https://example.com/b"),
                CitationFaithfulnessChecker.extractUrls(answer)
        );
    }

    @Test
    @DisplayName("没有链接时返回空列表")
    void noUrlsWhenNonePresent() {
        assertTrue(CitationFaithfulnessChecker.extractUrls("没有链接的回答").isEmpty());
        assertTrue(CitationFaithfulnessChecker.extractUrls(null).isEmpty());
        assertTrue(CitationFaithfulnessChecker.extractUrls("").isEmpty());
    }

    @Test
    @DisplayName("非 http 协议不算 URL")
    void ignoresNonHttpSchemes() {
        assertTrue(
                CitationFaithfulnessChecker.extractUrls(
                        "ftp://files.example.com/a.zip mailto:a@b.com").isEmpty()
        );
    }

    // ==================== 检查通过 ====================

    @Test
    @DisplayName("URL 出现在工具结果里 → 通过")
    void passesWhenUrlComesFromToolResult() {
        EvalExecution execution = execution(
                "文档见 https://docs.spring.io/spring-ai/reference/tool-calling.html",
                List.of("页面链接（均来自本页实际链接）：\n1. Tool Calling："
                        + "https://docs.spring.io/spring-ai/reference/tool-calling.html")
        );

        CitationFaithfulnessChecker.CheckResult result =
                checker.check(evalCase("查文档"), execution);

        assertTrue(result.passed(), "失败原因：" + result.failures());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    @DisplayName("URL 出现在用户输入里 → 通过")
    void passesWhenUrlComesFromUserPrompt() {
        EvalExecution execution = execution(
                "页面标题是 Example Domain，地址 https://example.com",
                List.of("页面标题：Example Domain")
        );

        CitationFaithfulnessChecker.CheckResult result = checker.check(
                evalCase("抓取 https://example.com 并告诉我标题"),
                execution
        );

        assertTrue(result.passed(), "用户自己给的链接应算合法出处：" + result.failures());
    }

    @Test
    @DisplayName("回答里没有链接 → 通过（无可校验对象）")
    void passesWhenAnswerHasNoUrl() {
        CitationFaithfulnessChecker.CheckResult result = checker.check(
                evalCase("总结一下"),
                execution("这是一段没有链接的总结。", List.of("工具结果"))
        );

        assertTrue(result.passed());
    }

    @Test
    @DisplayName("尾部斜杠差异不造成假失败")
    void toleratesTrailingSlashDifference() {
        EvalExecution execution = execution(
                "见 https://example.com/docs/tool-calling",
                List.of("链接：https://example.com/docs/tool-calling/")
        );

        assertTrue(checker.check(evalCase("查"), execution).passed());
    }

    // ==================== 检查失败 ====================

    @Test
    @DisplayName("URL 不在工具结果也不在用户输入里 → 失败并指出是哪个")
    void failsWhenUrlIsFabricated() {
        EvalExecution execution = execution(
                "官方文档：https://docs.spring.io/spring-ai/fake-page.html",
                List.of("页面链接：\n1. Spring AI：https://spring.io/projects/spring-ai")
        );

        CitationFaithfulnessChecker.CheckResult result =
                checker.check(evalCase("查 Spring AI 文档"), execution);

        assertFalse(result.passed(), "编造的链接必须被判失败");
        assertEquals(1, result.failures().size());
        assertTrue(
                result.failures().get(0).contains(
                        "https://docs.spring.io/spring-ai/fake-page.html"),
                "失败信息要指明具体 URL，实际：" + result.failures()
        );
    }

    @Test
    @DisplayName("多个链接里只要有一个没有出处就失败")
    void failsIfAnyUrlIsUnsupported() {
        EvalExecution execution = execution(
                "参考 https://spring.io/projects/spring-ai 与 https://made-up.example.org/x",
                List.of("链接：https://spring.io/projects/spring-ai")
        );

        CitationFaithfulnessChecker.CheckResult result =
                checker.check(evalCase("查"), execution);

        assertFalse(result.passed());
        assertTrue(
                result.failures().get(0).contains("https://made-up.example.org/x"),
                "只应报出没有出处的那一条，实际：" + result.failures()
        );
        assertFalse(
                result.failures().get(0).contains("spring.io/projects/spring-ai"),
                "有出处的链接不应被列为失败"
        );
    }

    // ==================== 辅助 ====================

    private static EvalExecution execution(String finalAnswer, List<String> toolResults) {
        return new EvalExecution(
                AgentState.SUCCEEDED,
                2,
                1,
                10,
                5,
                15,
                100,
                "stub-model",
                List.of("web_search"),
                finalAnswer,
                toolResults
        );
    }

    private static EvalCase evalCase(String userPrompt) {
        return new EvalCase(
                "CITATION-TEST",
                userPrompt,
                List.of(),
                List.of(),
                4,
                3,
                List.of(),
                "CITATION"
        );
    }
}
