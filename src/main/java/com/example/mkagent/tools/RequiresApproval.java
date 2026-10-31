package com.example.mkagent.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记"会写入业务数据、需要人工确认"的工具方法。
 *
 * 用途不是拦截（本批还没有审批链路），而是**可验证的清单**：
 * <ol>
 *   <li>{@code WriteToolGuardTest} 反射扫描所有带本注解的 {@code @Tool} 方法，
 *       断言它们的工具名不在任何 AgentType 的白名单里，
 *       也不在 {@code mkagent.tool-retry.retryable-tools} 里；</li>
 *   <li>M3 的审批链路按本注解决定"哪些调用要先挂起、等用户点确认"，
 *       不需要再维护第二份名单。</li>
 * </ol>
 *
 * 为什么用注解而不是在代码里维护一个 Set&lt;String&gt;：
 * 名单会和工具实现分家——新增一个写工具时最容易忘的就是"顺手加进名单"，
 * 而注解就在方法上，写工具的人不可能不看见。
 *
 * 为什么同时允许标注在类上：将来若出现整个类都是写工具的情况，
 * 类级标注比逐个方法标注更难漏。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresApproval {

    /**
     * 人类可读的确认理由（展示给用户，不要放参数或内部标识）。
     */
    String reason() default "该操作会写入业务数据，需要人工确认";
}
