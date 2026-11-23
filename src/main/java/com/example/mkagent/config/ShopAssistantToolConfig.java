package com.example.mkagent.config;

import com.example.mkagent.idempotency.IdempotencyReplayService;
import com.example.mkagent.idempotency.IdempotentWriteExecutor;
import com.example.mkagent.tools.IdempotentToolCallback;
import com.example.mkagent.tools.ShopCheckoutWriteTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * 导购下单写工具的幂等装配（与售后共用同一套 {@link IdempotentToolCallback}）。
 */
@Configuration
public class ShopAssistantToolConfig {

    @Bean("shopCheckoutWriteToolCallbacks")
    public ToolCallback[] shopCheckoutWriteToolCallbacks(
            ShopCheckoutWriteTool shopCheckoutWriteTool,
            IdempotentWriteExecutor idempotentWriteExecutor,
            IdempotencyReplayService idempotencyReplayService
    ) {
        return Arrays.stream(ToolCallbacks.from(shopCheckoutWriteTool))
                .map(callback -> new IdempotentToolCallback(
                        callback, idempotentWriteExecutor, idempotencyReplayService))
                .toArray(ToolCallback[]::new);
    }
}
