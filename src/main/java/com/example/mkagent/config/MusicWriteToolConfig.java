package com.example.mkagent.config;

import com.example.mkagent.music.IdempotencyReplayService;
import com.example.mkagent.music.IdempotentWriteExecutor;
import com.example.mkagent.tools.IdempotentToolCallback;
import com.example.mkagent.tools.PlaylistWriteTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * 写工具的幂等装配。
 *
 * 两个 {@code @RequiresApproval} 写工具在这里被包成
 * {@link IdempotentToolCallback}：身份派生、单事务执行、冲突回放。
 *
 * <b>这个 Bean 不进入任何 AgentType 的白名单</b>——与批次 2 一样，
 * 写工具对模型仍然不可见（{@code WriteToolGuardTest} 会断言这一点）。
 * 先在这里把幂等链路装配好、测透，等白名单真正打开时不需要再改工具代码。
 *
 * 不包装在 {@code AgentToolProvider} 里，是因为那里是"按类型给白名单"的地方；
 * 把幂等包装混进去，会让"某个类型拿到的写工具到底有没有被保护"
 * 变成一个要看两层代码才能回答的问题。
 */
@Configuration
public class MusicWriteToolConfig {

    @Bean("musicWriteToolCallbacks")
    public ToolCallback[] musicWriteToolCallbacks(
            PlaylistWriteTool playlistWriteTool,
            IdempotentWriteExecutor idempotentWriteExecutor,
            IdempotencyReplayService idempotencyReplayService
    ) {
        return Arrays.stream(ToolCallbacks.from(playlistWriteTool))
                .map(callback -> new IdempotentToolCallback(
                        callback, idempotentWriteExecutor, idempotencyReplayService))
                .toArray(ToolCallback[]::new);
    }
}
