package com.example.mkagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条离线评测用例。
 *
 * JSON 字段与 {@code src/test/resources/eval/cases.json} 一一对应：
 * 1. id                用例唯一标识；
 * 2. userPrompt        发送给 MkManus 的用户任务；
 * 3. expectedTools     必须实际调用到的工具名；
 * 4. forbiddenTools    不允许调用的工具名；
 * 5. maxSteps          本轮评测允许的最大 Agent Loop 步数；
 * 6. maxToolCalls      本轮评测允许的最大工具调用次数；
 * 7. expectedKeywords  最终回答必须包含的关键词（为空表示交给 LLM-as-judge）；
 * 8. category          类别标签，用于分类别通过率。
 *
 * 注意：
 * expectedTools 与 forbiddenTools 不允许出现同一个工具，
 * 由 {@link EvalCaseLoader} 在加载时校验。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCase {

    private String id;

    private String userPrompt;

    private List<String> expectedTools = new ArrayList<>();

    private List<String> forbiddenTools = new ArrayList<>();

    private int maxSteps = 8;

    private int maxToolCalls = 12;

    private List<String> expectedKeywords = new ArrayList<>();

    private String category;

    public EvalCase() {
        // Jackson 反序列化需要无参构造器。
    }

    /**
     * 单元测试 / 程序化构造用例时使用。
     */
    public EvalCase(
            String id,
            String userPrompt,
            List<String> expectedTools,
            List<String> forbiddenTools,
            int maxSteps,
            int maxToolCalls,
            List<String> expectedKeywords,
            String category
    ) {
        this.id = id;
        this.userPrompt = userPrompt;
        setExpectedTools(expectedTools);
        setForbiddenTools(forbiddenTools);
        this.maxSteps = maxSteps;
        this.maxToolCalls = maxToolCalls;
        setExpectedKeywords(expectedKeywords);
        this.category = category;
    }

    /**
     * 没有关键词时，确定性检查无法判断内容是否正确，
     * 需要交给 LLM-as-judge。
     */
    public boolean requiresJudge() {
        return expectedKeywords.isEmpty();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public void setUserPrompt(String userPrompt) {
        this.userPrompt = userPrompt;
    }

    public List<String> getExpectedTools() {
        return List.copyOf(expectedTools);
    }

    public void setExpectedTools(List<String> expectedTools) {
        this.expectedTools = copy(expectedTools);
    }

    public List<String> getForbiddenTools() {
        return List.copyOf(forbiddenTools);
    }

    public void setForbiddenTools(List<String> forbiddenTools) {
        this.forbiddenTools = copy(forbiddenTools);
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public List<String> getExpectedKeywords() {
        return List.copyOf(expectedKeywords);
    }

    public void setExpectedKeywords(List<String> expectedKeywords) {
        this.expectedKeywords = copy(expectedKeywords);
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    private static List<String> copy(List<String> source) {
        return source == null
                ? new ArrayList<>()
                : new ArrayList<>(source);
    }
}
