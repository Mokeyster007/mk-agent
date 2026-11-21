package com.example.mkagent.eval.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 音乐任务集：加载 + 严格校验。
 *
 * <p>校验的目的不是"格式好看"，而是防止评测结论失真：
 * 一个写错工具名的用例永远不可能通过，却会拉低通过率，
 * 让人误以为"模型不行"；一个不存在的能力（比如歌词搜索）
 * 混进任务集，测的就是想象出来的系统。
 */
public final class MusicEvalTaskSet {

    /**
     * 当前任务集文件版本。
     *
     * <p>v2 相对 v1 的变化：{@code askKeywords}（一维关键词表）被
     * {@code askElements}（按信息要素分组）取代；新增 {@code toolFallbacks}
     * 与 {@code boundaryAlternativeKeywords}。
     *
     * <p>为什么改字段要升版本：旧文件里的 {@code askKeywords} 在新代码下会被忽略，
     * 追问要求会**静默消失**（任务照样"通过"，但什么都没检查）。
     * 宁可加载时报版本不匹配，也不要让评测悄悄变松。
     */
    public static final int CURRENT_VERSION = 2;

    /**
     * 默认任务集位置。
     */
    public static final String DEFAULT_LOCATION = "classpath:eval/music-tasks.json";

    /**
     * 当前代码里真实存在的工具名。
     *
     * <p>这个集合是"能力边界"的可执行定义：任务集里出现集合外的名字，
     * 加载直接失败，而不是等到跑的时候工具不存在。
     */
    public static final Set<String> KNOWN_TOOLS = Set.of(
            "search_songs",
            "get_song_detail",
            "get_user_playlists",
            "get_playlist_songs",
            "get_play_stats",
            "get_top_songs",
            "create_playlist",
            "add_songs_to_playlist",
            "terminate_task"
    );

    /**
     * 需要人工确认的写工具。
     */
    public static final Set<String> WRITE_TOOLS = Set.of(
            "create_playlist",
            "add_songs_to_playlist"
    );

    /**
     * 合法入口。
     */
    public static final Set<String> ENTRIES = Set.of("MUSIC_USER", "MUSIC_ADMIN");

    /**
     * 合法测试用户别名（映射在评测执行器里完成）。
     */
    public static final Set<String> USERS = Set.of("alice", "bob", "admin");

    /**
     * 合法审批脚本。
     */
    public static final Set<String> APPROVALS = Set.of("NONE", "APPROVE", "REJECT", "HOLD");

    /**
     * 任务类别。
     */
    public enum Category {

