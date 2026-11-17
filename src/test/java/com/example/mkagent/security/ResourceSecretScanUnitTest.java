package com.example.mkagent.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源目录密钥扫描测试。
 *
 * 目标：保证 src/main/resources 下不会混入明文密钥（防止再次提交泄露）。
 *
 * 规则：
 * 1. 32 位十六进制字符串（常见 API Key / MD5 形态的密钥）；
 * 2. sk- 前缀密钥（DashScope / OpenAI 风格）；
 * 3. api-key / apiKey / api_key 被赋了非占位符的值；
 * 4. password / secret / token 被赋了非占位符的值。
 *
 * 关于 application-local.yml：
 * 它现在**已经入库**，只允许出现 {@code ${ENV:default}} 占位符。
 * 真实值放在仓库根的 config/application-local.yml（被 .gitignore 忽略，
 * 且不在 classpath 上、不会被打包），因此这里必须把它纳入扫描——
 * 之前的版本把它排除在外，等于把最大的泄露入口留在扫描盲区里。
 *
 * 报告只输出“规则 + 行号”，不输出命中的具体值，避免测试日志二次泄露。
 */
class ResourceSecretScanUnitTest {

    private static final Path RESOURCES_DIR =
            Paths.get("src", "main", "resources");

    /**
     * 允许名单：确属示例/文档用途、可以出现在资源文件中的字面量。
     * 目前为空，后续如需放行请在此登记并写明原因。
     */
    private static final Set<String> ALLOWLIST = Set.of();

    private static final Pattern HEX_32 =
            Pattern.compile("\\b[0-9a-fA-F]{32}\\b");

    private static final Pattern SK_PREFIX =
            Pattern.compile("sk-[A-Za-z0-9._-]{16,}");

    private static final Pattern API_KEY_ASSIGNMENT = Pattern.compile(
            "(?i)api[-_]?key[\"']?\\s*[:=]\\s*[\"']?([^\"'\\s,]*)"
    );

