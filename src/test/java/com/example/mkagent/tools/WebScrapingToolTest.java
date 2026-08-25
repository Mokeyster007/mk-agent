package com.example.mkagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WebScrapingToolTest {


    @Test
    public void webscrapingtest() {
        WebScrapingTool tool = new WebScrapingTool();
        String url = "https://www.bilibili.com";
        String result = tool.scrapeWebPage(url);
        assertNotNull(result);

    }
}