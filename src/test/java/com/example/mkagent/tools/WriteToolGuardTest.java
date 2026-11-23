package com.example.mkagent.tools;

import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.config.AgentToolProvider;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写工具守卫：反射扫描 + 白名单交叉验证。
 *
 * 这个测试的价值不在于"验证今天的代码"，而在于**明天新增写工具时**：
 * 只要有人给一个 {@code @Tool} 方法加上 {@link RequiresApproval}
 * 却忘了"它本来就不该进白名单"，这里会立刻失败。
 *
 * 反过来也成立：如果某个写工具被悄悄加进了白名单，
 * 即使注解还在，这里也会失败。
 *
 * 为什么扫描而不是维护一份名单：名单会和实现分家。
 * 扫描是"从代码事实出发"，名单是"从人的记忆出发"。
 */
@HermeticSpringBootTest
class WriteToolGuardTest {

    /**
     * MCP 自动配置在测试 profile 下被排除，
     * chatApp 需要的 ToolCallbackProvider 由此空桩提供。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    /**
     * 工具类所在包。
     */
    private static final String TOOLS_PACKAGE = "com.example.mkagent.tools";

    /**
     * 允许写工具进入白名单的登记表（唯一合法来源）。
     *
     * <p>当前仅允许电商业务已接入审批的写工具：
     * <ul>
     *   <li>{@code AFTER_SALES} → {@code submit_return}</li>
     *   <li>{@code SHOP_ASSISTANT} → {@code submit_checkout_order}</li>
     * </ul>
     * 音乐写工具已彻底退场，不得出现在任何白名单或审批策略中。
     */
    private static final java.util.Map<AgentType, Set<String>> APPROVAL_WHITELISTED =
            java.util.Map.of(
                    AgentType.AFTER_SALES, Set.of("submit_return"),
                    AgentType.SHOP_ASSISTANT, Set.of("submit_checkout_order")
            );

    @Autowired
    private AgentToolProvider toolProvider;

    @Autowired
    private Environment environment;

    @Autowired
    private ApprovalToolPolicy approvalToolPolicy;

    @Test
    @DisplayName("扫描能发现电商写工具且不再存在音乐写工具")
    void scanFindsWriteTools() {
        Set<String> writeTools = scanWriteToolNames();

        assertFalse(writeTools.isEmpty(), "扫描结果为空说明守卫失效");
        assertTrue(writeTools.contains("submit_return"), writeTools.toString());
        assertTrue(writeTools.contains("submit_checkout_order"), writeTools.toString());
        assertFalse(writeTools.contains("create_playlist"), "音乐写工具应已移除");
        assertFalse(writeTools.contains("add_songs_to_playlist"), "音乐写工具应已移除");
    }

    @Test
    @DisplayName("审批策略扫到了全部电商写工具（不能是空集合）")
    void approvalPolicySeesWriteTools() {
        assertTrue(approvalToolPolicy.isReady(), "容器刷新后必须已完成扫描");

        Set<String> approvalTools = approvalToolPolicy.requiredToolNames();

        assertFalse(approvalTools.isEmpty(), "审批工具集合为空说明策略扫描失效");
        assertTrue(approvalTools.contains("submit_return"),
                "策略必须识别 submit_return，实际：" + approvalTools);
        assertTrue(approvalTools.contains("submit_checkout_order"),
                "策略必须识别 submit_checkout_order，实际：" + approvalTools);
        assertFalse(approvalTools.contains("create_playlist"),
                "音乐写工具不应再注册到审批策略：" + approvalTools);
        assertFalse(approvalTools.contains("add_songs_to_playlist"),
                "音乐写工具不应再注册到审批策略：" + approvalTools);
    }

