package com.example.mkagent.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构造审批摘要（给用户看）。
 *
 * <p>各业务域通过 {@link ApprovalSummaryContributor} 提供结构化摘要（售后退货、导购结算下单等）；
 * 没有任何业务扩展认领时，退化为通用脱敏摘要（只展示工具名与有效期，绝不回显参数原文）。
 */
@Component
public class ApprovalSummaryBuilder {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSummaryBuilder.class);

    private final ApprovalProperties properties;

    private final List<ApprovalSummaryContributor> contributors;

    @Autowired
    public ApprovalSummaryBuilder(
            ApprovalProperties properties,
            List<ApprovalSummaryContributor> contributors
    ) {
        this.properties = properties;
        this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    public ApprovalSummaryBuilder(ApprovalProperties properties) {
        this(properties, List.of());
    }

    public ApprovalSummary build(List<AssistantMessage.ToolCall> toolCalls) {
        return build(toolCalls, null);
    }

    public ApprovalSummary build(
            List<AssistantMessage.ToolCall> toolCalls,
            String userId
    ) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("审批摘要缺少工具调用");
        }

        AssistantMessage.ToolCall primary = pickPrimary(toolCalls);
        String toolName = primary.name();

        ApprovalSummaryContributor.Contribution contributed = contribute(primary, userId);
        if (contributed != null) {
            String text = renderGeneric(
                    contributed.label(), contributed.details(), toolCalls.size());
            return new ApprovalSummary(
                    toolName,
                    contributed.label(),
                    null,
                    null,
                    List.of(),
                    toolCalls.size(),
                    text,
                    contributed.details());
        }

        String label = "执行操作 " + toolName;
        String text = renderGeneric(label, List.of(), toolCalls.size());
        return new ApprovalSummary(
                toolName,
                label,
                null,
                null,
                List.of(),
                toolCalls.size(),
                text,
                List.of());
    }

    private ApprovalSummaryContributor.Contribution contribute(
            AssistantMessage.ToolCall primary,
            String userId
    ) {
        for (ApprovalSummaryContributor contributor : contributors) {
            try {
                if (!contributor.supports(primary.name())) {
                    continue;
                }
                ApprovalSummaryContributor.Contribution contribution =
                        contributor.contribute(primary.arguments(), userId);
                if (contribution != null) {
                    return contribution;
                }
            } catch (Exception e) {
                log.warn("审批摘要扩展失败，退回默认渲染：tool={}, reason={}",
                        primary.name(), e.getClass().getSimpleName());
            }
        }
        return null;
    }

    private String renderGeneric(
            String label,
            List<ApprovalSummary.Detail> details,
            int roundToolCount
    ) {
        StringBuilder text = new StringBuilder(label);

        if (details != null && !details.isEmpty()) {
            text.append("：");
            for (int i = 0; i < details.size(); i++) {
                if (i > 0) {
                    text.append("；");
                }
                text.append(details.get(i).label())
                        .append(" ")
                        .append(details.get(i).value());
            }
        }
        if (roundToolCount > 1) {
            text.append("（本轮共 ").append(roundToolCount).append(" 个操作，一并等待确认）");
        }
        text.append("。有效期 ")
                .append(properties.getTtl().toMinutes())
                .append(" 分钟。");

        return sanitize(text.toString(), ApprovalSummary.MAX_TEXT_LENGTH);
    }

    private AssistantMessage.ToolCall pickPrimary(
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        for (AssistantMessage.ToolCall call : toolCalls) {
            if (isWriteTool(call.name())) {
                return call;
            }
        }
        return toolCalls.get(0);
    }

    private boolean isWriteTool(String toolName) {
        for (ApprovalSummaryContributor contributor : contributors) {
            if (contributor.supports(toolName)) {
                return true;
            }
        }
        return false;
    }

    public static String sanitize(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }

        StringBuilder cleaned = new StringBuilder(value.length());
        boolean lastWasSpace = false;

        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);

            if (Character.isISOControl(codePoint) || codePoint == 0x2028
                    || codePoint == 0x2029) {
                if (!lastWasSpace && cleaned.length() > 0) {
                    cleaned.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }

            if (codePoint == ' ') {
                if (lastWasSpace || cleaned.length() == 0) {
                    continue;
                }
                cleaned.append(' ');
                lastWasSpace = true;
                continue;
            }

            cleaned.appendCodePoint(codePoint);
            lastWasSpace = false;
        }

        String result = cleaned.toString().trim();

        if (result.codePointCount(0, result.length()) <= maxCodePoints) {
            return result;
        }

        int end = result.offsetByCodePoints(0, maxCodePoints);
        return result.substring(0, end) + "…";
    }
}
