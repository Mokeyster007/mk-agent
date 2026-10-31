package com.example.mkagent.config;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * 音乐 Agent 的装配（M2e）。
 *
 * 为什么在配置类里手动 new，而不是给 MusicAgent 加 @Component：
 * 同一个类需要**两个**不同配置的实例（不同工具白名单、不同提示词、不同 AgentType）。
 * 用 @Component 只能得到一个，或者要靠 @Qualifier + 继承造出两个子类，
 * 反而把"一个类两种配置"的意图藏起来。
 *
 * 两个实例都是单例：BaseAgent 的 per-run 状态全在 AgentRunContext 里，
 * 实例本身无状态，共用单例不会串号（并发由 AgentConcurrencyGuard 控制）。
 *
 * agentType 只由**入口**决定：{@code /ai/music/chat} → MUSIC_USER，
 * {@code /ai/music/admin/chat} → MUSIC_ADMIN。
 * 请求参数、请求头、提示词都不能改变它。
 */
@Configuration
public class MusicAgentConfig {

    @Bean("musicUserAgent")
    public MusicAgent musicUserAgent(
            ChatModel chatModel,
            @Qualifier("musicUserToolCallbacks") ToolCallback[] tools,
            @Qualifier("agentExecutor") Executor agentExecutor,
            ToolEventMessageProvider toolEventMessageProvider,
            AgentTaskRegistry agentTaskRegistry,
            AgentRunRecorder agentRunRecorder,
            AgentConcurrencyGuard agentConcurrencyGuard,
            @Value("${mkagent.agent.music.max-steps:8}") int maxSteps,
            @Value("${mkagent.agent.music.max-tool-calls:12}") int maxToolCalls,
            @Value("${mkagent.agent.music.timeout:2m}") Duration timeout,
            @Value("${mkagent.agent.music.sse-buffer:30s}") Duration sseBuffer
    ) {
        return build(
                AgentType.MUSIC_USER,
                "MusicUserAgent",
                MusicAgent.USER_SYSTEM_PROMPT,
                tools,
                chatModel,
                agentExecutor,
                toolEventMessageProvider,
                agentTaskRegistry,
                agentRunRecorder,
                agentConcurrencyGuard,
                maxSteps,
                maxToolCalls,
                timeout,
                sseBuffer
        );
    }

    @Bean("musicAdminAgent")
    public MusicAgent musicAdminAgent(
            ChatModel chatModel,
            @Qualifier("musicAdminToolCallbacks") ToolCallback[] tools,
            @Qualifier("agentExecutor") Executor agentExecutor,
            ToolEventMessageProvider toolEventMessageProvider,
            AgentTaskRegistry agentTaskRegistry,
            AgentRunRecorder agentRunRecorder,
            AgentConcurrencyGuard agentConcurrencyGuard,
            @Value("${mkagent.agent.music.max-steps:8}") int maxSteps,
            @Value("${mkagent.agent.music.max-tool-calls:12}") int maxToolCalls,
            @Value("${mkagent.agent.music.timeout:2m}") Duration timeout,
            @Value("${mkagent.agent.music.sse-buffer:30s}") Duration sseBuffer
    ) {
        return build(
                AgentType.MUSIC_ADMIN,
                "MusicAdminAgent",
                MusicAgent.ADMIN_SYSTEM_PROMPT,
                tools,
                chatModel,
                agentExecutor,
                toolEventMessageProvider,
                agentTaskRegistry,
                agentRunRecorder,
                agentConcurrencyGuard,
                maxSteps,
                maxToolCalls,
                timeout,
                sseBuffer
        );
    }

    /**
     * 两个 Bean 的共同装配步骤：注册表 / 持久化 / 并发闸门 / 预算。
     *
     * 抽成一个方法而不是复制两遍：漏配一处（比如忘了 setRunRecorder）
     * 只会在运行到那一条路径时才暴露，而且很难看出是"少了一行"。
     */
    private MusicAgent build(
            AgentType agentType,
            String name,
            String systemPrompt,
            ToolCallback[] tools,
            ChatModel chatModel,
            Executor agentExecutor,
            ToolEventMessageProvider toolEventMessageProvider,
            AgentTaskRegistry agentTaskRegistry,
            AgentRunRecorder agentRunRecorder,
            AgentConcurrencyGuard agentConcurrencyGuard,
            int maxSteps,
            int maxToolCalls,
            Duration timeout,
            Duration sseBuffer
    ) {
        MusicAgent agent = new MusicAgent(
                chatModel,
                tools,
                agentExecutor,
                toolEventMessageProvider,
                agentType,
                name,
                systemPrompt
        );

        // 与 MkManus 相同的三件套：取消、持久化、并发闸门。
        agent.setTaskRegistry(agentTaskRegistry);
        agent.setRunRecorder(agentRunRecorder);
        agent.setConcurrencyGuard(agentConcurrencyGuard);

        agent.applyBudget(maxSteps, maxToolCalls, timeout, sseBuffer);

        return agent;
    }
}
