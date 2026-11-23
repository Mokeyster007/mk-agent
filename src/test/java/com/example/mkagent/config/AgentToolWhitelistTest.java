package com.example.mkagent.config;

import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.ToolRetryWrapper;
import com.example.mkagent.tools.AfterSaleQueryTool;
import com.example.mkagent.tools.FileOperationTool;
import com.example.mkagent.tools.PDFGenerationTool;
import com.example.mkagent.tools.ResourceDownloadTool;
import com.example.mkagent.tools.ShopAssistantQueryTool;
import com.example.mkagent.tools.ShopCartTool;
import com.example.mkagent.tools.TerminateTool;
import com.example.mkagent.tools.WebScrapingTool;
import com.example.mkagent.tools.WebSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AgentToolProvider 工具白名单单元测试。
 *
 * 验证每种 Agent 类型拿到的工具集合严格等于白名单（最小权限原则），
 * 且音乐 Agent 类型不再注册任何工具能力。
 */
class AgentToolWhitelistTest {

    private final AgentToolProvider provider = new AgentToolProvider(
            new WebSearchTool("test-api-key"),
            new WebScrapingTool(),
            new TerminateTool(),
            new FileOperationTool(),
            new ResourceDownloadTool(),
            new PDFGenerationTool(),
            new AfterSaleQueryTool(null),
            new ToolCallback[0],
            new ShopAssistantQueryTool(null, null, null, null),
            new ShopCartTool(null, null),
            new ToolCallback[0],
            new ToolRetryWrapper(
                    true,
                    3,
                    200,
                    2.0,
                    "web_search,web_scrape",
                    "web_search:搜索工具执行失败,web_scrape:网页抓取失败"
            )
    );

    @Test
    void chatAgentOnlyHasWebSearchAndNoFileTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.CHAT));

        assertEquals(Set.of("web_search"), names);
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("download_resource"));
        assertFalse(names.contains("generate_pdf"));
    }

    @Test
    void manusAgentHasNoHighRiskFileTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.MANUS));

        assertEquals(
                Set.of("web_search", "web_scrape", "terminate_task"),
                names
        );
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("download_resource"));
        assertFalse(names.contains("generate_pdf"));
    }

    @Test
    void fileAgentHasFileToolsButNoSearchTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.FILE));

        assertEquals(
                Set.of("read_file", "write_file", "download_resource", "generate_pdf"),
                names
        );
        assertFalse(names.contains("web_search"));
        assertFalse(names.contains("web_scrape"));
    }

    @Test
    void evalAgentReusesManusReadOnlyTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.EVAL));

        assertEquals(
                Set.of("web_search", "web_scrape", "terminate_task"),
                names
        );
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("download_resource"));
        assertFalse(names.contains("generate_pdf"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void musicAgentsHaveNoRegisteredToolsAfterRetirement() {
        assertThrows(IllegalArgumentException.class,
                () -> provider.getTools(AgentType.MUSIC_USER));
        assertThrows(IllegalArgumentException.class,
                () -> provider.getTools(AgentType.MUSIC_ADMIN));
    }

    @Test
    void shopAssistantHasCatalogCartPreviewAndOrderReadToolsOnly() {
        Set<String> names = toolNames(provider.getTools(AgentType.SHOP_ASSISTANT));

        assertEquals(
                Set.of("search_products", "compare_products",
                        "preview_checkout", "get_my_recent_orders",
                        "get_my_cart", "add_to_cart", "update_cart_item", "remove_cart_item",
                        "terminate_task"),
                names
        );

        assertFalse(names.contains("submit_return"));
        assertFalse(names.contains("search_songs"));
        assertFalse(names.contains("create_playlist"));
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("web_search"));
    }

    @Test
    void afterSaleAgentHasOnlyAfterSaleTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.AFTER_SALES));

        assertEquals(
                Set.of("list_my_signed_orders", "get_my_order_detail",
                        "check_return_eligibility", "list_my_return_requests",
                        "preview_return", "terminate_task"),
                names
        );

        assertFalse(names.contains("search_products"));
        assertFalse(names.contains("add_to_cart"));
        assertFalse(names.contains("submit_checkout_order"));
        assertFalse(names.contains("search_songs"));
        assertFalse(names.contains("create_playlist"));
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("web_search"));
    }

    private static Set<String> toolNames(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
