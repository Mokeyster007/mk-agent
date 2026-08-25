package com.example.mkagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class WebSearchToolTest {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Test
    void webSearchTest() {
        assertNotNull(searchApiKey, "search-api.api-key 未成功加载");

        WebSearchTool tool = new WebSearchTool(searchApiKey);
        String result = tool.searchWeb("B站官网");

        System.out.println(result);
        assertNotNull(result);
    }
}