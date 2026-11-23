package com.example.mkagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认工具事件消息提供者。
 *
 * 为每个已知工具维护一组用户可读文案；未登记的工具走通用兜底文案。
 * 所有文案都经过脱敏：不包含路径、密钥、完整原始结果。
 */
@Component
public class DefaultToolEventMessageProvider
        implements ToolEventMessageProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String, String> START_MESSAGES = Map.ofEntries(
            Map.entry("web_search", "正在搜索互联网公开资料"),
            Map.entry("web_scrape", "正在抓取网页正文"),
            Map.entry("read_file", "正在读取文件内容"),
            Map.entry("write_file", "正在写入文件"),
            Map.entry("generate_pdf", "正在生成 PDF 文件"),
            Map.entry("download_resource", "正在下载公开资源"),
            Map.entry("terminate_task", "任务收尾中"),
            Map.entry("demo_inventory_check", "正在查询库存信息"),
            // 电商导购与购物车/结算工具
            Map.entry("search_products", "正在按条件筛选商品"),
            Map.entry("compare_products", "正在对比商品规格"),
            Map.entry("get_my_cart", "正在查看购物车"),
            Map.entry("add_to_cart", "正在加入购物车"),
            Map.entry("update_cart_item", "正在更新购物车数量"),
            Map.entry("remove_cart_item", "正在移除购物车商品"),
            Map.entry("preview_checkout", "正在计算结算预览"),
            Map.entry("submit_checkout_order", "正在提交确认订单"),
            Map.entry("get_my_recent_orders", "正在查询我的订单"),
            // 电商售后工具
            Map.entry("list_my_signed_orders", "正在查询已签收订单与可退额度"),
            Map.entry("get_my_order_detail", "正在查看订单详情与退货资格"),
            Map.entry("check_return_eligibility", "正在校验退货资格"),
            Map.entry("list_my_return_requests", "正在查询我的退货申请"),
            Map.entry("preview_return", "正在试算退货金额与资格"),
            Map.entry("submit_return", "正在提交退货申请")
    );

    private static final Map<String, String> RESULT_MESSAGES = Map.ofEntries(
            Map.entry("read_file", "文件读取完成"),
            Map.entry("write_file", "文件写入完成"),
            Map.entry("generate_pdf", "PDF 生成完成"),
            Map.entry("download_resource", "资源下载完成"),
            Map.entry("terminate_task", "任务结束已确认"),
            Map.entry("demo_inventory_check", "库存查询完成"),
            Map.entry("compare_products", "商品对比已生成"),
            Map.entry("get_my_cart", "购物车读取完成"),
            Map.entry("add_to_cart", "已更新购物车"),
            Map.entry("update_cart_item", "已更新购物车数量"),
            Map.entry("remove_cart_item", "已从购物车移除"),
            Map.entry("get_my_recent_orders", "订单列表读取完成"),
            Map.entry("get_my_order_detail", "订单详情读取完成")
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
        String result = ToolResultTexts.decode(rawResult);

        if ("web_search".equals(toolName)) {
            return webSearchResultMessage(result);
        }

        if ("web_scrape".equals(toolName)) {
            return webScrapeResultMessage(result);
        }

        String envelopeMsg = shopEnvelopeMessage(toolName, result);
        if (envelopeMsg != null) {
            return envelopeMsg;
        }

        return RESULT_MESSAGES.getOrDefault(toolName, "工具调用完成");
    }

    private String shopEnvelopeMessage(String toolName, String rawResult) {
        return switch (toolName) {
            case "search_products" -> {
                JsonNode root = parseEnvelope(rawResult);
                if (root == null) {
                    yield "商品筛选完成";
                }
                if (!root.path("ok").asBoolean(false)) {
                    yield "商品筛选未成功";
                }
                int count = root.path("data").path("items").size();
                yield count > 0 ? "找到 " + count + " 款符合条件的商品" : "没有找到满足全部硬条件的商品";
            }
            case "preview_checkout" -> {
                JsonNode root = parseEnvelope(rawResult);
                if (root == null || !root.path("ok").asBoolean(false)) {
                    yield "结算预览未能生成";
                }
                yield root.path("data").path("eligible").asBoolean(false)
                        ? "结算预览已生成（待确认下单）"
                        : "当前不满足结算条件";
            }
            case "submit_checkout_order" -> {
                JsonNode root = parseEnvelope(rawResult);
                if (root == null) {
                    yield "下单结果已返回";
                }
                yield root.path("ok").asBoolean(false) ? "订单已创建" : "下单未成功（未创建订单）";
            }
            case "list_my_signed_orders" -> {
                JsonNode root = parseEnvelope(rawResult);
                if (root == null || !root.path("ok").asBoolean(false)) {
                    yield "暂无已签收订单";
                }
                yield "已查询到 " + root.path("data").size() + " 笔已签收订单";
            }
            case "list_my_return_requests" -> {
                JsonNode root = parseEnvelope(rawResult);
                if (root == null || !root.path("ok").asBoolean(false)) {
                    yield "退货申请查询未成功";
                }
                yield "已查询到 " + root.path("data").size() + " 条退货申请";
            }
            case "check_return_eligibility", "preview_return" -> {
                JsonNode root = parseEnvelope(rawResult);
                if (root == null || !root.path("ok").asBoolean(false)) {
                    yield "退货资格校验未通过";
                }
                yield root.path("data").path("eligible").asBoolean(false)
                        ? "资格试算通过（尚未提交）"
                        : "当前不满足退货条件";
            }
            case "submit_return" -> {
                JsonNode root = parseEnvelope(rawResult);
                if (root == null) {
                    yield "退货提交已返回";
                }
                yield root.path("ok").asBoolean(false) ? "退货申请已创建" : "退货提交未成功（未创建退货单）";
            }
            default -> null;
        };
    }

    private static JsonNode parseEnvelope(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(rawResult);
            if (node != null && node.isTextual()) {
                node = OBJECT_MAPPER.readTree(node.asText());
            }
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String webScrapeResultMessage(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "网页抓取完成";
        }
        if (rawResult.contains(ToolResultMarkers.TARGET_BLOCKED)) {
            return "该站点连续失败，已跳过本次抓取";
        }
        if (rawResult.contains(ToolResultMarkers.EXHAUSTED)) {
            return "网页抓取多次重试后仍失败";
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

    private String webSearchResultMessage(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "网页搜索完成";
        }
        if (rawResult.contains(ToolResultMarkers.TARGET_BLOCKED)) {
            return "该站点连续失败，已跳过本次搜索";
        }
        if (rawResult.contains(ToolResultMarkers.EXHAUSTED)) {
            return "网页搜索多次重试后仍失败";
        }
        if (rawResult.startsWith("没有找到")) {
            return "网页搜索完成，未找到相关结果";
        }
        if (rawResult.startsWith("搜索工具执行失败")) {
            return "网页搜索失败";
        }
        int count = rawResult.split("\n---\n").length;
        return "网页搜索完成，获取 " + count + " 条结果";
    }
}
