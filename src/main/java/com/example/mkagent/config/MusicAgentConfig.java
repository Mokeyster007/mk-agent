package com.example.mkagent.config;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.approval.ApprovalClaimService;
import com.example.mkagent.approval.ApprovalExecutionCompletionService;
import com.example.mkagent.approval.ApprovalPauseGateway;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.approval.RunHandoffLocks;
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
            @Qualifier("musicUserToolCallbacks") ToolCallback[] readTools,
            /*
             * 写工具 Bean 一直存在（幂等包装已装配好），但是否交给模型由
             * mkagent.agent.music.write-enabled 决定。生产默认 false。
             */
            @Qualifier("musicWriteToolCallbacks") ToolCallback[] writeTools,
            @Qualifier("agentExecutor") Executor agentExecutor,
            ToolEventMessageProvider toolEventMessageProvider,
            AgentTaskRegistry agentTaskRegistry,
            AgentRunRecorder agentRunRecorder,
            AgentConcurrencyGuard agentConcurrencyGuard,
            ApprovalToolPolicy approvalToolPolicy,
            ApprovalPauseGateway approvalPauseGateway,
            ApprovalClaimService approvalClaimService,
            ApprovalExecutionCompletionService approvalCompletionService,
            ApprovalSummaryBuilder approvalSummaryBuilder,
            RunHandoffLocks runHandoffLocks,
            @Value("${mkagent.agent.music.write-enabled:false}") boolean writeEnabled,
            @Value("${mkagent.agent.music.max-steps:8}") int maxSteps,
            @Value("${mkagent.agent.music.max-tool-calls:12}") int maxToolCalls,
            @Value("${mkagent.agent.music.timeout:2m}") Duration timeout,
            @Value("${mkagent.agent.music.sse-buffer:30s}") Duration sseBuffer,
            /*
             * 下面两个是**全局**开关（与 MkManus 同一份配置），不是 music.* 专属：
             * 它们描述的是"预算耗尽后怎么收尾"的行为，属于 Agent 通用语义，
             * 分成本地副本会让运维改了 application.yml 却只对 Manus 生效。
             */
            @Value("${mkagent.agent.budget-warning-steps:2}") int budgetWarningSteps,
            @Value("${mkagent.agent.force-final-answer:true}") boolean forceFinalAnswer
    ) {
        return build(
                AgentType.MUSIC_USER,
                "MusicUserAgent",
                /*
                 * 工具与提示词成对切换，不能只改一边（见 application.yml 注释）。
                 */
                writeEnabled
                        ? MusicAgent.USER_WRITE_SYSTEM_PROMPT
                        : MusicAgent.USER_SYSTEM_PROMPT,
                writeEnabled ? concat(readTools, writeTools) : readTools,
                chatModel,
                agentExecutor,
                toolEventMessageProvider,
                agentTaskRegistry,
                agentRunRecorder,
                agentConcurrencyGuard,
                approvalToolPolicy,
                approvalPauseGateway,
                approvalClaimService,
                approvalCompletionService,
                approvalSummaryBuilder,
                runHandoffLocks,
                maxSteps,
                maxToolCalls,
                timeout,
                sseBuffer,
                budgetWarningSteps,
                forceFinalAnswer
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
            ApprovalToolPolicy approvalToolPolicy,
            ApprovalPauseGateway approvalPauseGateway,
            ApprovalClaimService approvalClaimService,
            ApprovalExecutionCompletionService approvalCompletionService,
            ApprovalSummaryBuilder approvalSummaryBuilder,
            RunHandoffLocks runHandoffLocks,
            @Value("${mkagent.agent.music.max-steps:8}") int maxSteps,
            @Value("${mkagent.agent.music.max-tool-calls:12}") int maxToolCalls,
            @Value("${mkagent.agent.music.timeout:2m}") Duration timeout,
            @Value("${mkagent.agent.music.sse-buffer:30s}") Duration sseBuffer,
            /*
             * 下面两个是**全局**开关（与 MkManus 同一份配置），不是 music.* 专属：
             * 它们描述的是"预算耗尽后怎么收尾"的行为，属于 Agent 通用语义，
             * 分成本地副本会让运维改了 application.yml 却只对 Manus 生效。
             */
            @Value("${mkagent.agent.budget-warning-steps:2}") int budgetWarningSteps,
            @Value("${mkagent.agent.force-final-answer:true}") boolean forceFinalAnswer
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
                approvalToolPolicy,
                approvalPauseGateway,
                approvalClaimService,
                approvalCompletionService,
                approvalSummaryBuilder,
                runHandoffLocks,
                maxSteps,
                maxToolCalls,
                timeout,
                sseBuffer,
                budgetWarningSteps,
                forceFinalAnswer
        );
    }

    /**
     * 只读工具 + 写工具（写工具在后，模型选择时不受顺序影响）。
     *
     * 不在这里做去重或过滤：两个 Bean 的来源不同（一个走重试包装、
     * 一个是幂等包装），名字重复意味着装配出错，应该让
     * {@code ApprovalToolPolicy#verifyExposedTools} 或测试直接暴露，
     * 而不是在这里悄悄吞掉。
     */
    private static ToolCallback[] concat(ToolCallback[] read, ToolCallback[] write) {
        ToolCallback[] merged = new ToolCallback[read.length + write.length];
        System.arraycopy(read, 0, merged, 0, read.length);
        System.arraycopy(write, 0, merged, read.length, write.length);
        return merged;
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
            ApprovalToolPolicy approvalToolPolicy,
            ApprovalPauseGateway approvalPauseGateway,
            ApprovalClaimService approvalClaimService,
            ApprovalExecutionCompletionService approvalCompletionService,
            ApprovalSummaryBuilder approvalSummaryBuilder,
            RunHandoffLocks runHandoffLocks,
            int maxSteps,
            int maxToolCalls,
            Duration timeout,
            Duration sseBuffer,
            int budgetWarningSteps,
            boolean forceFinalAnswer
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

        /*
         * 审批链路（M3，批次 1）：只装配"暂停"所需的三个组件。
         * approve/reject/resume 在批次 2 接入；
         * 写工具本身仍不在白名单里，因此这批不会真的暂停任何线上请求。
         */
        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(approvalPauseGateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);

        /*
         * 批次 2：恢复执行的启动标记与收尾都走 ApprovalClaimService。
         * 不装配的后果是"恢复后审批永远停在 CLAIMED"——
         * 任务能跑完，但用户查到的审批状态永远是执行中。
         */
        agent.setApprovalClaimService(approvalClaimService);

        /*
         * 批次 2：恢复执行的终态与审批收尾同事务提交。
         * 不装配会退化为"先写 run 终态、再写审批 FINISHED"，
         * 顺序仍正确但不再原子。
         */
        agent.setApprovalCompletionService(approvalCompletionService);

        agent.applyBudget(maxSteps, maxToolCalls, timeout, sseBuffer);

        /*
         * 全局收尾策略：与 MkManus 共用 mkagent.agent.* 两个开关。
         * 不传的后果很隐蔽——音乐入口会一直用代码默认值（2 / true），
         * 运维把 yml 改成 false 时只有 Manus 变，音乐入口照旧多花一次调用。
         */
        agent.setBudgetWarningSteps(budgetWarningSteps);
        agent.setForceFinalAnswer(forceFinalAnswer);

        return agent;
    }
}
