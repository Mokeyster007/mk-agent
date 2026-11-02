package com.example.mkagent.approval;

import com.example.mkagent.tools.RequiresApproval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * "哪些工具需要人工确认"的唯一来源。
 *
 * 名单来自 {@link RequiresApproval} 注解本身，不另维护一份 Set：
 * 新增写工具时最容易忘的就是"顺手加进名单"，
 * 而注解就写在工具方法上，写工具的人不可能不看见。
 *
 * 扫描时机（重要）：
 * 不在构造期扫描，而是等 {@link ContextRefreshedEvent}（容器完全启动后）扫一次。
 * 原因：构造期调用 {@code getType(beanName)} 时，部分 {@code @Bean} 工厂方法
 * 还没实例化声明类，取类型可能抛异常；一旦某个写工具因此被跳过，
 * 白名单就少了一个必须确认的工具 —— 这是"失败放行"，比启动慢更糟。
 * 容器刷新完成后所有单例都已就绪，扫描结果是完整的。
 *
 * 失败关闭（重要）：
 * 扫描不完整时**不允许**退化成"按普通工具执行"。两条兜底：
 * 1. {@link #verifyExposedTools(String, Collection)}：Agent 实际可执行的工具
 *    必须都在扫描结果里，否则直接抛异常；
 * 2. 未完成扫描就查询名单（{@link #requiredToolNames()}）也直接抛异常，
 *    而不是返回空集。
 * 无关 Bean（非工具 Bean）取类型失败只记 WARN，不影响启动——
 * 这类 Bean 不可能被执行，跳过它不会让任何工具失去审批元数据。
 */
@Component
public class ApprovalToolPolicy implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalToolPolicy.class);

    /**
     * 一次扫描的结果。
     *
     * @param approvalToolNames 带 {@link RequiresApproval} 的工具名
     * @param allToolNames      扫到的全部 {@code @Tool} 方法名（用于判断"元数据是否确定"）
     * @param unresolvedBeans   取类型失败的 Bean 名（只用于告警与排查）
     */
    record ScanResult(
            Set<String> approvalToolNames,
            Set<String> allToolNames,
            Set<String> unresolvedBeans
    ) {
    }

    /**
     * 扫描结果。null 表示"还没扫"（不是"扫了但没有"）。
     */
    private volatile ScanResult scanResult;

    private final boolean enabled;

    private final ApplicationContext context;

    @Autowired
    public ApprovalToolPolicy(ApplicationContext context, ApprovalProperties properties) {
        this.context = context;
        this.enabled = properties.isEnabled();
    }

    /**
     * 测试用构造：直接给定审批名单，同时视为"这些工具就是全部已知工具"。
     */
    public ApprovalToolPolicy(Set<String> approvalToolNames, boolean enabled) {
        this(approvalToolNames, approvalToolNames, enabled);
    }

    /**
     * 测试用构造：分别给定审批名单与全部已知工具名。
     *
     * 两个集合分开，是为了能构造"Agent 暴露了扫描不到的工具"这种失败关闭场景。
     */
    public ApprovalToolPolicy(
            Set<String> approvalToolNames,
            Set<String> knownToolNames,
            boolean enabled
    ) {
        this.context = null;
        this.enabled = enabled;
        this.scanResult = new ScanResult(
                copyOf(approvalToolNames),
                copyOf(knownToolNames),
                Set.of()
        );
    }

    /**
     * 容器刷新完成后扫描一次（此后工具清单在运行期不变）。
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 子容器也会触发；重复扫描是幂等的，直接覆盖。
        if (event.getApplicationContext() == context || scanResult == null) {
            scanResult = scan(event.getApplicationContext());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 是否已完成扫描。
     */
    public boolean isReady() {
        return scanResult != null;
    }

    /**
     * 单个工具是否需要人工确认。
     *
     * 关闭审批时返回 false —— 但调用方**不能**因此直接执行写工具：
     * ToolCallAgent 会在"命中写工具但审批关闭"时失败关闭。
     */
    public boolean requiresApproval(String toolName) {
        return enabled && toolName != null
                && result().approvalToolNames().contains(toolName);
    }

    /**
     * 本轮是否有任一工具需要人工确认（整轮暂停的判定）。
     */
    public boolean anyRequiresApproval(List<AssistantMessage.ToolCall> toolCalls) {
        if (!enabled || toolCalls == null) {
            return false;
        }
        Set<String> names = result().approvalToolNames();
        for (AssistantMessage.ToolCall call : toolCalls) {
            if (names.contains(call.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命中写工具但审批未启用（失败关闭用）。
     */
    public boolean hitsApprovalToolWhenDisabled(List<AssistantMessage.ToolCall> toolCalls) {
        if (enabled || toolCalls == null) {
            return false;
        }
        Set<String> names = result().approvalToolNames();
        for (AssistantMessage.ToolCall call : toolCalls) {
            if (names.contains(call.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 需要人工确认的工具名（参与配置指纹）。
     */
    public Set<String> requiredToolNames() {
        return result().approvalToolNames();
    }

    /**
     * 全部已知（扫到过 {@code @Tool} 方法）的工具名，供诊断与测试使用。
     */
    public Set<String> knownToolNames() {
        return result().allToolNames();
    }

    /**
     * 校验 Agent **实际可执行**的工具都能确定审批元数据。
     *
     * 为什么不只 WARN：扫描漏掉一个写工具，它的 {@code requiresApproval} 就是 false，
     * 于是"不需要确认"地被执行——这是把审批承诺悄悄作废，比启动失败严重得多。
     * 因此这里失败关闭：抛异常 → 任务失败，绝不放行。
     *
     * 只校验**被暴露的工具**，与"无关 Bean 取类型失败"无关：
     * 后者根本不会被执行，不需要审批元数据，因此不必让启动失败。
     *
     * @param agentLabel  出错时用于定位（Agent 名称/类型）
     * @param exposedNames 该 Agent 的工具名集合
     */
    public void verifyExposedTools(String agentLabel, Collection<String> exposedNames) {
        ScanResult result = result();

        if (exposedNames == null || exposedNames.isEmpty()) {
            return;
        }

        Set<String> unknown = new TreeSet<>();
        for (String name : exposedNames) {
            if (name != null && !result.allToolNames().contains(name)) {
                unknown.add(name);
            }
        }

        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "无法确定以下工具的审批元数据，拒绝执行：" + unknown
                            + "（agent=" + agentLabel
                            + "，可能来自未被扫描的 Bean、代理类或工厂方法）");
        }
    }

    /**
     * 取扫描结果；未完成扫描直接失败关闭，不退化成空名单。
     */
    private ScanResult result() {
        ScanResult local = scanResult;
        if (local == null) {
            throw new IllegalStateException(
                    "审批元数据尚未就绪（工具扫描未完成），拒绝按普通工具执行");
        }
        return local;
    }

    private static Set<String> copyOf(Set<String> source) {
        return source == null ? Set.of() : Set.copyOf(source);
    }

    /**
     * 扫描所有 Bean 的 {@code @Tool} 方法。
     *
     * 类级 {@link RequiresApproval} 同样生效：整个类都是写工具时，
     * 逐个方法标注更容易漏。
     *
     * 代理 Bean 用 {@link AopUtils#getTargetClass(Class)} 还原目标类：
     * 代理类的方法上没有注解，直接扫代理类会"看不见"审批标记，
     * 这正是最容易造成漏判的一种 Bean 形态。
     */
    private static ScanResult scan(ApplicationContext context) {
        Set<String> approvalNames = new LinkedHashSet<>();
        Set<String> allNames = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();

        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = context.getType(beanName);
            } catch (Exception e) {
                // 取类型失败不阻断启动：这类 Bean 若真是工具，
                // 会在 verifyExposedTools 处失败关闭，不会静默执行。
                unresolved.add(beanName);
                continue;
            }
            if (type == null) {
                unresolved.add(beanName);
                continue;
            }

            /*
             * CGLIB 代理类的方法上没有注解，必须还原到用户类才看得见。
             *
             * 注意用 ClassUtils.getUserClass 而不是 AopUtils.getTargetClass：
             * 后者只有 Object 重载，传 Class 会走"取对象类型"分支，
             * 对 Class 实例返回 java.lang.Class —— 于是所有 Bean 都扫不到注解，
             * 白名单静默变空。这正是本方法要防的失败放行。
             */
            Class<?> target = ClassUtils.getUserClass(type);
            boolean classAnnotated =
                    AnnotationUtils.findAnnotation(target, RequiresApproval.class) != null;

            Method[] methods;
            try {
                methods = target.getMethods();
            } catch (Exception e) {
                unresolved.add(beanName);
                continue;
            }

            for (Method method : methods) {
                Tool tool = AnnotationUtils.findAnnotation(method, Tool.class);
                if (tool == null) {
                    continue;
                }
                allNames.add(tool.name());

                boolean methodAnnotated =
                        AnnotationUtils.findAnnotation(method, RequiresApproval.class) != null;
                if (methodAnnotated || classAnnotated) {
                    approvalNames.add(tool.name());
                }
            }
        }

        if (!unresolved.isEmpty()) {
            log.warn("审批名单扫描时无法解析类型的 Bean（若其中含工具，运行时会失败关闭）：{}",
                    new TreeSet<>(unresolved));
        }
        log.info("审批工具扫描完成：Bean 数={}，全部工具={}，需人工确认={}",
                context.getBeanDefinitionNames().length,
                new TreeSet<>(allNames), new TreeSet<>(approvalNames));

        return new ScanResult(
                Set.copyOf(approvalNames),
                Set.copyOf(allNames),
                Set.copyOf(unresolved)
        );
    }
}
