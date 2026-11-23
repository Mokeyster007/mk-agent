package com.example.mkagent.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批摘要扩展机制测试（去音乐化后，由各业务域 Contributor 提供摘要）。
 */
@DisplayName("审批摘要：Contributor 与通用兜底")
class ApprovalSummaryBuilderTest {

    private static final String USER = "shop-user-1";

    @Test
    @DisplayName("命中业务 Contributor 时，摘要使用其标题与结构化明细，并显式传入 userId")
    void usesMatchingContributorWithUserId() {
        AtomicReference<String> askedUser = new AtomicReference<>();
        ApprovalSummaryContributor contributor = new ApprovalSummaryContributor() {
            @Override
            public boolean supports(String toolName) {
                return "submit_checkout_order".equals(toolName);
            }

            @Override
            public Contribution contribute(String argumentsJson, String userId) {
                askedUser.set(userId);
                return new Contribution("确认下单", List.of(
                        new ApprovalSummary.Detail("商品", "静音无线机械键盘 Air75"),
                        new ApprovalSummary.Detail("数量", "1 件"),
                        new ApprovalSummary.Detail("预计总额", "¥369.00")
                ));
            }
        };

        ApprovalSummaryBuilder builder = new ApprovalSummaryBuilder(
                new ApprovalProperties(), List.of(contributor));

        ApprovalSummary summary = builder.build(
                List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "submit_checkout_order",
                        "{\"productId\":2004,\"quantity\":1}")),
                USER);

        assertThat(askedUser.get()).isEqualTo(USER);
        assertThat(summary.toolLabel()).isEqualTo("确认下单");
        assertThat(summary.details()).hasSize(3);
        assertThat(summary.text()).contains("确认下单").contains("Air75").contains("¥369.00");
    }

    @Test
    @DisplayName("Contributor 抛异常时安全降级为通用脱敏摘要，不阻断审批且不回显参数原文")
    void swallowsContributorFailure() {
        ApprovalSummaryContributor broken = new ApprovalSummaryContributor() {
            @Override
            public boolean supports(String toolName) {
                return true;
            }

            @Override
            public Contribution contribute(String argumentsJson, String userId) {
                throw new IllegalStateException("db down");
            }
        };

        ApprovalSummaryBuilder builder = new ApprovalSummaryBuilder(
                new ApprovalProperties(), List.of(broken));

        ApprovalSummary summary = builder.build(
                List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "submit_return",
                        "{\"orderNo\":\"SO-SECRET-999\"}")),
                USER);

        assertThat(summary.toolLabel()).isEqualTo("执行操作 submit_return");
        assertThat(summary.text()).doesNotContain("SO-SECRET-999");
    }

    @Test
    @DisplayName("无匹配 Contributor 时退回通用摘要，且单参重载行为稳定")
    void fallsBackToGenericSummary() {
        ApprovalSummaryBuilder builder = new ApprovalSummaryBuilder(new ApprovalProperties());

        ApprovalSummary summary = builder.build(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "custom_write", "{\"secret\":\"abc\"}")));

        assertThat(summary.toolLabel()).isEqualTo("执行操作 custom_write");
        assertThat(summary.details()).isEmpty();
        assertThat(summary.text()).doesNotContain("abc");
    }
}
