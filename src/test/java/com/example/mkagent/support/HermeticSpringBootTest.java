package com.example.mkagent.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 封闭式 Spring 上下文测试的组合注解。
 *
 * 默认测试必须能在"Docker 数据库停止、8127 MCP 未启动、断网"的环境下通过，
 * 并且绝不能读取 application-local.yml（里面是真实密钥）。
 *
 * 做法：
 * 1. 强制激活 test profile，覆盖 application.yml 里的 spring.profiles.active=local；
 * 2. 公共隔离配置集中在 src/test/resources/application-test.yml
 *    （H2 内存库、关闭 pgvector、关闭 MCP、占位密钥）；
 * 3. 各测试只保留自己特有的属性，避免重复配置漂移。
 *
 * 新增上下文测试时请优先使用本注解；
 * HermeticTestIsolationUnitTest 会扫描并阻止漏用。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@ActiveProfiles("test")
public @interface HermeticSpringBootTest {

    /**
     * 透传 {@link SpringBootTest#webEnvironment()}，
     * 需要真实端口的 SSE / HTTP 测试用 RANDOM_PORT。
     */
    @AliasFor(annotation = SpringBootTest.class, attribute = "webEnvironment")
    SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.MOCK;

    /**
     * 透传 {@link SpringBootTest#properties()}，
     * 只放本测试特有的属性（如 max-concurrency=2）。
     */
    @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
    String[] properties() default {};
}