        QUERY("查询"),
        MULTI_STEP("多步骤"),
        CLARIFY("信息不足与澄清"),
        APPROVAL("审批与权限"),
        FAILURE("故障反馈");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Category parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("category 不能为空");
            }
            try {
                return Category.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知类别：" + value);
            }
        }
    }

    /**
     * 能力模式。
     *
     * <p>任务集里的 {@code capability} 字段**必填**，并且只有这一个来源：
     * 不按"预期写入量"推断，也不按"脚本里有没有写工具"推断。
     * 脚本化执行与真实模型执行都通过 {@link #capabilityOf(MusicEvalTask)} 取值，
     * 因此两种运行方式的能力口径不可能不一致。
     */
    public enum Capability {

        READ_ONLY("只读（生产默认装配）"),

        WRITE_APPROVAL("审批写能力（隔离评测启用）");

        private final String label;

        Capability(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 取任务声明的能力模式。
     *
     * <p>不做任何推断：声明缺失即抛异常。这是刻意的——
     * 一旦允许"缺省自动判断"，任务集里漏写一个字段就会让某个任务
     * 在只读提示词下测写能力（或反过来），而报告上看不出任何异常。
     */
    public static Capability capabilityOf(MusicEvalTask task) {
        String raw = task == null ? null : task.getCapability();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "任务缺少 capability 声明（READ_ONLY / WRITE_APPROVAL）："
                            + (task == null ? "null" : task.getTaskId()));
        }
        try {
            return Capability.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "capability 只能是 READ_ONLY 或 WRITE_APPROVAL，实际：" + raw);
        }
    }

    /**
     * 文件外壳。
     */
    private static class FileModel {

        private int version = CURRENT_VERSION;

        private List<MusicEvalTask> tasks = new ArrayList<>();

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public List<MusicEvalTask> getTasks() {
            return tasks;
        }

        public void setTasks(List<MusicEvalTask> tasks) {
            this.tasks = tasks;
        }
    }

    private final ObjectMapper objectMapper;

    public MusicEvalTaskSet() {
        this(new ObjectMapper());
    }

    public MusicEvalTaskSet(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从默认 classpath 位置加载。
     */
    public List<MusicEvalTask> loadDefault() {
        return load(DEFAULT_LOCATION);
    }

    /**
     * 加载并校验。
     *
     * @param location {@code classpath:...} 或文件路径
     */
    public List<MusicEvalTask> load(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("任务集路径不能为空");
        }

        Resource resource = resolve(location);
        if (!resource.exists()) {
            throw new IllegalStateException("任务集文件不存在：" + location);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            FileModel model = objectMapper.readValue(inputStream, FileModel.class);

            if (model == null || model.getTasks() == null) {
                throw new IllegalStateException("任务集为空：" + location);
            }
            if (model.getVersion() != CURRENT_VERSION) {
                throw new IllegalStateException(
                        "任务集版本不支持：期望 " + CURRENT_VERSION
                                + "，实际 " + model.getVersion());
            }

            validate(model.getTasks());
            return List.copyOf(model.getTasks());

        } catch (IOException e) {
            throw new IllegalStateException("读取任务集失败：" + location, e);
        }
    }

    private Resource resolve(String location) {
        String prefix = "classpath:";
        if (location.startsWith(prefix)) {
            String path = location.substring(prefix.length());
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return new ClassPathResource(path);
        }
        return new FileSystemResource(location);
    }

    /**
     * 校验字段与用例之间的一致性。
     */
    private void validate(List<MusicEvalTask> tasks) {
        if (tasks.isEmpty()) {
            throw new IllegalStateException("任务集不能为空");
        }

        Set<String> ids = new HashSet<>();

        for (int i = 0; i < tasks.size(); i++) {
            MusicEvalTask task = tasks.get(i);
            String prefix = "任务校验失败（index=" + i
                    + ", taskId=" + (task == null ? "null" : task.getTaskId()) + "）：";

            if (task == null) {
                throw new IllegalStateException(prefix + "任务不能为 null");
            }
            if (isBlank(task.getTaskId())) {
                throw new IllegalStateException(prefix + "taskId 不能为空");
            }
            if (!ids.add(task.getTaskId())) {
                throw new IllegalStateException(prefix + "taskId 重复");
            }
            if (isBlank(task.getUserInput())) {
                throw new IllegalStateException(prefix + "userInput 不能为空");
            }
            if (!ENTRIES.contains(task.getEntry())) {
                throw new IllegalStateException(prefix + "entry 非法：" + task.getEntry());
            }
            if (!USERS.contains(task.getUser())) {
                throw new IllegalStateException(prefix + "user 非法：" + task.getUser());
            }
            if (!APPROVALS.contains(task.getApproval())) {
                throw new IllegalStateException(prefix + "approval 非法：" + task.getApproval());
            }

            // 类别必须能解析（解析失败直接抛）
            Category.parse(task.getCategory());

            if (task.getExpect().getMaxSteps() < 1) {
                throw new IllegalStateException(prefix + "maxSteps 必须 >= 1");
            }
            if (task.getExpect().getMaxToolCalls() < 1) {
                throw new IllegalStateException(prefix + "maxToolCalls 必须 >= 1");
            }
            if (isBlank(task.getExpect().getRunState())) {
                throw new IllegalStateException(prefix + "expect.runState 不能为空");
            }

            if (task.getScript().isEmpty()) {
                throw new IllegalStateException(prefix + "script 不能为空");
            }

            Set<String> usedTools = new LinkedHashSet<>();

            for (int t = 0; t < task.getScript().size(); t++) {
                MusicEvalTask.ScriptTurn turn = task.getScript().get(t);
                boolean hasTool = turn.isToolCall();
                boolean hasAnswer = !isBlank(turn.getAnswer());

                if (hasTool == hasAnswer) {
                    throw new IllegalStateException(
                            prefix + "script[" + t + "] 必须且只能包含 tool 或 answer 之一");
                }
                if (hasTool) {
                    requireKnownTool(prefix + "script[" + t + "]", turn.getTool());
                    usedTools.add(turn.getTool());
                }
            }

            for (String tool : task.getExpect().getRequiredTools()) {
                requireKnownTool(prefix + "expect.requiredTools", tool);
            }
            for (String tool : task.getForbid().getTools()) {
                requireKnownTool(prefix + "forbid.tools", tool);
            }

            Set<String> overlap = new HashSet<>(task.getExpect().getRequiredTools());
            overlap.retainAll(task.getForbid().getTools());
            if (!overlap.isEmpty()) {
                throw new IllegalStateException(
                        prefix + "requiredTools 与 forbid.tools 冲突：" + overlap);
            }

            requireAskElements(prefix, task);
            requireToolFallbacks(prefix, task);

            /*
             * 管理员入口没有写工具与歌单工具。
             * 任务集里出现这类组合，说明用例与白名单不一致——
             * 这种用例在真实运行里必然失败，属于"任务写错了"，不是"模型不行"。
             */
            if ("MUSIC_ADMIN".equals(task.getEntry())) {
                for (String tool : usedTools) {
                    if (WRITE_TOOLS.contains(tool)
                            || "get_user_playlists".equals(tool)
                            || "get_playlist_songs".equals(tool)) {
                        throw new IllegalStateException(
                                prefix + "管理员入口不能使用工具：" + tool);
                    }
                }
            }

            /*
             * 能力声明必须显式给出，且与用例内容自洽。
             * 这里挡的是"任务写错了"，不是"模型不行"——
             * 例如只读任务里写了 create_playlist，真实运行必然失败。
             */
            String capability = task.getCapability();
            if (isBlank(capability)) {
                throw new IllegalStateException(
                        prefix + "capability 必填（READ_ONLY / WRITE_APPROVAL），"
                                + "不允许由脚本或预期写入量推断");
            }
            if (!"READ_ONLY".equals(capability) && !"WRITE_APPROVAL".equals(capability)) {
                throw new IllegalStateException(
                        prefix + "capability 只能是 READ_ONLY 或 WRITE_APPROVAL，实际："
                                + capability);
            }
            if ("MUSIC_ADMIN".equals(task.getEntry()) && "WRITE_APPROVAL".equals(capability)) {
                throw new IllegalStateException(
                        prefix + "管理员入口不能声明 WRITE_APPROVAL（不顺便扩权）");
            }

            if ("READ_ONLY".equals(capability)) {
                requireReadOnlyConsistency(prefix, task, usedTools);
            } else {
                requireWriteApprovalConsistency(prefix, task, usedTools);
            }
        }
    }

    /**
     * 只读任务的自洽性：不能有写工具、不能有审批、不能期望写入结果。
     */
    private void requireReadOnlyConsistency(
            String prefix,
            MusicEvalTask task,
            Set<String> usedTools
    ) {
        for (String tool : usedTools) {
            if (WRITE_TOOLS.contains(tool)) {
                throw new IllegalStateException(
                        prefix + "只读任务的脚本里不能出现写工具：" + tool);
            }
        }
        for (String tool : task.getExpect().getRequiredTools()) {
            if (WRITE_TOOLS.contains(tool)) {
                throw new IllegalStateException(
                        prefix + "只读任务不能要求写工具：" + tool);
            }
        }
        if (!task.getForbid().isAnyWrite()) {
            throw new IllegalStateException(
                    prefix + "只读任务必须禁止写入（forbid.anyWrite=true）");
        }
        if (!task.getExpect().getPlaylists().isEmpty()) {
            throw new IllegalStateException(
                    prefix + "只读任务不能断言歌单产物");
        }
        if (!"NONE".equals(task.getApproval())) {
            throw new IllegalStateException(
                    prefix + "只读任务不会有审批，approval 必须是 NONE，实际："
                            + task.getApproval());
        }
        if (task.getExpect().getApprovalStatus() != null
                || task.getExpect().getApprovalResumeState() != null) {
            throw new IllegalStateException(
                    prefix + "只读任务不能断言审批状态");
        }
        Integer delta = task.getExpect().getPlaylistCountDelta();
        if (delta != null && delta != 0) {
            throw new IllegalStateException(
                    prefix + "只读任务的歌单增量只能是 0，实际：" + delta);
        }
    }

    /**
     * 审批写任务的自洽性：脚本真去写时必须走审批，不能"写着写着就落库"。
     */
    private void requireWriteApprovalConsistency(
            String prefix,
            MusicEvalTask task,
            Set<String> usedTools
    ) {
        if (!"MUSIC_USER".equals(task.getEntry())) {
            throw new IllegalStateException(
                    prefix + "WRITE_APPROVAL 只能用于 MUSIC_USER 入口");
        }

        boolean scriptWrites = usedTools.stream().anyMatch(WRITE_TOOLS::contains);
        if (scriptWrites && "NONE".equals(task.getApproval())) {
            throw new IllegalStateException(
                    prefix + "脚本里有写工具，approval 不能是 NONE（必须驱动审批）");
        }
        if (task.getExpect().getApprovalStatus() != null
                && "NONE".equals(task.getApproval())) {
            throw new IllegalStateException(
                    prefix + "断言了审批状态，approval 就不能是 NONE");
        }
    }

    /**
     * 追问要素的自洽性：要素必须有名字、有同义表达，且只在 ASK 任务上出现。
     */
    private void requireAskElements(String prefix, MusicEvalTask task) {
        List<MusicEvalTask.AskElement> elements = task.getExpect().getAskElements();

        if (elements.isEmpty()) {
            return;
        }

        if (task.getExpect().getClarification()
                != MusicEvalTask.Expectation.ClarificationKind.ASK) {
            throw new IllegalStateException(
                    prefix + "只有 clarification=ASK 的任务才声明 askElements");
        }

        for (int i = 0; i < elements.size(); i++) {
            MusicEvalTask.AskElement element = elements.get(i);
            if (element == null) {
                throw new IllegalStateException(prefix + "askElements[" + i + "] 不能为 null");
            }
            if (isBlank(element.getName())) {
                throw new IllegalStateException(
                        prefix + "askElements[" + i + "].name 不能为空（报告要说明缺哪一项）");
            }
            if (element.getAnyOf().isEmpty()) {
                throw new IllegalStateException(
                        prefix + "askElements[" + i + "].anyOf 不能为空"
                                + "（空要素等于永远不满足，任务必然失败）");
            }
        }
    }

    /**
     * 替代路径的自洽性：只能替代**确实要求过**的工具，且必须写明证据。
     *
     * <p>三条防呆，都是为了让"替代"不会悄悄变成"取消约束"：
     * 1. {@code insteadOf} 必须在 requiredTools 里——否则这条声明没人消费；
     * 2. 替代工具必须是真实工具，且不能被 forbid.tools 禁掉
     *    （"既要它当证据，又不许调用"是自相矛盾的用例）；
     * 3. 证据要求不能为空——空的替代等于无条件通过。
     */
    private void requireToolFallbacks(String prefix, MusicEvalTask task) {
        for (MusicEvalTask.ToolFallback fallback : task.getExpect().getToolFallbacks()) {
            if (fallback == null) {
                throw new IllegalStateException(prefix + "toolFallbacks 不能包含 null");
            }
            if (!task.getExpect().getRequiredTools().contains(fallback.getInsteadOf())) {
                throw new IllegalStateException(
                        prefix + "toolFallbacks.insteadOf 不在 requiredTools 里："
                                + fallback.getInsteadOf());
            }
            requireKnownTool(prefix + "toolFallbacks.insteadOf", fallback.getInsteadOf());

            if (fallback.getTools().isEmpty()) {
                throw new IllegalStateException(
                        prefix + "toolFallbacks[" + fallback.getInsteadOf() + "].tools 不能为空");
            }
            for (String tool : fallback.getTools()) {
                requireKnownTool(prefix + "toolFallbacks.tools", tool);
                if (task.getForbid().getTools().contains(tool)) {
                    throw new IllegalStateException(
                            prefix + "替代工具 " + tool + " 同时被 forbid.tools 禁止");
                }
            }
            if (fallback.getResponseMustContain().isEmpty()) {
                throw new IllegalStateException(
                        prefix + "toolFallbacks[" + fallback.getInsteadOf()
                                + "].responseMustContain 不能为空（空证据等于无条件通过）");
            }
        }
    }

    private void requireKnownTool(String where, String tool) {
        if (isBlank(tool)) {
            throw new IllegalStateException(where + " 不能包含空工具名");
        }
        if (!KNOWN_TOOLS.contains(tool)) {
            throw new IllegalStateException(
                    where + " 使用了当前不存在的工具：" + tool
                            + "（可用：" + new java.util.TreeSet<>(KNOWN_TOOLS) + "）");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
