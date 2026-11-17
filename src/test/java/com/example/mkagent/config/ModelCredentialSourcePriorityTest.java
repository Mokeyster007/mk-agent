package com.example.mkagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.ApiKey;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型凭据的**来源优先级**与**空值语义**（全部使用假值，不发起任何请求）。
 *
 * <p>必须区分两件常被混为一谈的事：
 *
 * <ol>
 *   <li><b>配置来源覆盖</b>：同一个属性名在多个 PropertySource 里都有值时，
 *       谁赢（环境变量 vs 外部 config 文件 vs classpath 模板）。
 *       看 {@link IsolatedAppLauncher#sourceOf} 给出的<b>来源标签</b>。</li>
 *   <li><b>占位符后备解析</b>：属性值本身是 {@code ${A:${B:}}} 时，
 *       最终解析成什么。这时"来源"仍然是定义占位符的那个文件，
 *       真正取值来自环境变量——不是"环境变量覆盖了配置"。</li>
 * </ol>
 *
 * <p>本类同时钉死一个容易踩的语义：{@code ${A:${B:}}} 里，
 * <b>A 未定义</b>才会用 B；<b>A 定义为空串</b>不会回退，结果就是空串。
 */
class ModelCredentialSourcePriorityTest {

    private static final String MODEL_KEY_PROPERTY = "spring.ai.dashscope.api-key";

    /** 假值：形状像真实凭据，但明显不是，也不参与任何断言输出以外的用途。 */
    private static final String FAKE_PROJECT_ENV = "fake-project-env-key";
    private static final String FAKE_SDK_ENV = "fake-sdk-env-key";
    private static final String FAKE_EXTERNAL = "fake-external-file-key";
    private static final String FAKE_CHAT_LEVEL = "fake-chat-level-key";
    private static final String FAKE_CONNECTION_LEVEL = "fake-connection-level-key";

    @TempDir
    Path tempDir;

    // ==================== 1. 只有项目自定义变量 ====================

    @Test
    @DisplayName("只有 MKAGENT_MODEL_API_KEY：走模板占位符解析，不是来源覆盖")
    void onlyProjectEnvVariable() {
        try (ConfigurableApplicationContext context = IsolatedAppLauncher.start(
                Map.of("MKAGENT_MODEL_API_KEY", FAKE_PROJECT_ENV))) {

            ConfigurableEnvironment env = context.getEnvironment();
            assertThat(env.getProperty(MODEL_KEY_PROPERTY)).isEqualTo(FAKE_PROJECT_ENV);

            // 来源标签仍是 classpath 模板：说明这是"占位符后备解析"，
            // 不是环境变量作为属性源覆盖了属性。
            assertThat(IsolatedAppLauncher.sourceOf(env, MODEL_KEY_PROPERTY))
                    .contains("application-local.yml")
                    .contains("class path resource");

            assertThat(modelKey(context)).isEqualTo(FAKE_PROJECT_ENV);
        }
    }

    // ==================== 2. 只有 SDK 原生变量 ====================

    @Test
    @DisplayName("只有 AI_DASHSCOPE_API_KEY：嵌套占位符后备生效")
    void onlySdkEnvVariable() {
        try (ConfigurableApplicationContext context = IsolatedAppLauncher.start(
                Map.of("AI_DASHSCOPE_API_KEY", FAKE_SDK_ENV))) {

            ConfigurableEnvironment env = context.getEnvironment();
            assertThat(env.getProperty(MODEL_KEY_PROPERTY)).isEqualTo(FAKE_SDK_ENV);
            assertThat(IsolatedAppLauncher.sourceOf(env, MODEL_KEY_PROPERTY))
                    .contains("class path resource");
            assertThat(modelKey(context)).isEqualTo(FAKE_SDK_ENV);
        }
    }

    // ==================== 3. 主变量"缺失"与"为空"的区别 ====================

    @Test
    @DisplayName("主变量缺失 + SDK 变量有值：回退生效，启动成功")
    void missingPrimaryVariableFallsBack() {
        try (ConfigurableApplicationContext context = IsolatedAppLauncher.start(
                Map.of("AI_DASHSCOPE_API_KEY", FAKE_SDK_ENV))) {
            assertThat(context.getEnvironment().getProperty(MODEL_KEY_PROPERTY))
                    .isEqualTo(FAKE_SDK_ENV);
        }
    }

    @Test
    @DisplayName("主变量存在但为空串 + SDK 变量有值：不回退，启动失败（空 ≠ 缺失）")
    void blankPrimaryVariableDoesNotFallBack() {
        assertThatThrownBy(() -> IsolatedAppLauncher.start(Map.of(
                "MKAGENT_MODEL_API_KEY", "",
                "AI_DASHSCOPE_API_KEY", FAKE_SDK_ENV
        )).close())
                .satisfies(failure -> assertThat(IsolatedAppLauncher.deepestMessage(failure))
                        .contains("未配置模型凭据")
                        .contains("MKAGENT_MODEL_API_KEY"));
    }

    @Test
    @DisplayName("两个变量都缺失：解析成空串，启动失败且报错不含任何凭据值")
    void bothVariablesMissingFailsFast() {
        assertThatThrownBy(() -> IsolatedAppLauncher.start(Map.of()).close())
                .satisfies(failure -> {
                    String message = IsolatedAppLauncher.deepestMessage(failure);
                    assertThat(message)
                            .contains("未配置模型凭据")
                            .contains("MKAGENT_MODEL_API_KEY")
                            .doesNotContain("sk-")
                            .doesNotContain(IsolatedAppLauncher.FAKE_SEARCH_KEY);
                });
    }

    // ==================== 4. 只有外部 config 文件 ====================

    @Test
    @DisplayName("只有外部 config/application-local.yml：来源是 file，压过 classpath 模板")
    void onlyExternalConfigFile() throws Exception {
        Path configDir = externalConfigDir("spring.ai.dashscope.api-key: " + FAKE_EXTERNAL);

        try (ConfigurableApplicationContext context = IsolatedAppLauncher.start(
                Map.of(),
                "--spring.config.additional-location=file:" + configDir.toAbsolutePath() + "/")) {

            ConfigurableEnvironment env = context.getEnvironment();
            assertThat(env.getProperty(MODEL_KEY_PROPERTY)).isEqualTo(FAKE_EXTERNAL);
            assertThat(IsolatedAppLauncher.sourceOf(env, MODEL_KEY_PROPERTY))
                    .contains("file [")
                    .contains("application-local.yml");
            assertThat(modelKey(context)).isEqualTo(FAKE_EXTERNAL);
        }
    }

    // ==================== 5. 环境变量与外部属性同时存在 ====================

    @Test
    @DisplayName("环境变量 + 外部同名属性：外部属性赢，环境变量被忽略（来源覆盖）")
    void externalPropertyWinsOverEnvironmentVariable() throws Exception {
        Path configDir = externalConfigDir("spring.ai.dashscope.api-key: " + FAKE_EXTERNAL);

        try (ConfigurableApplicationContext context = IsolatedAppLauncher.start(
                Map.of("MKAGENT_MODEL_API_KEY", FAKE_PROJECT_ENV),
                "--spring.config.additional-location=file:" + configDir.toAbsolutePath() + "/")) {

            ConfigurableEnvironment env = context.getEnvironment();
            // 关键结论：外部文件直接定义了属性，模板里的占位符整条被替换掉，
            // 环境变量没有机会参与解析。
            assertThat(env.getProperty(MODEL_KEY_PROPERTY)).isEqualTo(FAKE_EXTERNAL);
            assertThat(env.getProperty(MODEL_KEY_PROPERTY)).isNotEqualTo(FAKE_PROJECT_ENV);
            assertThat(IsolatedAppLauncher.sourceOf(env, MODEL_KEY_PROPERTY)).contains("file [");
            assertThat(modelKey(context)).isEqualTo(FAKE_EXTERNAL);
        }
    }

    // ==================== 6. chat.api-key 与 connection.api-key ====================

    @Test
    @DisplayName("chat.api-key 优先于公共 connection.api-key（模型实例持有的就是 chat 级）")
    void chatLevelKeyWinsOverConnectionLevel() {
        try (ConfigurableApplicationContext context = IsolatedAppLauncher.start(
                Map.of(),
                "--spring.ai.dashscope.chat.api-key=" + FAKE_CHAT_LEVEL,
                "--spring.ai.dashscope.api-key=" + FAKE_CONNECTION_LEVEL)) {

            // 生产代码用的解析函数（与库的 resolveConnectionProperties 同序）
            assertThat(configuredKey(context)).isEqualTo(FAKE_CHAT_LEVEL);

            // 端到端：模型实例最终持有的 key
            assertThat(modelKey(context)).isEqualTo(FAKE_CHAT_LEVEL);
        }
    }

    @Test
    @DisplayName("chat.api-key 为空串时回落到 connection.api-key（hasText 语义）")
    void blankChatLevelKeyFallsBackToConnectionLevel() {
        try (ConfigurableApplicationContext context = IsolatedAppLauncher.start(
                Map.of(),
                "--spring.ai.dashscope.chat.api-key=",
                "--spring.ai.dashscope.api-key=" + FAKE_CONNECTION_LEVEL)) {

            assertThat(configuredKey(context)).isEqualTo(FAKE_CONNECTION_LEVEL);
            assertThat(modelKey(context)).isEqualTo(FAKE_CONNECTION_LEVEL);
        }
    }

    // ==================== 辅助 ====================

    private Path externalConfigDir(String content) throws Exception {
        Path dir = tempDir.resolve("config");
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("application-local.yml"),
                content + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
        return dir;
    }

    private static String configuredKey(ConfigurableApplicationContext context) {
        return DashScopeChatModelConfig.configuredApiKey(
                context.getBean(DashScopeChatProperties.class),
                context.getBean(DashScopeConnectionProperties.class)
        );
    }

    /**
     * 读模型实例里真正持有的 api-key。
     *
     * <p>为什么用反射：{@code DashScopeChatModel} 没有暴露 api-key，
     * 而"实际优先级"必须看模型手里那个对象，不能只看配置解析结果。
     * 字段名来自 {@code javap -p}（{@code dashscopeApi} / {@code apiKey}）；
     * 依赖升级导致改名时，本测试会以 NoSuchFieldException 明确失败。
     */
    private static String modelKey(ConfigurableApplicationContext context) {
        try {
            DashScopeChatModel model = context.getBean(DashScopeChatModel.class);
            Field apiField = DashScopeChatModel.class.getDeclaredField("dashscopeApi");
            apiField.setAccessible(true);
            DashScopeApi api = (DashScopeApi) apiField.get(model);

            Field keyField = DashScopeApi.class.getDeclaredField("apiKey");
            keyField.setAccessible(true);
            ApiKey apiKey = (ApiKey) keyField.get(api);
            return apiKey == null ? null : apiKey.getValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取模型持有的 api-key，依赖结构可能已变化", e);
        }
    }
}
