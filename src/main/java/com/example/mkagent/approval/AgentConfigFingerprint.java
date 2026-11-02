package com.example.mkagent.approval;

import com.example.mkagent.model.AgentType;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Agent 配置兼容性指纹（R3）。
 *
 * 参与指纹的内容：
 * 1. 显式工具契约版本（{@link ToolContractVersion}）；
 * 2. Agent 类型；
 * 3. 系统提示词**原文**（只作为哈希材料，不落库）；
 * 4. 每个工具的名字、描述、参数 Schema；
 * 5. 需要人工确认的工具名集合（`@RequiresApproval` 标记）。
 *
 * **不**参与指纹：`maxSteps` / `maxToolCalls` / `timeout`。
 * 预算是"任务级快照"，恢复时用检查点里的旧值；
 * 把它们算进指纹会让"运维调大步数"直接作废所有待审批任务，
 * 而这跟"旧检查点在新代码下是否安全"没有关系。
 *
 * 指纹变化时的语义：**拒绝恢复**（`CHECKPOINT_CONFIG_MISMATCH`），
 * 不用新提示词顶替旧检查点。理由：提示词决定模型行为，
 * 静默换版本等于让用户批准的操作在一个他没看过的规则下执行。
 */
public final class AgentConfigFingerprint {

    private AgentConfigFingerprint() {
    }

    /**
     * 计算配置指纹。
     *
     * @param agentType            Agent 类型
     * @param systemPrompt         系统提示词（可为空）
     * @param tools                当前白名单
     * @param approvalRequiredTools 需要人工确认的工具名
     * @return 64 位十六进制 sha256
     */
    public static String compute(
            AgentType agentType,
            String systemPrompt,
            ToolCallback[] tools,
            Set<String> approvalRequiredTools
    ) {
        StringBuilder material = new StringBuilder();
        material.append("contract=").append(ToolContractVersion.CURRENT).append('\n');
        material.append("agent=").append(agentType == null ? "UNKNOWN" : agentType.name())
                .append('\n');
        material.append("prompt=").append(systemPrompt == null ? "" : systemPrompt)
                .append('\n');

        for (ToolCallback tool : sortedByName(tools)) {
            material.append("tool=")
                    .append(tool.getToolDefinition().name()).append('|')
                    .append(nullToEmpty(tool.getToolDefinition().description())).append('|')
                    .append(nullToEmpty(tool.getToolDefinition().inputSchema()))
                    .append('\n');
        }

        material.append("approval=")
                .append(String.join(",", new TreeSet<>(
                        approvalRequiredTools == null ? Set.of() : approvalRequiredTools)))
                .append('\n');

        return ApprovalHashes.sha256Hex(material.toString());
    }

    /**
     * 白名单哈希：只对工具名排序后取哈希。
     */
    public static String whitelistHash(ToolCallback[] tools) {
        List<String> names = new ArrayList<>();
        for (ToolCallback tool : sortedByName(tools)) {
            names.add(tool.getToolDefinition().name());
        }
        return ApprovalHashes.whitelistHash(names);
    }

    private static List<ToolCallback> sortedByName(ToolCallback[] tools) {
        List<ToolCallback> list = tools == null
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(tools));
        list.sort((a, b) -> a.getToolDefinition().name()
                .compareTo(b.getToolDefinition().name()));
        return list;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 工具名集合（便于日志与测试断言）。
     */
    public static Set<String> toolNames(ToolCallback[] tools) {
        Set<String> names = new TreeSet<>();
        for (ToolCallback tool : sortedByName(tools)) {
            names.add(tool.getToolDefinition().name());
        }
        return names;
    }

    /**
     * 集合转有序列表（白名单哈希用）。
     */
    public static List<String> sorted(Collection<String> values) {
        List<String> list = new ArrayList<>(values);
        list.sort(String::compareTo);
        return list;
    }
}
