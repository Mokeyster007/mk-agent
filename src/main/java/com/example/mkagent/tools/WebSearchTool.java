package com.example.mkagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 默认搜索引擎：baidu。
     *
     * 保持默认值不变是为了让既有行为、成本与配额口径完全不动；
     * 需要查英文官方文档时把 search-api.engine 改成 google
     * （原因见 docs/COST.md 的「搜索引擎」一节）。
     */
    private static final String DEFAULT_ENGINE = "baidu";

    private final String apiKey;

    /**
     * 搜索引擎（baidu / google），来自 search-api.engine。
     */
    private final String engine;

    /**
     * 测试与手工构造入口：只给 api-key，engine 用默认值。
     *
     * 刻意不加 @Value 注解：这个构造器只服务于测试，
     * 若它也带 @Value，Spring 会看到两个候选构造器而无法选择
     * （启动报 "No default constructor found"）。
     */
    public WebSearchTool(String apiKey) {
        this(apiKey, DEFAULT_ENGINE);
    }

    /**
     * Spring 注入入口：api-key 与 engine 都来自配置。
     *
     * 凭据缺失时在装配阶段直接失败，并给出"该设哪个环境变量"的信息。
     * 否则 {@code @Value("${search-api.api-key}")} 只会抛
     * "Could not resolve placeholder"，看不出是凭据没注入还是属性名写错。
     * 这里用 {@code :} 给一个空默认值，把错误交给下面这行显式校验，
     * 保证启动即失败（而不是等第一次搜索才 401）。
     */
    @Autowired
    public WebSearchTool(
            @Value("${search-api.api-key:}") String apiKey,
            @Value("${search-api.engine:baidu}") String engine
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置搜索服务凭据：请设置环境变量 MKAGENT_SEARCH_API_KEY，"
                            + "或配置 search-api.api-key。"
                            + "本地开发可写入 config/application-local.yml"
                            + "（file:./config/，不入库），详见 docs/SECURITY.md 第 7 节。"
            );
        }
        this.apiKey = apiKey;
        // 配了空串也回退默认值：空 engine 会被上游当成非法参数。
        this.engine = (engine == null || engine.isBlank())
                ? DEFAULT_ENGINE
                : engine.trim();
    }

    /**
     * 当前使用的搜索引擎（诊断与测试断言用）。
     */
    public String getEngine() {
        return engine;
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
        Map<String, Object> paramMap = buildRequestParams(query);

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

    /**
     * 构造搜索请求参数。
     *
     * 单独抽出来是为了能**离线**断言 engine 取自配置：
     * 直接断言 HTTP 请求会真的联网，而这里只验证参数拼装，
     * 既覆盖了真实逻辑（call 里用的就是这份 map），又不需要网络。
     */
    Map<String, Object> buildRequestParams(String query) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", engine);
        return paramMap;
    }
}