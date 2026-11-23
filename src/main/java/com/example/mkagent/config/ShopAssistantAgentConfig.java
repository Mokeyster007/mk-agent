package com.example.mkagent.config;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.ShopAssistantAgent;
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
 * 电商导购与下单助手 Agent 装配。
 */
@Configuration
public class ShopAssistantAgentConfig {

    @Bean("shopAssistantAgent")
    public ShopAssistantAgent shopAssistantAgent(
            ChatModel chatModel,
            @Qualifier("shopAssistantToolCallbacks") ToolCallback[] tools,
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
            @Value("${mkagent.agent.shop.max-steps:8}") int maxSteps,
            @Value("${mkagent.agent.shop.max-tool-calls:12}") int maxToolCalls,
            @Value("${mkagent.agent.shop.timeout:2m}") Duration timeout,
            @Value("${mkagent.agent.shop.sse-buffer:30s}") Duration sseBuffer,
            @Value("${mkagent.agent.budget-warning-steps:2}") int budgetWarningSteps,
            @Value("${mkagent.agent.force-final-answer:true}") boolean forceFinalAnswer
    ) {
        ShopAssistantAgent agent = new ShopAssistantAgent(
                chatModel, tools, agentExecutor, toolEventMessageProvider);

        agent.setTaskRegistry(agentTaskRegistry);
        agent.setRunRecorder(agentRunRecorder);
        agent.setConcurrencyGuard(agentConcurrencyGuard);

        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(approvalPauseGateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);

        agent.setConversationService(conversationService);
        agent.setApprovalClaimService(approvalClaimService);
        agent.setApprovalCompletionService(approvalCompletionService);

        agent.applyBudget(maxSteps, maxToolCalls, timeout, sseBuffer);
        agent.setBudgetWarningSteps(budgetWarningSteps);
        agent.setForceFinalAnswer(forceFinalAnswer);

        return agent;
    }
}
