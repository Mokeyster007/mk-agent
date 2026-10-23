package com.example.mkagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评测用例加载器。
 *
 * 支持两种路径：
 * 1. classpath:eval/cases.json（默认，测试资源）；
 * 2. 绝对 / 相对文件路径（便于临时替换用例集）。
 *
 * 加载时做严格校验，避免一条坏用例导致整轮评测结论失真。
 */
public class EvalCaseLoader {

    private static final String CLASSPATH_PREFIX = "classpath:";

    private final ObjectMapper objectMapper;

    public EvalCaseLoader() {
        this(new ObjectMapper());
    }

    public EvalCaseLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 加载并校验用例列表。
     *
     * @param location classpath: 或文件路径
     * @return 不可变用例列表
     */
    public List<EvalCase> load(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("评测用例路径不能为空");
        }

        Resource resource = resolve(location);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "评测用例文件不存在：" + location
            );
        }

        try (InputStream inputStream = resource.getInputStream()) {
            List<EvalCase> cases = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<EvalCase>>() {
                    }
            );

            validate(cases);
            return List.copyOf(cases);

        } catch (IOException e) {
            throw new IllegalStateException(
                    "读取评测用例失败：" + location,
                    e
            );
        }
    }

    private Resource resolve(String location) {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            String path = location.substring(CLASSPATH_PREFIX.length());
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return new ClassPathResource(path);
        }
        return new FileSystemResource(location);
    }

    /**
     * 校验字段完整性与用例之间的一致性。
     */
    private void validate(List<EvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalStateException("评测用例列表不能为空");
        }

        Set<String> ids = new HashSet<>();

        for (int i = 0; i < cases.size(); i++) {
            EvalCase evalCase = cases.get(i);
            String prefix = "评测用例校验失败（index=" + i
                    + ", id=" + (evalCase == null ? "null" : evalCase.getId())
                    + "）：";

            if (evalCase == null) {
                throw new IllegalStateException(prefix + "用例不能为 null");
            }
            if (isBlank(evalCase.getId())) {
                throw new IllegalStateException(prefix + "id 不能为空");
            }
            if (!ids.add(evalCase.getId())) {
                throw new IllegalStateException(prefix + "id 重复");
            }
            if (isBlank(evalCase.getUserPrompt())) {
                throw new IllegalStateException(prefix + "userPrompt 不能为空");
            }
            if (isBlank(evalCase.getCategory())) {
                throw new IllegalStateException(prefix + "category 不能为空");
            }
            if (evalCase.getMaxSteps() < 1) {
                throw new IllegalStateException(prefix + "maxSteps 必须 >= 1");
            }
            if (evalCase.getMaxToolCalls() < 1) {
                throw new IllegalStateException(prefix + "maxToolCalls 必须 >= 1");
            }

            for (String tool : evalCase.getExpectedTools()) {
                if (isBlank(tool)) {
                    throw new IllegalStateException(prefix + "expectedTools 不能包含空工具名");
                }
            }
            for (String tool : evalCase.getForbiddenTools()) {
                if (isBlank(tool)) {
                    throw new IllegalStateException(prefix + "forbiddenTools 不能包含空工具名");
                }
            }
            for (String keyword : evalCase.getExpectedKeywords()) {
                if (isBlank(keyword)) {
                    throw new IllegalStateException(prefix + "expectedKeywords 不能包含空关键词");
                }
            }

            Set<String> overlap = new HashSet<>(evalCase.getExpectedTools());
            overlap.retainAll(evalCase.getForbiddenTools());
            if (!overlap.isEmpty()) {
                throw new IllegalStateException(
                        prefix + "expectedTools 与 forbiddenTools 冲突：" + overlap
                );
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
