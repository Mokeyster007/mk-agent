package com.example.mkagent.eval.music;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条音乐任务执行完成后的**客观观测**。
 *
 * <p>数据全部来自数据库（{@code agent_run} / {@code agent_approval} /
 * {@code music_playlist} / {@code music_playlist_song}），不读取模型自述。
 * 原因很直接：判断"有没有真的写进去"只能看库，不能看模型说"已经完成"。
 *
 * <p>为什么不用 {@code AgentRunContext}：
 * 审批恢复会由 {@code CheckpointRestorer} 新建一个 ctx，原 ctx 在恢复后不可达；
 * 而 {@code AgentRunRecorder} 会把同一个 ctx 的 step / toolTrace / tokens /
 * finalAnswer 落到 {@code agent_run}，所以库里的数据是等价的，且跨暂停-恢复完整。
 */
public class MusicEvalObservation {

    private String runId = "";

    private String approvalId = "";

    private String runState = "";

    private String errorCode = "";

    private String approvalStatus = "";

    private String approvalResumeState = "";

    private int steps;

    private int toolCalls;

    private List<String> toolTrace = new ArrayList<>();

    private String finalAnswer = "";

    private String model = "";

    /**
     * 总 Token；null 表示本次运行没有可用的用量数据（不能填 0 冒充已测）。
     */
    private Long tokens;

    private long costMillis;

    private long playlistsBefore;

    private long playlistsAfter;

    private List<Long> createdPlaylistIds = new ArrayList<>();

    /**
     * 任务结束后，该用户名下所有歌单的 名称 → 歌曲 ID 列表。
     */
    private Map<String, List<Long>> playlistSongs = new LinkedHashMap<>();

    /**
     * 审批决定之前发生的业务写入数（必须为 0）。
     */
    private int pauseWrites;

    /**
     * 整个任务期间发生的业务写入数：
     * 新建歌单行 + 新增歌单歌曲关联行。查询类任务必须为 0。
     */
    private int writeCount;

    /**
     * 其他用户的歌单是否发生变化（越权写入）。
     */
    private boolean otherUserChanged;

    /**
     * 本次运行是否进入过 WAITING_APPROVAL。
     */
    private boolean paused;

    /**
     * 是否出现了任务脚本未预期的暂停（例如审批脚本是 NONE 却暂停了）。
     */
    private boolean unexpectedPause;

    /**
     * 本次运行使用的能力模式：
     * {@code READ_ONLY}（生产默认装配）或 {@code WRITE_APPROVAL}（隔离评测启用写能力）。
     *
     * <p>两种模式的结果不能混在一起算成功率——它们测的不是同一套能力。
     */
    private String capabilityMode;

    /**
     * 本次运行使用的系统提示词版本标识（见 MusicAgent 里的常量）。
     */
    private String promptVersion;

    /**
     * 本次运行的占位符取值（name / fixture 等），用于把任务定义还原成实际名字。
     */
    private Map<String, String> vars = Map.of();

    /**
     * 工具响应的**脱敏**证据摘要（每次调用一条）。
     *
     * <p>只留三样东西：工具名、响应里出现的字段名、以及任务关心的标记是否命中。
     * 不保存响应原文——评测报告不需要把曲库数据再抄一遍，
     * 而"答案有没有查询依据"这个判断在采集时就已经做完了。
     */
    private List<ToolEvidence> toolEvidence = new ArrayList<>();

    /**
     * 回答里出现、但在本次**所有工具响应中都找不到依据**的曲目标题。
     *
     * <p>只统计《》括起来的标题：这是中文语境下模型的固定写法，
     * 误伤面最小。空列表表示"没有发现无依据曲目"，不等于"证明全部有依据"——
     * 不用书名号写歌名的回答不会被这项覆盖，属于已知局限。
     */
    private List<String> ungroundedTitles = new ArrayList<>();

    /**
     * 一次工具调用的脱敏证据。
     *
     * @param tool         工具名
     * @param fields       响应 JSON 里出现的字段名（只到第一层/数组首元素）
     * @param matched      任务关心的标记中，该响应命中的那些
     * @param responseChars 响应字符数（用于判断是否被信封截断）
     */
    public record ToolEvidence(
            String tool,
            List<String> fields,
            List<String> matched,
            int responseChars
    ) {

        public ToolEvidence {
            fields = fields == null ? List.of() : List.copyOf(fields);
            matched = matched == null ? List.of() : List.copyOf(matched);
        }
    }

    public String getRunId() {
        return runId;
    }