    /**
     * 数据库口令 / secret / token 的明文赋值。
     * 与 api-key 规则分开，便于报告里区分是哪一类凭据。
     */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:password|passwd|secret|token)[\"']?\\s*[:=]\\s*[\"']?([^\"'\\s,]*)"
    );

    /**
     * 占位符形态：空值、${ENV}（含嵌套 ${A:${B:}}）、your-xxx、<...>、xxx。
     *
     * <p>嵌套写法必须放行：模型凭据用的是
     * {@code ${MKAGENT_MODEL_API_KEY:${AI_DASHSCOPE_API_KEY:}}}，
     * 只用 {@code ^\$\{[^}]+}$} 会把内层花括号当成"值里有内容"，误报成明文密钥。
     * 整个值就是一个 {@code ${...}} 时，运行期由 Spring 解析，源码里不可能藏真实值。
     */
    private static final List<Pattern> PLACEHOLDER_PATTERNS = List.of(
            Pattern.compile("^$"),
            Pattern.compile("^\\$\\{.*}$"),
            Pattern.compile("(?i)^your[-_].*"),
            Pattern.compile("^<.*>$"),
            Pattern.compile("(?i)^x{3,}$")
    );

    @Test
    void resourcesContainNoPlaintextSecrets() throws IOException {
        assertTrue(
                Files.isDirectory(RESOURCES_DIR),
                "找不到资源目录：" + RESOURCES_DIR.toAbsolutePath()
        );

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(RESOURCES_DIR)) {
            List<Path> targets = files
                    .filter(Files::isRegularFile)
                    .toList();

            for (Path file : targets) {
                String content = readTextOrNull(file);
                if (content == null) {
                    // 二进制文件（图片等）不参与文本扫描。
                    continue;
                }

                String relative = RESOURCES_DIR.relativize(file)
                        .toString()
                        .replace('\\', '/');

                violations.addAll(findViolations(relative, content));
            }
        }

        assertTrue(
                violations.isEmpty(),
                "资源目录中发现疑似明文密钥（请改为环境变量或占位符）：\n"
                        + String.join("\n", violations)
        );
    }

    /**
     * 打包链路一致性：classpath 上的 application-local.yml 必须与源码逐字节相同。
     *
     * <p>为什么需要这条：spring-boot-starter-parent 默认对 src/main/resources 下的
     * application*.yml 打开 Maven resource filtering。只要有人写出
     * {@code @MKAGENT_MODEL_API_KEY@} 这类构建期占位符，构建时就会把当前
     * shell / CI 里的密钥展开并写回 target/classes，再原样打进 jar——
     * 源码里干净，产物里却是明文。
     *
     * <p>pom.xml 已显式关闭 filtering；本测试守住"以后别被重新打开"，
     * 同时验证配置模板确实进了打包产物（否则覆盖链路会断）。
     */
    @Test
    void classpathCopyOfLocalConfigIsNotRewrittenByTheBuild() throws IOException {
        Path source = RESOURCES_DIR.resolve("application-local.yml");
        assertTrue(
                Files.isRegularFile(source),
                "缺少 classpath 配置模板：" + source
        );

        try (InputStream classpath = getClass().getClassLoader()
                .getResourceAsStream("application-local.yml")) {
            assertNotNull(
                    classpath,
                    "application-local.yml 不在 classpath 上，"
                            + "打包产物将缺少可覆盖的配置骨架"
            );
            assertArrayEquals(
                    Files.readAllBytes(source),
                    classpath.readAllBytes(),
                    "target/classes 上的 application-local.yml 与源码不一致："
                            + "构建过程改写了配置文件（resource filtering 可能被重新打开）"
            );
        }
    }

    @Test
    void detectorFlagsPlantedSecretsSoTheScanIsNotVacuous() {
        String planted = """
                api-key: sk-abcdefghijklmnopqrstuvwxyz012345
                amap: 0123456789abcdef0123456789abcdef
                """;

        List<String> violations = findViolations("planted.yml", planted);

        assertFalse(
                violations.isEmpty(),
                "扫描器必须能识别出植入的假密钥，否则测试形同虚设"
        );
        assertTrue(violations.stream().anyMatch(v -> v.contains("32 位十六进制")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("sk- 前缀")));
    }

    @Test
    void detectorAcceptsPlaceholdersAndEnvReferences() {
        String safe = """
                api-key: ${DASHSCOPE_API_KEY}
                apiKey:
                api_key: ""
                "AMAP_MAPS_API_KEY": ""
                api-key: ${MKAGENT_MODEL_API_KEY:${AI_DASHSCOPE_API_KEY:}}
                """;

        assertEquals(
                List.of(),
                findViolations("safe.yml", safe),
                "占位符与环境变量引用（含嵌套默认值）不应被判为密钥"
        );
    }

    @Test
    void nestedPlaceholderWithLiteralDefaultIsStillFlagged() {
        // 嵌套写法放行的是"整个值是占位符"，不是"占位符里可以塞真实默认值"：
        // 默认值会被原样打进 jar，必须由 sk- / 十六进制规则兜住。
        String planted = """
                api-key: ${MKAGENT_MODEL_API_KEY:sk-abcdefghijklmnopqrstuvwxyz012345}
                """;

        assertFalse(
                findViolations("planted.yml", planted).isEmpty(),
                "占位符默认值里的字面量密钥仍然要报出来"
        );
    }

    /**
     * 扫描一段文本，返回违规描述（只含规则与行号）。
     */
    private static List<String> findViolations(String fileName, String content) {
        List<String> violations = new ArrayList<>();
        String[] lines = content.split("\\R", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNumber = i + 1;

            if (containsOutsideAllowlist(line, HEX_32)) {
                violations.add(describe(fileName, lineNumber, "32 位十六进制"));
            }

            if (containsOutsideAllowlist(line, SK_PREFIX)) {
                violations.add(describe(fileName, lineNumber, "sk- 前缀密钥"));
            }

            var matcher = API_KEY_ASSIGNMENT.matcher(line);
            while (matcher.find()) {
                String value = matcher.group(1);
                if (ALLOWLIST.contains(value) || isPlaceholder(value)) {
                    continue;
                }
                violations.add(
                        describe(fileName, lineNumber, "api-key 明文赋值")
                );
            }

            var secretMatcher = SECRET_ASSIGNMENT.matcher(line);
            while (secretMatcher.find()) {
                String value = secretMatcher.group(1);
                if (ALLOWLIST.contains(value) || isPlaceholder(value)) {
                    continue;
                }
                violations.add(
                        describe(fileName, lineNumber, "口令/secret 明文赋值")
                );
            }
        }

        return violations;
    }

    private static boolean containsOutsideAllowlist(
            String line,
            Pattern pattern
    ) {
        var matcher = pattern.matcher(line);
        while (matcher.find()) {
            if (!ALLOWLIST.contains(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlaceholder(String value) {
        return PLACEHOLDER_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(value).matches());
    }

    /**
     * 违规描述中不包含命中的具体值，避免测试日志二次泄露。
     */
    private static String describe(String fileName, int lineNumber, String rule) {
        return "  - " + fileName + ":" + lineNumber + " 命中规则【" + rule + "】";
    }

    /**
     * 按 UTF-8 读取文本；含 NUL 字节视为二进制，返回 null。
     */
    private static String readTextOrNull(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);

        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }
}
