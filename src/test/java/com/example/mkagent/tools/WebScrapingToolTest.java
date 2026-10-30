package com.example.mkagent.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实网页抓取冒烟测试。
 *
 * 会真实访问外网站点，属于 live 测试：
 * 默认 mvn test 不执行，需 -Plive 显式开启。
 * 抓取逻辑的确定性断言在 WebScrapingToolHttpHandlingUnitTest /
 * WebScrapingToolRetrySemanticsTest 中，那两个测试不触网。
 */
@SpringBootTest
@Tag("live")
class WebScrapingToolTest {


    @Test
    public void webscrapingtest() {
        WebScrapingTool tool = new WebScrapingTool();
        String url = "https://www.bilibili.com";
        String result = tool.scrapeWebPage(url);
        assertNotNull(result);

    }
}