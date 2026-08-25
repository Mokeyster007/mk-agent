package com.example.mkagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公开网页搜索工具。
 */
@Component
public class WebSearchTool {

    private static final String SEARCH_API_URL =
            "https://www.searchapi.io/api/v1/search";

    private final String apiKey;

    public WebSearchTool(
            @Value("${search-api.api-key}") String apiKey
    ) {
        this.apiKey = apiKey;
    }

    /**
     * 工具名称必须与：
     * 1. System Prompt
     * 2. 测试任务
     * 3. 日志判断
     * 保持一致。
     */
    @Tool(
            name = "web_search",
            description = """
                    搜索互联网公开资料。

                    当用户要求搜索、查询最新资料、获取网页来源、
                    查找官方文档或需要实时公开信息时，必须调用此工具。

                    输入关键词后，工具会返回搜索结果的标题、链接和摘要。
                    """
    )
    public String searchWeb(
            @ToolParam(
                    description = """
                            搜索关键词。请使用完整、具体的关键词，
                            例如：Spring AI Tool Calling 官方文档
                            """
            )
            String query
    ) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");

        try {
            String response = HttpUtil.get(
                    SEARCH_API_URL,
                    paramMap
            );

            JSONObject jsonObject = JSONUtil.parseObj(response);

            JSONArray organicResults =
                    jsonObject.getJSONArray("organic_results");

            if (organicResults == null || organicResults.isEmpty()) {
                return "没有找到与关键词相关的搜索结果：" + query;
            }

            List<Object> results = organicResults.subList(
                    0,
                    Math.min(5, organicResults.size())
            );

            return results.stream()
                    .map(item -> {
                        JSONObject result = JSONUtil.parseObj(item);

                        String title = result.getStr("title", "");
                        String link = result.getStr("link", "");
                        String snippet = result.getStr("snippet", "");

                        return """
                                标题：%s
                                链接：%s
                                摘要：%s
                                """.formatted(title, link, snippet);
                    })
                    .collect(Collectors.joining("\n---\n"));

        } catch (Exception e) {
            return "搜索工具执行失败：" + e.getMessage();
        }
    }
}