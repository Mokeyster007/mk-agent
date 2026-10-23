package com.example.mkagent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认工具事件消息提供者。
 *
 * 为每个已知工具维护一组用户可读文案；
 * 未登记的工具走通用兜底文案。
 * 所有文案都经过脱敏：不包含路径、密钥、完整原始结果。
 */
@Component
public class DefaultToolEventMessageProvider
        implements ToolEventMessageProvider {

    /**
     * 工具名称 → 调用开始文案。
     */
    private static final Map<String, String> START_MESSAGES = Map.of(
            "web_search", "正在搜索互联网公开资料",
            "web_scrape", "正在抓取网页正文",
            "read_file", "正在读取文件内容",
            "write_file", "正在写入文件",
            "generate_pdf", "正在生成 PDF 文件",
            "download_resource", "正在下载公开资源",
            "terminate_task", "任务收尾中",
            "demo_inventory_check", "正在查询库存信息"
    );

    /**
     * 工具名称 → 调用完成文案（不需要根据结果细分时使用）。
     *
     * web_scrape 不在此表中：它的完成文案必须结合返回值判断
     * （成功 / 可重试失败 / 被拒绝 / 重定向），见 webScrapeResultMessage。
     */
    private static final Map<String, String> RESULT_MESSAGES = Map.of(
            "read_file", "文件读取完成",
            "write_file", "文件写入完成",
            "generate_pdf", "PDF 生成完成",
            "download_resource", "资源下载完成",
            "terminate_task", "任务结束已确认",
            "demo_inventory_check", "库存查询完成"
    );

    @Override
    public String startMessage(String toolName) {
        return START_MESSAGES.getOrDefault(
                toolName,
                "正在调用工具：" + toolName
        );
    }

    @Override
    public String resultMessage(String toolName, String rawResult) {
        if ("web_search".equals(toolName)) {
            return webSearchResultMessage(rawResult);
        }

        if ("web_scrape".equals(toolName)) {
            return webScrapeResultMessage(rawResult);
        }

        return RESULT_MESSAGES.getOrDefault(toolName, "工具调用完成");
    }

    /**
     * 网页抓取结果摘要。
     *
     * 抓取工具用两种前缀区分结果：
     * - "网页抓取失败："：可重试的临时故障（网络异常、5xx）
     * - "网页抓取被拒绝："：不可重试的拒绝（SSRF 校验、4xx、重定向被拒）
     * - "页面发生重定向"：需要模型换最终地址再抓
     *
     * 这些情况都不能显示“网页抓取完成”，
     * 否则前端会误以为抓到了正文。
     */
    private String webScrapeResultMessage(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "网页抓取完成";
        }

        if (rawResult.contains("网页抓取被拒绝")) {
            return "网页抓取被拒绝：该地址不允许抓取";
        }

        if (rawResult.contains("网页抓取失败")) {
            return "网页抓取失败";
        }

        if (rawResult.contains("页面发生重定向")) {
            return "页面发生重定向，等待模型改用最终地址";
        }

        return "网页抓取完成";
    }

    /**
     * 网页搜索结果摘要：只暴露结果条数，不暴露具体内容。
     */
    private String webSearchResultMessage(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "网页搜索完成";
        }

        if (rawResult.startsWith("没有找到")) {
            return "网页搜索完成，未找到相关结果";
        }

        if (rawResult.startsWith("搜索工具执行失败")) {
            return "网页搜索失败";
        }

        // WebSearchTool 以 "\n---\n" 分隔每条结果，据此统计条数。
        int count = rawResult.split("\n---\n").length;

        return "网页搜索完成，获取 " + count + " 条结果";
    }
}
