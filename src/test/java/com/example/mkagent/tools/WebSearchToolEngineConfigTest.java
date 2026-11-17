package com.example.mkagent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSearchTool 请求参数构造（离线，不联网）。
 *
 * 真实运行暴露的问题：engine 被写死成 "baidu"，
 * 查英文官方文档（Spring AI / OpenAI docs）时结果质量明显偏低，
 * 想切 google 只能改代码。
 *
 * 现在 engine 来自 search-api.engine，默认值保持 baidu。
 *
 * 测试策略：断言 buildRequestParams（call 里实际使用的那份 map），
 * 而不是断言 HTTP 请求——后者必须联网。
 */
class WebSearchToolEngineConfigTest {

    private static final String API_KEY = "test-api-key";

    @Test
    @DisplayName("未配置 engine 时默认 baidu（行为不变）")
    void defaultsToBaiduWhenUnconfigured() {
        WebSearchTool tool = new WebSearchTool(API_KEY);

        assertEquals("baidu", tool.getEngine());
        assertEquals("baidu", tool.buildRequestParams("spring ai").get("engine"));
    }

    @Test
    @DisplayName("engine 取自配置，可切换为 google")
    void engineComesFromConfiguration() {
        WebSearchTool tool = new WebSearchTool(API_KEY, "google");

        assertEquals("google", tool.getEngine());

        Map<String, Object> params = tool.buildRequestParams("spring ai tool calling");
        assertEquals("google", params.get("engine"),
                "请求参数里的 engine 必须取自配置");
        assertEquals("spring ai tool calling", params.get("q"));
        assertEquals(API_KEY, params.get("api_key"));
    }

    @Test
    @DisplayName("engine 为空/空白时回退 baidu，不把空参数发给上游")
    void blankEngineFallsBackToDefault() {
        assertEquals("baidu", new WebSearchTool(API_KEY, null).getEngine());
        assertEquals("baidu", new WebSearchTool(API_KEY, "").getEngine());
        assertEquals("baidu", new WebSearchTool(API_KEY, "   ").getEngine());
    }

    @Test
    @DisplayName("engine 两端空格被去掉")
    void engineIsTrimmed() {
        WebSearchTool tool = new WebSearchTool(API_KEY, "  google  ");

        assertEquals("google", tool.getEngine());
        assertFalse(tool.buildRequestParams("x").get("engine").toString().contains(" "));
    }

    @Test
    @DisplayName("搜索凭据为空：装配阶段直接失败，报错点名环境变量")
    void blankApiKeyFailsFastWithActionableMessage() {
        for (String blank : new String[]{"", "   "}) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> new WebSearchTool(blank, "baidu"),
                    "空凭据必须启动即失败，不能等到第一次搜索才 401"
            );
            assertTrue(error.getMessage().contains("MKAGENT_SEARCH_API_KEY"),
                    "报错必须告诉运维该设哪个环境变量");
            assertTrue(error.getMessage().contains("search-api.api-key"),
                    "报错必须同时给出属性名，便于排查是不是配置写错");
        }
    }
}