    /**
     * 写工具进入白名单的**唯一**合法方式：显式登记 + 运行时真的会暂停。
     *
     * <p>这条规则在 A1 之前是"写工具不得进入任何白名单"——
     * 那时审批链路刚装配、还没有真实业务用它。
     * 现在售后 Agent 的 {@code submit_return} 必须对模型可见
     * （否则"确认后建单"根本无法发生），因此规则收紧为：
     * <ol>
     *   <li>只有登记在 {@link #APPROVAL_WHITELISTED} 里的类型/工具可以出现在白名单；</li>
     *   <li>出现了就必须被 {@code ApprovalToolPolicy} 认成"需要人工确认"——
     *       否则等于把审批承诺悄悄作废；</li>
     *   <li>其余类型（含音乐）仍然一个写工具都不能有。</li>
     * </ol>
     *
     * <p>这样"新增一个对模型可见的写工具"必须同时改这份登记表，
     * 是一次看得见的决定，而不是某个配置顺手打开的结果。
     */
    @Test
    @DisplayName("写工具只能出现在显式登记的白名单里，且必须真的会暂停")
    void writeToolsOnlyInDeclaredWhitelistAndAlwaysGated() {
        Set<String> writeTools = scanWriteToolNames();

        for (AgentType type : AgentType.values()) {
            Set<String> allowed = toolNames(type);
            if (allowed.isEmpty()) {
                continue;
            }

            Set<String> permitted = APPROVAL_WHITELISTED.getOrDefault(type, Set.of());

            for (String writeTool : writeTools) {
                if (!allowed.contains(writeTool)) {
                    continue;
                }

                assertTrue(
                        permitted.contains(writeTool),
                        "写工具 " + writeTool + " 未登记就进入了 " + type + " 的白名单："
                                + allowed
                );
                assertTrue(
                        approvalToolPolicy.requiresApproval(writeTool),
                        "白名单里的写工具 " + writeTool + " 必须被审批策略认成需要确认，"
                                + "否则会被当普通工具直接执行"
                );
            }
        }
    }

    /**
     * 音乐写工具仍然对模型不可见（A1 没有改变这条）。
     *
     * <p>与上面那条分开写，是因为它们的理由不同：
     * 音乐写工具"能力不存在"，售后写工具"存在但必须先确认"。
     * 混在一个断言里，将来放宽一处会顺手放宽另一处。
     */
    @Test
    @DisplayName("音乐写工具不在任何 AgentType 的白名单里")
    void musicWriteToolsAreInNoWhitelist() {
        Set<String> musicWriteTools = Set.of("create_playlist", "add_songs_to_playlist");

        for (AgentType type : AgentType.values()) {
            Set<String> allowed = toolNames(type);
            if (allowed.isEmpty()) {
                continue;
            }

            for (String writeTool : musicWriteTools) {
                assertFalse(
                        allowed.contains(writeTool),
                        "音乐写工具 " + writeTool + " 不得进入 " + type + " 的白名单：" + allowed
                );
            }
        }
    }

    @Test
    @DisplayName("写工具不在自动重试名单里（重试写操作等于重复执行）")
    void writeToolsAreNotRetryable() {
        String configured = environment.getProperty(
                "mkagent.tool-retry.retryable-tools", "");
        Set<String> retryable = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        for (String writeTool : scanWriteToolNames()) {
            assertFalse(
                    retryable.contains(writeTool),
                    "写工具 " + writeTool + " 不得出现在 retryable-tools：" + retryable
            );
        }
    }

    /**
     * 反射扫描：包内所有 {@code @Tool} 且带 {@link RequiresApproval} 的方法。
     */
    private Set<String> scanWriteToolNames() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        Set<String> names = new LinkedHashSet<>();

        for (BeanDefinition definition : scanner.findCandidateComponents(TOOLS_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }

            Class<?> type;
            try {
                type = Class.forName(className, false,
                        Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                continue;
            }

            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(RequiresApproval.class)) {
                    continue;
                }

                /*
                 * 带审批注解却不是 @Tool 方法，说明注解被误用：
                 * 守卫拿不到工具名，等于这个写工具绕过了所有检查。
                 */
                assertTrue(
                        method.isAnnotationPresent(Tool.class),
                        "@RequiresApproval 只能标在 @Tool 方法上：" + type.getSimpleName()
                                + "#" + method.getName()
                );
                assertTrue(
                        Modifier.isPublic(method.getModifiers()),
                        "写工具方法必须是 public：" + method.getName()
                );

                names.add(method.getAnnotation(Tool.class).name());
            }
        }

        return names;
    }

    private Set<String> toolNames(AgentType type) {
        try {
            ToolCallback[] callbacks = toolProvider.getTools(type);
            return Arrays.stream(callbacks)
                    .map(callback -> callback.getToolDefinition().name())
                    .collect(Collectors.toSet());
        } catch (IllegalArgumentException e) {
            // 该类型还没配工具，视为空集。
            return Set.of();
        }
    }
}