    public MusicEvalObservation setRunId(String runId) {
        this.runId = runId == null ? "" : runId;
        return this;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public MusicEvalObservation setApprovalId(String approvalId) {
        this.approvalId = approvalId == null ? "" : approvalId;
        return this;
    }

    public String getRunState() {
        return runState;
    }

    public MusicEvalObservation setRunState(String runState) {
        this.runState = runState == null ? "" : runState;
        return this;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public MusicEvalObservation setErrorCode(String errorCode) {
        this.errorCode = errorCode == null ? "" : errorCode;
        return this;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public MusicEvalObservation setApprovalStatus(String approvalStatus) {
        this.approvalStatus = approvalStatus == null ? "" : approvalStatus;
        return this;
    }

    public String getApprovalResumeState() {
        return approvalResumeState;
    }

    public MusicEvalObservation setApprovalResumeState(String approvalResumeState) {
        this.approvalResumeState = approvalResumeState == null ? "" : approvalResumeState;
        return this;
    }

    public int getSteps() {
        return steps;
    }

    public MusicEvalObservation setSteps(int steps) {
        this.steps = steps;
        return this;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public MusicEvalObservation setToolCalls(int toolCalls) {
        this.toolCalls = toolCalls;
        return this;
    }

    public List<String> getToolTrace() {
        return List.copyOf(toolTrace);
    }

    public MusicEvalObservation setToolTrace(List<String> toolTrace) {
        this.toolTrace = toolTrace == null ? new ArrayList<>() : new ArrayList<>(toolTrace);
        return this;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public MusicEvalObservation setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer == null ? "" : finalAnswer;
        return this;
    }

    public String getModel() {
        return model;
    }

    public MusicEvalObservation setModel(String model) {
        this.model = model == null ? "" : model;
        return this;
    }

    public Long getTokens() {
        return tokens;
    }

    public MusicEvalObservation setTokens(Long tokens) {
        this.tokens = tokens;
        return this;
    }

    public long getCostMillis() {
        return costMillis;
    }

    public MusicEvalObservation setCostMillis(long costMillis) {
        this.costMillis = costMillis;
        return this;
    }

    public long getPlaylistsBefore() {
        return playlistsBefore;
    }

    public MusicEvalObservation setPlaylistsBefore(long playlistsBefore) {
        this.playlistsBefore = playlistsBefore;
        return this;
    }

    public long getPlaylistsAfter() {
        return playlistsAfter;
    }

    public MusicEvalObservation setPlaylistsAfter(long playlistsAfter) {
        this.playlistsAfter = playlistsAfter;
        return this;
    }

    public List<Long> getCreatedPlaylistIds() {
        return List.copyOf(createdPlaylistIds);
    }

    public MusicEvalObservation setCreatedPlaylistIds(List<Long> createdPlaylistIds) {
        this.createdPlaylistIds = createdPlaylistIds == null
                ? new ArrayList<>()
                : new ArrayList<>(createdPlaylistIds);
        return this;
    }

    public Map<String, List<Long>> getPlaylistSongs() {
        return Map.copyOf(playlistSongs);
    }

    public MusicEvalObservation setPlaylistSongs(Map<String, List<Long>> playlistSongs) {
        this.playlistSongs = playlistSongs == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(playlistSongs);
        return this;
    }

    public int getPauseWrites() {
        return pauseWrites;
    }

    public MusicEvalObservation setPauseWrites(int pauseWrites) {
        this.pauseWrites = pauseWrites;
        return this;
    }

    public int getWriteCount() {
        return writeCount;
    }

    public MusicEvalObservation setWriteCount(int writeCount) {
        this.writeCount = writeCount;
        return this;
    }

    public boolean isOtherUserChanged() {
        return otherUserChanged;
    }

    public MusicEvalObservation setOtherUserChanged(boolean otherUserChanged) {
        this.otherUserChanged = otherUserChanged;
        return this;
    }

    public boolean isPaused() {
        return paused;
    }

    public MusicEvalObservation setPaused(boolean paused) {
        this.paused = paused;
        return this;
    }

    public boolean isUnexpectedPause() {
        return unexpectedPause;
    }

    public MusicEvalObservation setUnexpectedPause(boolean unexpectedPause) {
        this.unexpectedPause = unexpectedPause;
        return this;
    }

    public String getCapabilityMode() {
        return capabilityMode;
    }

    public MusicEvalObservation setCapabilityMode(String capabilityMode) {
        this.capabilityMode = capabilityMode;
        return this;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public MusicEvalObservation setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
        return this;
    }

    public Map<String, String> getVars() {
        return vars == null ? Map.of() : Map.copyOf(vars);
    }

    public MusicEvalObservation setVars(Map<String, String> vars) {
        this.vars = vars;
        return this;
    }

    public List<ToolEvidence> getToolEvidence() {
        return List.copyOf(toolEvidence);
    }

    public MusicEvalObservation setToolEvidence(List<ToolEvidence> toolEvidence) {
        this.toolEvidence = toolEvidence == null
                ? new ArrayList<>()
                : new ArrayList<>(toolEvidence);
        return this;
    }

    public List<String> getUngroundedTitles() {
        return List.copyOf(ungroundedTitles);
    }

    public MusicEvalObservation setUngroundedTitles(List<String> ungroundedTitles) {
        this.ungroundedTitles = ungroundedTitles == null
                ? new ArrayList<>()
                : new ArrayList<>(ungroundedTitles);
        return this;
    }
}
