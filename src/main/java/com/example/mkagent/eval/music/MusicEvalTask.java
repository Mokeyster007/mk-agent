package com.example.mkagent.eval.music;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一条音乐任务评测用例。
 *
 * <p>字段与 {@code src/test/resources/eval/music-tasks.json} 一一对应。
 * 设计原则：**只描述可被当前代码支持的能力**——工具名必须是
 * {@link MusicEvalTaskSet#KNOWN_TOOLS} 里的真实工具，不能写"情绪标签""歌词搜索"
 * 这类不存在的字段，否则评测测的是想象出来的系统。
 *
 * <p>为什么任务集放在 JSON 而不是 Java 常量：
 * 1. 任务集是"评测对象"，会随版本演进，改动不应该要求重新编译；
 * 2. 报告、脚本、人工复核说明可以放在一起，review 时一眼能对上；
 * 3. 与既有 {@code eval/cases.json} 保持同一种组织方式。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MusicEvalTask {

    /**
     * 任务唯一标识，同时用于生成隔离数据的名字前缀。
     */
    private String taskId;

    /**
     * 类别：QUERY / MULTI_STEP / CLARIFY / APPROVAL / FAILURE。
     */
    private String category;

    /**
     * 入口类型：MUSIC_USER / MUSIC_ADMIN。决定工具白名单与系统提示词。
     */
    private String entry = "MUSIC_USER";

    /**
     * 测试用户：alice / bob / admin（映射到种子用户 ID）。
     *
     * <p>注意：这是**测试身份**，通过服务端的 userId 字段注入，
     * 不等同于生产认证。生产入口的身份来自请求头，且当前版本没有真实认证。
     */
    private String user;

    /**
     * 用户输入（单轮）。占位符 {@code {{name}}} / {@code {{fixture}}}
     * 由评测执行器替换为本次运行唯一的歌单名。
     */
    private String userInput;

    /**
     * 审批脚本：NONE / APPROVE / REJECT / HOLD。
     *
     * <p>审批由评测脚本按预定条件驱动，**不让模型自行批准**。
     */
    private String approval = "NONE";

    /**
     * 能力模式声明：{@code READ_ONLY} / {@code WRITE_APPROVAL}。
     *
     * <p><b>必填</b>，不接受"由脚本或预期写入量推断"。原因：
     * 像 c01（"帮我建个歌单"）这类任务，脚本里没有写工具，
     * 但它测的是"写能力已提供、只是信息不足时该不该追问"。
     * 若按"脚本里有没有写工具"推断，它会退化成只读任务，
     * 提示词也随之变成"不提供写能力"，与任务本意相反。
     *
     * <p>显式声明的另一个好处是：脚本化执行与真实模型执行**共用同一个取值**，
     * 不存在"脚本跑的是写能力、真实模型跑的是只读"这种口径漂移。
     */
    private String capability;

    /**
     * 确定性模式下喂给模型的脚本化回合；真实模型模式下被忽略。
     *
     * <p>它的作用不是"替模型答题"，而是把执行链路（工具、审批、事务、落库）
     * 跑成一个可复现的已知输入，用来验证检查器与执行器本身是否可靠。
     */
    private List<ScriptTurn> script = new ArrayList<>();

    /**
     * 任务开始前要准备的隔离数据（只用于本任务，结束后删除）。
     */
    private List<PlaylistFixture> initialData = new ArrayList<>();

    /**
     * 必须发生的结果。
     */
    private Expectation expect = new Expectation();

    /**
     * 禁止发生的结果。
     */
    private Forbid forbid = new Forbid();

    /**
     * 无法自动判定、需要人工复核的语义项说明；为空表示全自动可判。
     */
    private String manualReview = "";

    /**
     * 一个脚本回合：要么是一次工具调用，要么是一条文本回答。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScriptTurn {

        private String tool;

        private Map<String, Object> args = Map.of();

        private String answer;

        public boolean isToolCall() {
            return tool != null && !tool.isBlank();
        }

        public String getTool() {
            return tool;
        }

        public void setTool(String tool) {
            this.tool = tool;
        }

        public Map<String, Object> getArgs() {
            return args == null ? Map.of() : args;
        }

        public void setArgs(Map<String, Object> args) {
            this.args = args;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }
    }

    /**
     * 隔离数据：一条待准备的歌单。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlaylistFixture {

        private String name;

        private List<Long> songs = new ArrayList<>();

        private boolean isPublic;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Long> getSongs() {
            return songs == null ? List.of() : List.copyOf(songs);
        }

        public void setSongs(List<Long> songs) {
            this.songs = songs;
        }

        public boolean isPublic() {
            return isPublic;
        }

        public void setPublic(boolean isPublic) {
            this.isPublic = isPublic;
        }
    }

    /**
     * 期望结果。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Expectation {

        /**
         * 期望的最终运行状态（读 {@code agent_run.state}）。
         */
        private String runState = "SUCCEEDED";

        /**
         * 必须实际调用到的工具（只看是否出现，不约束顺序）。
         */
        private List<String> requiredTools = new ArrayList<>();

        /**
         * 任务结束后必须存在的歌单及其**精确**歌曲集合。
         */
        private List<PlaylistExpectation> playlists = new ArrayList<>();

        /**
         * 歌单总数增量；null 表示不断言。
         */
        private Integer playlistCountDelta;

        /**
         * 审批状态断言；null 表示不断言。
         */
        private String approvalStatus;

        /**
         * 审批执行子状态断言；null 表示不断言。
         */
        private String approvalResumeState;

        /**
         * 最终回答必须包含的片段（全部命中）。
         */
        private List<String> answerContains = new ArrayList<>();

        /**
         * 澄清类型。
         *
         * <p>以前只有一个 boolean，把"追问细节"和"说明不支持"当成同一件事，
         * 结果 c01（写能力已提供、只是信息不足）里模型答"当前版本不支持创建歌单"
         * 也被判成合理澄清——这是误判。两类回答的**正确性前提相反**，必须分开：
         *
         * <ul>
         *   <li>{@link #ASK}：能力存在，缺的是信息，必须问出具体要素；</li>
         *   <li>{@link #DENY}：能力确实不存在，必须明确说明边界；</li>
         *   <li>{@link #NONE}：不涉及澄清。</li>
         * </ul>
         */
        public enum ClarificationKind {

            NONE,
            ASK,
            DENY
        }

        /**
         * 期望的澄清类型，默认不涉及澄清。
         */
        private ClarificationKind clarification = ClarificationKind.NONE;

        /**
         * ASK 模式下必须问到的**信息要素**，每个要素都要被问到。
         *
         * <p>为什么从"一维关键词表"改成"要素分组"：
         * 原来用 {@code askKeywords} 的"任一命中"，两处都不对——
         * 一是一句话里问到两个要素也只算命中一次，
         * 二是同义表达换个说法就漏判。实测 c02 的回答是
         * "请提供您想添加的歌曲名称或歌手，以及目标歌单的名称"，
         * 明明把两件事都问清了，却因为词表里只有"歌名/歌单名"而被判成
         * "追问过于笼统"。按要素组织同义表达，判的是"问没问到这个要素"，
         * 而不是"有没有说出那个词"。
         *
         * <p>每个要素内部仍是"任一命中即可"——同义表达不该逐个枚举到穷尽。
         */
        private List<AskElement> askElements = new ArrayList<>();

        /**
         * 工具替代路径：某个必需工具没被调用时，允许用另一个工具的**响应级证据**替代。
         *
         * <p>为什么需要它：q02 问"哪一年发行"，检查项要求必须调用
         * {@code get_song_detail}。但 {@code search_songs} 的响应体里本来就带
         * {@code releaseYear}，模型只调检索就答对了年份，却被判成"缺少工具"。
         * 约束的本意是"答案必须有查询依据"，不是"必须走某一条工具路径"。
         *
         * <p>替代不是放宽：只有被替代工具的响应里**真的出现**了指定字段与取值，
         * 才算满足；否则仍然按缺少工具判失败。
         */
        private List<ToolFallback> toolFallbacks = new ArrayList<>();

        /**
         * DENY 任务要求给出的"支持范围内替代操作"（任一命中即可）。
         *
         * <p>只说"不支持"是消极回答；按业务边界，还应告诉用户
         * 当前版本能做什么（换关键词、按歌手/歌名检索等）。
         * 为空表示不要求。
         */
        private List<String> boundaryAlternativeKeywords = new ArrayList<>();

        /**
         * 最终回答中"任一命中即可"的片段（用于归因是否正确等宽松断言）。
         */
        private List<String> answerAnyOf = new ArrayList<>();

        /**
         * 回答里对"为什么没做"的归因要求。
         *
         * <p>为什么不能只看 {@link #answerAnyOf}：a02（用户拒绝）里
         * "没有创建"/"未创建"这类说法只描述**结果**，不描述**原因**。
         * "系统不支持创建，所以没有创建"、"执行失败，没有创建"都能命中它，
         * 却把"用户拒绝"错判成通过。归因必须单独判定：
         * 出现拒绝语义、且不出现能力缺失/执行失败语义，才算说对了原因。
         */
        public enum Attribution {

            NONE,
            USER_REJECTED
        }

        /**
         * 期望的归因，默认不涉及。
         */
        private Attribution attribution = Attribution.NONE;

        private int maxSteps = 8;

        private int maxToolCalls = 12;

        public String getRunState() {
            return runState;
        }

        public void setRunState(String runState) {
            this.runState = runState;
        }

        public List<String> getRequiredTools() {
            return requiredTools == null ? List.of() : List.copyOf(requiredTools);
        }

        public void setRequiredTools(List<String> requiredTools) {
            this.requiredTools = requiredTools;
        }

        public List<PlaylistExpectation> getPlaylists() {
            return playlists == null ? List.of() : List.copyOf(playlists);
        }

        public void setPlaylists(List<PlaylistExpectation> playlists) {
            this.playlists = playlists;
        }

        public Integer getPlaylistCountDelta() {
            return playlistCountDelta;
        }

        public void setPlaylistCountDelta(Integer playlistCountDelta) {
            this.playlistCountDelta = playlistCountDelta;
        }

        public String getApprovalStatus() {
            return approvalStatus;
        }

        public void setApprovalStatus(String approvalStatus) {
            this.approvalStatus = approvalStatus;
        }

        public String getApprovalResumeState() {
            return approvalResumeState;
        }

        public void setApprovalResumeState(String approvalResumeState) {
            this.approvalResumeState = approvalResumeState;
        }

        public List<String> getAnswerContains() {
            return answerContains == null ? List.of() : List.copyOf(answerContains);
        }

        public void setAnswerContains(List<String> answerContains) {
            this.answerContains = answerContains;
        }

        public ClarificationKind getClarification() {
            return clarification == null ? ClarificationKind.NONE : clarification;
        }

        public void setClarification(ClarificationKind clarification) {
            this.clarification = clarification;
        }

        public List<AskElement> getAskElements() {
            return askElements == null ? List.of() : List.copyOf(askElements);
        }

        public void setAskElements(List<AskElement> askElements) {
            this.askElements = askElements;
        }

        public List<ToolFallback> getToolFallbacks() {
            return toolFallbacks == null ? List.of() : List.copyOf(toolFallbacks);
        }

        public void setToolFallbacks(List<ToolFallback> toolFallbacks) {
            this.toolFallbacks = toolFallbacks;
        }

        /**
         * 取某个必需工具的替代路径；没有声明返回 null。
         */
        public ToolFallback fallbackFor(String requiredTool) {
            for (ToolFallback fallback : getToolFallbacks()) {
                if (fallback != null && requiredTool.equals(fallback.getInsteadOf())) {
                    return fallback;
                }
            }
            return null;
        }

        public List<String> getBoundaryAlternativeKeywords() {
            return boundaryAlternativeKeywords == null
                    ? List.of()
                    : List.copyOf(boundaryAlternativeKeywords);
        }

        public void setBoundaryAlternativeKeywords(List<String> boundaryAlternativeKeywords) {
            this.boundaryAlternativeKeywords = boundaryAlternativeKeywords;
        }

        public List<String> getAnswerAnyOf() {
            return answerAnyOf == null ? List.of() : List.copyOf(answerAnyOf);
        }

        public void setAnswerAnyOf(List<String> answerAnyOf) {
            this.answerAnyOf = answerAnyOf;
        }

        public Attribution getAttribution() {
            return attribution == null ? Attribution.NONE : attribution;
        }

        public void setAttribution(Attribution attribution) {
            this.attribution = attribution;
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
    }

    /**
     * 期望存在的歌单。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlaylistExpectation {

        private String name;

        private List<Long> songs = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Long> getSongs() {
            return songs == null ? List.of() : List.copyOf(songs);
        }

        public void setSongs(List<Long> songs) {
            this.songs = songs;
        }
    }

    /**
     * 一个"必须被问到"的信息要素。
     *
     * <p>例如 c02 的"这首歌"没有指代对象，必须同时问清两件事：
     * 是哪首歌、加到哪个歌单。这两件事各是一个要素，
     * 每个要素下挂一组同义表达（"歌名/歌曲名称/哪一首/歌手…"）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AskElement {

        /**
         * 要素名（写进报告，说明"缺的是哪一项"）。
         */
        private String name;

        /**
         * 该要素的同义表达，任一命中即算问到。
         */
        private List<String> anyOf = new ArrayList<>();

        public String getName() {
            return name == null ? "" : name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getAnyOf() {
            return anyOf == null ? List.of() : List.copyOf(anyOf);
        }

        public void setAnyOf(List<String> anyOf) {
            this.anyOf = anyOf;
        }
    }

    /**
     * 工具替代路径：允许用另一个工具的响应级证据，替代某个必需工具。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolFallback {

        /**
         * 被替代的必需工具名（{@code requiredTools} 里的那个）。
         */
        private String insteadOf;

        /**
         * 可以替代它的工具名（必须真实调用过）。
         */
        private List<String> tools = new ArrayList<>();

        /**
         * 替代工具的响应里必须同时出现的字段名或取值。
         *
         * <p>这是"证据"而不是"措辞"：只匹配工具真实返回的内容，
         * 不看模型说了什么。字段名（如 {@code releaseYear}）与取值（如 {@code 2021}）
         * 都要在**同一次**响应里出现，才算这条路径成立。
         */
        private List<String> responseMustContain = new ArrayList<>();

        /**
         * 为什么这条替代是合理的（写进报告，便于人工复核）。
         */
        private String reason = "";

        public String getInsteadOf() {
            return insteadOf == null ? "" : insteadOf;
        }

        public void setInsteadOf(String insteadOf) {
            this.insteadOf = insteadOf;
        }

        public List<String> getTools() {
            return tools == null ? List.of() : List.copyOf(tools);
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }

        public List<String> getResponseMustContain() {
            return responseMustContain == null ? List.of() : List.copyOf(responseMustContain);
        }

        public void setResponseMustContain(List<String> responseMustContain) {
            this.responseMustContain = responseMustContain;
        }

        public String getReason() {
            return reason == null ? "" : reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /**
     * 禁止结果。
     *
     * <p>默认值刻意取"最严"：任何写入都禁止、任何成功措辞都禁止。
     * 漏填等于收紧，不会因为忘了写而放过一次越权写入。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Forbid {

        /**
         * 禁止任何业务写入（歌单 / 歌单歌曲）。
         */
        private boolean anyWrite = true;

        /**
         * 禁止调用的工具。
         */
        private List<String> tools = new ArrayList<>();

        /**
         * 最终回答里禁止出现的措辞（用于识别"虚报成功"）。
         *
         * <p>只放"声称某个动作已经完成"的话术（已创建 / 已添加 / 已为你找到）。
         * 编造内容类（不存在的歌单名、内部用户 ID、凭空推荐的歌）放
         * {@link #hallucinationPhrases}——两者失败性质不同，不能混在一起判。
         */
        private List<String> answerPhrases = new ArrayList<>();

        /**
         * 最终回答里禁止出现的"编造内容"（用于识别答非事实）。
         *
         * <p>与 {@link #answerPhrases} 分开的原因：
         * 虚报成功是安全问题（B 类），编造内容是回答准确性问题（C 类）。
         * 混在一起会让安全违规数被语义问题污染。
         */
        private List<String> hallucinationPhrases = new ArrayList<>();

        /**
         * 未发生写入时，禁止声称已经完成（失败/拒绝/等待场景）。
         */
        private boolean falseSuccess = true;

        public boolean isAnyWrite() {
            return anyWrite;
        }

        public void setAnyWrite(boolean anyWrite) {
            this.anyWrite = anyWrite;
        }

        public List<String> getTools() {
            return tools == null ? List.of() : List.copyOf(tools);
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }

        public List<String> getAnswerPhrases() {
            return answerPhrases == null ? List.of() : List.copyOf(answerPhrases);
        }

        public List<String> getHallucinationPhrases() {
            return hallucinationPhrases == null ? List.of() : List.copyOf(hallucinationPhrases);
        }

        public void setHallucinationPhrases(List<String> hallucinationPhrases) {
            this.hallucinationPhrases = hallucinationPhrases;
        }

        public void setAnswerPhrases(List<String> answerPhrases) {
            this.answerPhrases = answerPhrases;
        }

        public boolean isFalseSuccess() {
            return falseSuccess;
        }

        public void setFalseSuccess(boolean falseSuccess) {
            this.falseSuccess = falseSuccess;
        }
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getEntry() {
        return entry;
    }

    public void setEntry(String entry) {
        this.entry = entry;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public String getApproval() {
        return approval;
    }

    public void setApproval(String approval) {
        this.approval = approval;
    }

    public String getCapability() {
        return capability;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public List<ScriptTurn> getScript() {
        return script == null ? List.of() : List.copyOf(script);
    }

    public void setScript(List<ScriptTurn> script) {
        this.script = script;
    }

    public List<PlaylistFixture> getInitialData() {
        return initialData == null ? List.of() : List.copyOf(initialData);
    }

    public void setInitialData(List<PlaylistFixture> initialData) {
        this.initialData = initialData;
    }

    public Expectation getExpect() {
        return expect == null ? new Expectation() : expect;
    }

    public void setExpect(Expectation expect) {
        this.expect = expect;
    }

    public Forbid getForbid() {
        return forbid == null ? new Forbid() : forbid;
    }

    public void setForbid(Forbid forbid) {
        this.forbid = forbid;
    }

    public String getManualReview() {
        return manualReview == null ? "" : manualReview;
    }

    public void setManualReview(String manualReview) {
        this.manualReview = manualReview;
    }
}
