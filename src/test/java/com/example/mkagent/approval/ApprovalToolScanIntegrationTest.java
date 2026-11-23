package com.example.mkagent.approval;

import com.example.mkagent.config.AgentToolProvider;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.RequiresApproval;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批工具扫描的真实链路（批次 1 补充）。
 *
 * 与 {@code ApprovalPauseBehaviourTest} 的区别：这里用的是**真实**
 * {@link ApprovalToolPolicy}（容器扫描），不是手工构造的替身。
 * 目的是回答"注解到底能不能被识别"，而不是"策略对象被喂了名单后行为如何"。
 *
 * 覆盖四种 Bean 形态：
 * 1. {@code @Component} 的写工具（{@code PlaylistWriteTool}）；
 * 2. {@code @Bean} 工厂方法产出的工具（不是 @Component，最容易漏）；
 * 3. 实例是代理的工具（AOP 代理类的方法上没有注解）；
 * 4. Agent 实际白名单里的全部工具（用真实 {@link AgentToolProvider} 取）。
 *
 * 以及失败关闭：暴露了扫不到的工具时，必须抛异常而不是当成普通工具。
 */
@HermeticSpringBootTest
class ApprovalToolScanIntegrationTest {

    @Autowired
    private ApprovalToolPolicy policy;

    @Autowired
    private AgentToolProvider agentToolProvider;

    @Autowired
    private FactoryMadeTool factoryMadeTool;

    @Autowired
    private ProxiedTool proxiedTool;

    @Test
    @DisplayName("真实扫描能识别电商写工具的审批注解")
    void realScanFindsWriteTools() {
        assertTrue(policy.isReady(), "容器刷新后必须已完成扫描");

        Set<String> approvalTools = policy.requiredToolNames();
        assertTrue(approvalTools.contains("submit_return"),
                "submit_return 必须被识别为需审批，实际：" + approvalTools);
        assertTrue(approvalTools.contains("submit_checkout_order"),
                "submit_checkout_order 必须被识别为需审批，实际：" + approvalTools);

        /*
         * 不只断言"非空"：空集合会让 contains 之外的检查全部假通过，
         * 这里同时钉住"扫到的工具总数"必须大于审批工具数。
         */
        assertTrue(policy.knownToolNames().size() > approvalTools.size(),
                "已知工具应包含只读工具，实际：" + policy.knownToolNames());
    }

    @Test
    @DisplayName("@Bean 工厂方法与代理实例产出的工具也能被识别")
    void factoryAndProxyToolsAreDiscovered() {
        Set<String> approvalTools = policy.requiredToolNames();

        assertTrue(approvalTools.contains(FactoryMadeTool.TOOL_NAME),
                "@Bean 工厂方法产出的写工具必须被扫到，实际：" + approvalTools);
        assertTrue(approvalTools.contains(ProxiedTool.TOOL_NAME),
                "代理实例的写工具必须被扫到，实际：" + approvalTools);

        // 代理类本身没有注解，必须还原到目标类才看得见
        assertEquals(ProxiedTool.class, AopUtils.getTargetClass(proxiedTool),
                "代理实例必须能还原目标类");
        assertFalse(AopUtils.isAopProxy(factoryMadeTool), "对照：工厂方法产出的是普通对象");
    }

    @Test
    @DisplayName("Agent 实际白名单里的电商工具全部可确定审批元数据")
    void everyExposedShopToolIsKnown() {
        for (AgentType type : new AgentType[]{
                AgentType.SHOP_ASSISTANT, AgentType.AFTER_SALES}) {

            ToolCallback[] callbacks = agentToolProvider.getTools(type);
            Set<String> exposed = new LinkedHashSet<>();
            Arrays.stream(callbacks).forEach(c ->
                    exposed.add(c.getToolDefinition().name()));

            assertFalse(exposed.isEmpty(), type + " 白名单不能为空");
            assertDoesNotThrow(
                    () -> policy.verifyExposedTools(type.name(), exposed),
                    type + " 的工具必须全部可确定审批元数据：" + exposed);
        }
    }

    @Test
    @DisplayName("暴露了扫不到的工具：失败关闭，不当成普通工具执行")
    void unknownExposedToolFailsClosed() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> policy.verifyExposedTools("test-agent", Set.of("ghost_write_tool")));

        assertTrue(error.getMessage().contains("ghost_write_tool"),
                "异常必须点名是哪个工具，实际：" + error.getMessage());
        assertTrue(error.getMessage().contains("审批元数据"),
                "异常必须说明是审批元数据无法确定，实际：" + error.getMessage());
    }

    // ==================== 测试专用工具 ====================

    /**
     * 不是 {@code @Component}，只由 {@code @Bean} 工厂方法产出。
     *
     * 这正是"扫描只看 @Component 就会漏"的形态。
     */
    static class FactoryMadeTool {

        static final String TOOL_NAME = "factory_write_tool";

        @Tool(name = TOOL_NAME, description = "测试用写工具（工厂方法产出）")
        @RequiresApproval(reason = "测试用")
        public String write(String name) {
            return "ok:" + name;
        }
    }

    /**
     * 会被包成 CGLIB 代理的写工具：代理类的方法上没有注解。
     */
    static class ProxiedTool {

        static final String TOOL_NAME = "proxied_write_tool";

        @Tool(name = TOOL_NAME, description = "测试用写工具（代理实例）")
        @RequiresApproval(reason = "测试用")
        public String write(String name) {
            return "ok:" + name;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExtraTools {

        @Bean
        FactoryMadeTool factoryMadeTool() {
            return new FactoryMadeTool();
        }

        @Bean
        ProxiedTool proxiedTool() {
            ProxyFactory factory = new ProxyFactory(new ProxiedTool());
            factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
            return (ProxiedTool) factory.getProxy();
        }

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
