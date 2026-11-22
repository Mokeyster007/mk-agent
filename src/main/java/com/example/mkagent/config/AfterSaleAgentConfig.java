package com.example.mkagent.config;

import com.example.mkagent.agent.AfterSaleAgent;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.approval.ApprovalClaimService;
import com.example.mkagent.approval.ApprovalExecutionCompletionService;
import com.example.mkagent.approval.ApprovalPauseGateway;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.approval.RunHandoffLocks;
import com.example.mkagent.conversation.ConversationService;
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
 * 售后 Agent 的装配。
 *
 * <p>与 {@link MusicAgentConfig} 是同一个模板：手动 new 而不是 {@code @Component}，
 * 因为装配需要注入十几个协作者，且"用哪个工具白名单、哪套提示词"必须显式写在装配处，
 * 而不是藏在类的字段默认值里。
 *
 * <p>审批链路装配的是**完整的一套**（暂停 + 恢复 + 收尾 + 交接锁）：
 * 只装"暂停"会让确认后的执行停在 CLAIMED，用户查到的审批状态永远是执行中。
 *
 * <p>写工具对模型可见（{@code submit_return} 在 AFTER_SALES 白名单里），
 * 这是与音乐写工具的关键差别：音乐写工具"对模型不可见"，
 * 售后写工具"可见但必须先确认"。两者都靠 {@code @RequiresApproval} 表达，
 * 差别只在是否进入白名单。
 */
@Configuration
public class AfterSaleAgentConfig {

    @Bean("afterSaleAgent")
    public AfterSaleAgent afterSaleAgent(
            ChatModel chatModel,
            @Qualifier("afterSaleToolCallbacks") ToolCallback[] tools,
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
            ConversationService conversationService,
            @Value("${mkagent.agent.aftersale.max-steps:8}") int maxSteps,
            @Value("${mkagent.agent.aftersale.max-tool-calls:12}") int maxToolCalls,
            @Value("${mkagent.agent.aftersale.timeout:2m}") Duration timeout,
            @Value("${mkagent.agent.aftersale.sse-buffer:30s}") Duration sseBuffer,
            /*
             * 全局收尾策略（与 Manus / 音乐共用 mkagent.agent.*）：
             * 它们描述"预算耗尽后怎么收尾"，属于 Agent 通用语义，
             * 不能因为售后是新入口就各写一份默认值。
             */
            @Value("${mkagent.agent.budget-warning-steps:2}") int budgetWarningSteps,
            @Value("${mkagent.agent.force-final-answer:true}") boolean forceFinalAnswer
    ) {
        AfterSaleAgent agent = new AfterSaleAgent(
                chatModel, tools, agentExecutor, toolEventMessageProvider);

        // 取消 / 持久化 / 并发闸门：与其它 Agent 完全一致。
        agent.setTaskRegistry(agentTaskRegistry);
        agent.setRunRecorder(agentRunRecorder);
        agent.setConcurrencyGuard(agentConcurrencyGuard);

        // 审批：暂停 + 摘要 + 交接锁。
        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(approvalPauseGateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);

        // 会话：同一窗口连续追问"哪一单 / 几件 / 为什么"。
        agent.setConversationService(conversationService);

        // 恢复执行：启动标记与终态收尾。
        agent.setApprovalClaimService(approvalClaimService);
        agent.setApprovalCompletionService(approvalCompletionService);

        agent.applyBudget(maxSteps, maxToolCalls, timeout, sseBuffer);
        agent.setBudgetWarningSteps(budgetWarningSteps);
        agent.setForceFinalAnswer(forceFinalAnswer);

        return agent;
    }
}
