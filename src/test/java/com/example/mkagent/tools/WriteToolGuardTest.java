package com.example.mkagent.tools;

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

    @Autowired
    private AgentToolProvider toolProvider;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("扫描能发现写工具（防止扫描本身失效导致的假绿）")
    void scanFindsWriteTools() {
        Set<String> writeTools = scanWriteToolNames();

        assertFalse(writeTools.isEmpty(), "扫描结果为空说明守卫失效");
        assertTrue(writeTools.contains("create_playlist"), writeTools.toString());
        assertTrue(writeTools.contains("add_songs_to_playlist"), writeTools.toString());
    }

    @Test
    @DisplayName("写工具不在任何 AgentType 的白名单里")
    void writeToolsAreInNoWhitelist() {
        Set<String> writeTools = scanWriteToolNames();

        for (AgentType type : AgentType.values()) {
            Set<String> allowed = toolNames(type);
            if (allowed.isEmpty()) {
                continue;
            }

            for (String writeTool : writeTools) {
                assertFalse(
                        allowed.contains(writeTool),
                        "写工具 " + writeTool + " 不得进入 " + type + " 的白名单：" + allowed
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
