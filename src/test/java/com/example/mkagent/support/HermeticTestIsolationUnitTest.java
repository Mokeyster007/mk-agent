package com.example.mkagent.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 封闭测试的守护测试：扫描类路径上所有会启动 Spring 上下文的测试类，
 * 要求非 live 测试都使用 {@link HermeticSpringBootTest}（激活 test profile）。
 *
 * 为什么需要它：
 * application.yml 默认 spring.profiles.active=local，
 * 一旦某个上下文测试忘记切 profile，就会读取真实的 application-local.yml
 * （含真实密钥、Docker PostgreSQL 地址、8127 MCP 连接），
 * 在"没启动 Docker / 没启动 MCP / 断网"的环境下直接失败，甚至偷偷触网。
 *
 * 本测试本身不启动 Spring、不触网。
 */
class HermeticTestIsolationUnitTest {

    /**
     * 会启动 Spring 上下文的注解。
     *
     * 目前项目只用 @SpringBootTest，这里把常见切片一并纳入，
     * 以后有人引入 @WebMvcTest / @DataJdbcTest 时同样受约束。
     */
    @SuppressWarnings("rawtypes")
    private static final List<Class<? extends java.lang.annotation.Annotation>>
            CONTEXT_ANNOTATIONS = List.of(
            SpringBootTest.class,
            WebMvcTest.class,
            DataJpaTest.class,
            JdbcTest.class,
            JsonTest.class,
            WebFluxTest.class,
            RestClientTest.class
    );

    @Test
    void everyContextTestIsHermeticUnlessLive() throws Exception {
        List<Class<?>> contextTests = scanContextTests();

        assertThat(contextTests)
                .as("扫描不到任何上下文测试，说明扫描逻辑失效")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();

        for (Class<?> testClass : contextTests) {
            if (isLive(testClass)) {
                continue;
            }

            MergedAnnotations annotations = MergedAnnotations.from(
                    testClass,
                    MergedAnnotations.SearchStrategy.TYPE_HIERARCHY
            );

            if (!annotations.isPresent(HermeticSpringBootTest.class)) {
                violations.add(testClass.getName()
                        + " 未使用 @HermeticSpringBootTest");
                continue;
            }

            List<String> profiles = activeProfiles(annotations);
            if (!profiles.contains("test")) {
                violations.add(testClass.getName()
                        + " 未激活 test profile，实际：" + profiles);
            }
            if (profiles.contains("local")) {
                violations.add(testClass.getName()
                        + " 显式激活了 local profile，会读取 application-local.yml");
            }
        }

        assertThat(violations)
                .as("上下文测试必须使用 @HermeticSpringBootTest；"
                        + "live 测试请标注 @Tag(\"live\")")
                .isEmpty();
    }

    /**
     * 扫描会启动 Spring 上下文的测试类。
     */
    private List<Class<?>> scanContextTests() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        for (Class<? extends java.lang.annotation.Annotation> annotation
                : CONTEXT_ANNOTATIONS) {
            scanner.addIncludeFilter(new AnnotationTypeFilter(annotation, true, true));
        }

        Set<BeanDefinition> candidates =
                scanner.findCandidateComponents("com.example.mkagent");

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : candidates) {
            classes.add(Class.forName(candidate.getBeanClassName()));
        }
        return classes;
    }

    private boolean isLive(Class<?> testClass) {
        return java.util.Arrays.stream(testClass.getAnnotationsByType(Tag.class))
                .anyMatch(tag -> "live".equals(tag.value()));
    }

    private List<String> activeProfiles(MergedAnnotations annotations) {
        MergedAnnotation<ActiveProfiles> merged =
                annotations.get(ActiveProfiles.class);
        if (!merged.isPresent()) {
            return List.of();
        }
        return List.of(merged.getStringArray("value"));
    }
}
