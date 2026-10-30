package com.example.mkagent.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * live 测试隔离的守护测试。
 *
 * 目的：live 测试（真实模型 / 真实搜索 / 真实数据库）一旦漏打 @Tag("live")，
 * 就会在 `mvn test` 里默默花钱。这里用两道断言把它钉死：
 *
 * 1. pom.xml 里 surefire 默认排除 live 分组，且 -Plive 可以把排除项覆盖掉；
 * 2. 已知的 live 测试类确实带 @Tag("live")。
 *
 * 本测试本身不启动 Spring、不触网，属于默认执行的分组。
 */
class LiveTestIsolationUnitTest {

    /**
     * 已知会调用真实模型 / 真实外部服务的测试类。
     *
     * 新增这类测试时必须同时加进本清单，否则守护失效。
     */
    private static final List<String> LIVE_TEST_CLASSES = List.of(
            "com.example.mkagent.eval.AgentEvalRunnerTest",
            "com.example.mkagent.agent.MkManusTest",
            "com.example.mkagent.app.chatAppTest",
            "com.example.mkagent.tools.WebScrapingToolTest",
            "com.example.mkagent.tools.WebSearchToolTest",
            "com.example.mkagent.demo.rag.MultiQueryExpanderDemoTest",
            "com.example.mkagent.rag.ChatAppDocumentLoaderTest",
            "com.example.mkagent.rag.PgVectorVectorStoreConfigTest"
    );

    @Test
    void surefireExcludesLiveGroupByDefault() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .as("默认排除的分组必须是 live")
                .contains("<mkagent.test.excluded-groups>live</mkagent.test.excluded-groups>");

        assertThat(pom)
                .as("surefire 必须真的使用该属性作为 excludedGroups")
                .contains(
                        "<excludedGroups>${mkagent.test.excluded-groups}</excludedGroups>"
                );

        assertThat(pom)
                .as("-Plive 必须能把排除项覆盖成哨兵值 none")
                .contains("<id>live</id>")
                .contains("<mkagent.test.excluded-groups>none</mkagent.test.excluded-groups>");
    }

    @Test
    void liveTestClassesAreTagged() throws Exception {
        for (String className : LIVE_TEST_CLASSES) {
            Class<?> clazz = Class.forName(className);

            assertThat(clazz.getAnnotationsByType(Tag.class))
                    .as("%s 必须标注 @Tag(\"live\")", className)
                    .extracting(Tag::value)
                    .contains("live");
        }
    }
}
