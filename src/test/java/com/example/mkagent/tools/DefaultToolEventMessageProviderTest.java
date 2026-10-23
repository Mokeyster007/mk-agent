package com.example.mkagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DefaultToolEventMessageProvider 的 web_scrape 文案测试。
 *
 * 重点：抓取失败 / 被拒绝时**不能**显示“网页抓取完成”，
 * 否则前端会把一次失败误读成抓到了正文；
 * 同时文案里不允许出现 URL、路径等原始内容。
 */
class DefaultToolEventMessageProviderTest {

    private final DefaultToolEventMessageProvider provider =
            new DefaultToolEventMessageProvider();

    @Test
    void successfulScrapeShowsCompleted() {
        String raw = "\"页面标题：示例\\n\\n页面正文：正文内容\"";

        assertEquals("网页抓取完成", provider.resultMessage("web_scrape", raw));
    }

    @Test
    void retryableFailureDoesNotShowCompleted() {
        String raw = "\"网页抓取失败：Connection refused\"";

        String message = provider.resultMessage("web_scrape", raw);

        assertEquals("网页抓取失败", message);
        assertFalse(
                message.contains("完成"),
                "失败时不能出现“完成”字样"
        );
    }

    @Test
    void rejectedScrapeDoesNotShowCompletedAndHidesUrl() {
        String raw = "\"网页抓取被拒绝：不允许抓取本机或保留地址\"";

        String message = provider.resultMessage("web_scrape", raw);

        assertEquals("网页抓取被拒绝：该地址不允许抓取", message);
        assertFalse(message.contains("完成"));
        assertFalse(
                message.contains("127.0.0.1"),
                "SSE 文案不得回显被拦截的地址"
        );
    }

    @Test
    void redirectIsReportedAsRedirectNotCompleted() {
        String raw = "\"页面发生重定向，请使用最终 URL：https://example.com/final\"";

        String message = provider.resultMessage("web_scrape", raw);

        assertTrue(message.contains("重定向"), "实际：" + message);
        assertFalse(message.contains("完成"));
        assertFalse(
                message.contains("https://"),
                "SSE 文案不得回显具体 URL"
        );
    }

    @Test
    void emptyResultStillTreatedAsCompleted() {
        assertEquals("网页抓取完成", provider.resultMessage("web_scrape", null));
        assertEquals("网页抓取完成", provider.resultMessage("web_scrape", "  "));
    }

    @Test
    void otherToolsKeepOriginalBehaviour() {
        assertEquals(
                "文件读取完成",
                provider.resultMessage("read_file", "\"ok\"")
        );
        assertEquals(
                "任务结束已确认",
                provider.resultMessage("terminate_task", "\"done\"")
        );
        // 未登记工具走通用兜底。
        assertEquals(
                "工具调用完成",
                provider.resultMessage("unknown_tool", "\"x\"")
        );
    }
}
