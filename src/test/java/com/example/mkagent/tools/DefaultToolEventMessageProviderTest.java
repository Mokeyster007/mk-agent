package com.example.mkagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // ==================== web_search：JSON 字符串字面量解码 ====================

    /**
     * 核心回归：搜索结果条数曾经**永远是 1**。
     *
     * 根因：@Tool 返回值经 MethodToolCallback 序列化为 JSON 字符串字面量
     * （外层双引号 + 换行转义成 \n），而代码按真实换行 "\n---\n" 分割，
     * 于是永远只得到 1 段。
     */
    @Test
    void searchResultCountIsCorrectForJsonEncodedResponse() {
        // 3 条结果，真实形态：带外层引号、换行是转义后的 \n
        String raw = "\"标题：A\\n链接：https://a\\n摘要：sa\\n---\\n"
                + "标题：B\\n链接：https://b\\n摘要：sb\\n---\\n"
                + "标题：C\\n链接：https://c\\n摘要：sc\\n\"";

        String message = provider.resultMessage("web_search", raw);

        assertEquals("网页搜索完成，获取 3 条结果", message);
    }

    /**
     * 未编码的原始字符串同样要工作（不是所有调用路径都经过序列化，
     * 例如 RetryableToolCallback 耗尽后返回的是纯文本）。
     */
    @Test
    void searchResultCountIsCorrectForRawResponse() {
        String raw = "标题：A\n链接：https://a\n摘要：sa\n---\n"
                + "标题：B\n链接：https://b\n摘要：sb";

        assertEquals(
                "网页搜索完成，获取 2 条结果",
                provider.resultMessage("web_search", raw)
        );
    }

    /**
     * startsWith("没有找到") 在带引号字符串上曾经永远为 false。
     */
    @Test
    void noResultsDetectedOnJsonEncodedResponse() {
        String raw = "\"没有找到与关键词相关的搜索结果：spring ai\"";

        assertEquals(
                "网页搜索完成，未找到相关结果",
                provider.resultMessage("web_search", raw)
        );
    }

    @Test
    void noResultsDetectedOnRawResponse() {
        assertEquals(
                "网页搜索完成，未找到相关结果",
                provider.resultMessage(
                        "web_search",
                        "没有找到与关键词相关的搜索结果：spring ai"
                )
        );
    }

    /**
     * startsWith("搜索工具执行失败") 在带引号字符串上曾经永远为 false，
     * 于是失败被误报成"搜索完成"。
     */
    @Test
    void searchFailureDetectedOnJsonEncodedResponse() {
        String raw = "\"搜索工具执行失败：Connection reset\"";

        assertEquals("网页搜索失败", provider.resultMessage("web_search", raw));
    }

    @Test
    void searchFailureDetectedOnRawResponse() {
        assertEquals(
                "网页搜索失败",
                provider.resultMessage("web_search", "搜索工具执行失败：Connection reset")
        );
    }

    // ==================== 重试耗尽文案 ====================

    /**
     * 重试耗尽后 RetryableToolCallback 返回纯文本失败文案，
     * 前端文案不能退化成"网页抓取完成"。
     */
    @Test
    void exhaustedScrapeIsNotReportedAsCompleted() {
        String raw = "工具 web_scrape 多次尝试仍失败：Read timed out。"
                + "请换用其他方式，或在无法完成时调用 terminate_task";

        String message = provider.resultMessage("web_scrape", raw);

        assertEquals("网页抓取多次重试后仍失败", message);
        assertFalse(message.contains("完成"), "耗尽时不能出现“完成”字样");
    }

    @Test
    void exhaustedSearchIsNotReportedAsSuccess() {
        String raw = "工具 web_search 多次尝试仍失败：Connection reset。"
                + "请换用其他方式，或在无法完成时调用 terminate_task";

        assertEquals(
                "网页搜索多次重试后仍失败",
                provider.resultMessage("web_search", raw)
        );
    }

    /**
     * 耗尽文案本身是纯文本，但也可能被再次序列化（防御性覆盖）。
     */
    @Test
    void exhaustedMarkerDetectedEvenWhenJsonEncoded() {
        String raw = "\"工具 web_scrape 多次尝试仍失败：Read timed out。"
                + "请换用其他方式，或在无法完成时调用 terminate_task\"";

        assertEquals(
                "网页抓取多次重试后仍失败",
                provider.resultMessage("web_scrape", raw)
        );
    }

    /**
     * 站点级熔断：本次调用根本没执行，前端文案必须是"已跳过"，
     * 既不能说"完成"，也不能说"失败"（会误导用户以为又试了一次）。
     */
    @Test
    void targetBlockedScrapeIsReportedAsSkipped() {
        String raw = "该站点连续失败，请换用其他来源或在无法完成时调用 terminate_task"
                + "（工具 web_scrape，目标 github.com，已连续重试耗尽 2 次，本次未再执行）";

        String message = provider.resultMessage("web_scrape", raw);

        assertEquals("该站点连续失败，已跳过本次抓取", message);
        assertFalse(message.contains("完成"), "熔断不是完成");
        assertFalse(message.contains("重试后仍失败"),
                "熔断与'这次试了但失败'必须区分开");
    }

    @Test
    void targetBlockedSearchIsReportedAsSkipped() {
        String raw = "该站点连续失败，请换用其他来源或在无法完成时调用 terminate_task"
                + "（工具 web_search，目标 同一目标，已连续重试耗尽 2 次，本次未再执行）";

        assertEquals(
                "该站点连续失败，已跳过本次搜索",
                provider.resultMessage("web_search", raw)
        );
    }

    // ==================== 电商导购与售后工具 ====================

    @Test
    @DisplayName("电商导购与售后工具均提供可读开始文案，不直接暴露内部工具名")
    void shopAndAfterSaleToolsHaveReadableStartMessages() {
        assertEquals("正在按条件筛选商品", provider.startMessage("search_products"));
        assertEquals("正在对比商品规格", provider.startMessage("compare_products"));
        assertEquals("正在加入购物车", provider.startMessage("add_to_cart"));
        assertEquals("正在计算结算预览", provider.startMessage("preview_checkout"));
        assertEquals("正在提交确认订单", provider.startMessage("submit_checkout_order"));
        assertEquals("正在试算退货金额与资格", provider.startMessage("preview_return"));
        assertEquals("正在提交退货申请", provider.startMessage("submit_return"));
    }

    @Test
    @DisplayName("商品检索结果与售后结果文案脱敏且按信封 ok/count 区分")
    void shopAndAfterSaleResultMessages() throws Exception {
        String searchEnvelope = ToolResponseEnvelope.okSingle(Map.of(
                "items", List.of(
                        Map.of("id", 2004, "sku", "SKU-KEYBOARD-A75", "name", "无线轻音机械键盘 Air75"),
                        Map.of("id", 2005, "sku", "SKU-KEYBOARD-B68", "name", "紧凑蓝牙键盘 B68")
                )
        ));
        String encoded = new ObjectMapper().writeValueAsString(searchEnvelope);

        String searchMsg = provider.resultMessage("search_products", encoded);
        assertEquals("找到 2 款符合条件的商品", searchMsg);
        assertFalse(searchMsg.contains("Air75"), "不得回显商品标题");
        assertFalse(searchMsg.contains("SKU-"), "不得回显 SKU");

        String emptyEnvelope = ToolResponseEnvelope.okSingle(Map.of("items", List.of()));
        assertEquals("没有找到满足全部硬条件的商品",
                provider.resultMessage("search_products", emptyEnvelope));

        assertEquals("已更新购物车",
                provider.resultMessage("add_to_cart", "{\"ok\":true}"));
        assertEquals("订单已创建",
                provider.resultMessage("submit_checkout_order", "{\"ok\":true}"));
        assertEquals("退货申请已创建",
                provider.resultMessage("submit_return", "{\"ok\":true}"));
        assertEquals("退货提交未成功（未创建退货单）",
                provider.resultMessage("submit_return", ToolResponseEnvelope.error("额度不足")));
    }
}
