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
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按 Agent 类型提供工具白名单（最小权限原则）。
 *
 * <p>音乐模块退场后，本类不再注册任何音乐工具白名单；
 * 电商导购（{@code SHOP_ASSISTANT}）与售后（{@code AFTER_SALES}）各自拥有明确的工具边界：
 * <ul>
 *   <li>{@code SHOP_ASSISTANT}：商品硬条件检索/比较、共享购物车读写、结算预览、最近订单只读，
 *       唯一订单写工具 {@code submit_checkout_order}（审批 + 幂等）；</li>
 *   <li>{@code AFTER_SALES}：签收订单/额度/申请只读与试算，
 *       唯一退货写工具 {@code submit_return}（审批 + 幂等）。</li>
 * </ul>
 */
@Component
public class AgentToolProvider {

    private final Map<AgentType, List<Object>> toolBeansByType;

    private final ToolRetryWrapper toolRetryWrapper;

    private final ToolCallback[] afterSaleWriteCallbacks;

    private final ToolCallback[] shopCheckoutWriteCallbacks;

    public AgentToolProvider(
            WebSearchTool webSearchTool,
            WebScrapingTool webScrapingTool,
            TerminateTool terminateTool,
            FileOperationTool fileOperationTool,
            ResourceDownloadTool resourceDownloadTool,
            PDFGenerationTool pdfGenerationTool,
            AfterSaleQueryTool afterSaleQueryTool,
            @Qualifier("afterSaleWriteToolCallbacks") ToolCallback[] afterSaleWriteCallbacks,
            ShopAssistantQueryTool shopAssistantQueryTool,
            ShopCartTool shopCartTool,
            @Qualifier("shopCheckoutWriteToolCallbacks") ToolCallback[] shopCheckoutWriteCallbacks,
            ToolRetryWrapper toolRetryWrapper
    ) {
        this.toolRetryWrapper = toolRetryWrapper;
        this.afterSaleWriteCallbacks = afterSaleWriteCallbacks == null
                ? new ToolCallback[0]
                : afterSaleWriteCallbacks;
        this.shopCheckoutWriteCallbacks = shopCheckoutWriteCallbacks == null
                ? new ToolCallback[0]
                : shopCheckoutWriteCallbacks;

        this.toolBeansByType = Map.of(
                AgentType.CHAT, List.of(
                        webSearchTool
                ),
                AgentType.MANUS, List.of(
                        webSearchTool,
                        webScrapingTool,
                        terminateTool
                ),
                AgentType.FILE, List.of(
                        fileOperationTool,
                        resourceDownloadTool,
                        pdfGenerationTool
                ),
                AgentType.EVAL, List.of(
                        webSearchTool,
                        webScrapingTool,
                        terminateTool
                ),
                AgentType.AFTER_SALES, List.of(
                        afterSaleQueryTool,
                        terminateTool
                ),
                AgentType.SHOP_ASSISTANT, List.of(
                        shopAssistantQueryTool,
                        shopCartTool,
                        terminateTool
                )
        );
    }

    public ToolCallback[] getTools(AgentType agentType) {
        List<Object> tools = toolBeansByType.get(agentType);

        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException(
                    "AgentType 未配置任何工具：" + agentType
            );
        }

        ToolCallback[] readCallbacks = ToolCallbacks.from(tools.toArray());

        ToolCallback[] all = switch (agentType) {
            case AFTER_SALES -> concat(readCallbacks, afterSaleWriteCallbacks);
            case SHOP_ASSISTANT -> concat(readCallbacks, shopCheckoutWriteCallbacks);
            default -> readCallbacks;
        };

        return toolRetryWrapper.wrap(all);
    }

    private static ToolCallback[] concat(ToolCallback[] read, ToolCallback[] write) {
        if (write.length == 0) {
            return read;
        }
        ToolCallback[] merged = new ToolCallback[read.length + write.length];
        System.arraycopy(read, 0, merged, 0, read.length);
        System.arraycopy(write, 0, merged, read.length, write.length);
        return merged;
    }
}
