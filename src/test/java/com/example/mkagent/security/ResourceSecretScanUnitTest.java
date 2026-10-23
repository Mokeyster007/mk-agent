package com.example.mkagent.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源目录密钥扫描测试。
 *
 * 目标：保证 src/main/resources 下不会混入明文密钥（防止再次提交泄露）。
 *
 * 规则：
 * 1. 32 位十六进制字符串（常见 API Key / MD5 形态的密钥）；
 * 2. sk- 前缀密钥（DashScope / OpenAI 风格）；
 * 3. api-key / apiKey / api_key 被赋了非占位符的值。
 *
 * 排除项：
 * - application-local.yml 是本机私有配置（含真实密钥，已被 .gitignore 忽略，
 *   永远不会进入版本库）。它必须留在本机才能启动项目，
 *   因此扫描时排除，否则本地跑测试必然失败。
 *
 * 报告只输出“规则 + 行号”，不输出命中的具体值，避免测试日志二次泄露。
 */
class ResourceSecretScanUnitTest {

    private static final Path RESOURCES_DIR =
            Paths.get("src", "main", "resources");

    /**
     * 本机私有配置：含真实密钥但被 .gitignore 忽略，不参与扫描。
     */
    private static final Set<String> EXCLUDED_FILE_NAMES =
            Set.of("application-local.yml");

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
     * 占位符形态：空值、${ENV}、your-xxx、<...>、xxx。
     */
    private static final List<Pattern> PLACEHOLDER_PATTERNS = List.of(
            Pattern.compile("^$"),
            Pattern.compile("^\\$\\{[^}]+}$"),
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
                    .filter(path -> !EXCLUDED_FILE_NAMES.contains(
                            path.getFileName().toString()))
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
                """;

        assertEquals(
                List.of(),
                findViolations("safe.yml", safe),
                "占位符与环境变量引用不应被判为密钥"
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
