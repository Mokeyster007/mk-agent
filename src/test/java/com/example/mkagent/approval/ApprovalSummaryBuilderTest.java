package com.example.mkagent.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批摘要里的"目标歌单"。
 *
 * <p>这组测试锁住一个真实缺陷：{@code add_songs_to_playlist} 的参数里只有
 * {@code playlistId}，摘要构建原先只读 {@code name}，导致页面上的审批卡
 * 显示"目标歌单：未指定歌单名"——用户在最关键的一步看不到自己要改哪个歌单。
 *
 * <p>同时锁住"失败不致命"：查名字只是辅助信息，查不到/查出错都不能让
 * 审批流程本身失败，也不能跨用户泄露别人的歌单名。
 *
 * <p>注意 userId 是**显式传参**而不是从 ThreadLocal 取：摘要在"模型刚返回
 * 工具调用、还没执行任何工具"时构造，那一刻上下文并未绑定到线程上。
 * 这里只测摘要构建本身，真实链路（含 SQL）由
 * {@code MusicWriteLoopIntegrationTest} 覆盖。
 */
@DisplayName("审批摘要：目标歌单名")
class ApprovalSummaryBuilderTest {

    private static final String USER = "music-user-1";

    @Test
    @DisplayName("加歌类工具按 playlistId 查出歌单名，摘要与结构化字段都带上")
    void resolvesPlaylistNameForAddTool() {
        AtomicReference<String> askedUser = new AtomicReference<>();
        ApprovalSummaryBuilder builder = builder(
                (playlistId, userId) -> {
                    askedUser.set(userId);
                    return playlistId == 33L ? "联调验证-202611171817" : null;
                },
                List.of("深夜电台"));

        ApprovalSummary summary = builder.build(
                List.of(addCall("{\"playlistId\":33,\"songIds\":[1002]}")), USER);

        assertThat(summary.playlistName()).isEqualTo("联调验证-202611171817");
        assertThat(summary.text()).contains("向歌单添加歌曲");
        assertThat(summary.text()).contains("联调验证-202611171817");
        assertThat(summary.text()).contains("深夜电台");
        // 归属校验靠 userId，不能拿空身份去查
        assertThat(askedUser.get()).isEqualTo(USER);
    }

    @Test
    @DisplayName("查不到歌单名时保持 null，不编造、也不阻断摘要")
    void keepsNullWhenPlaylistUnknown() {
        ApprovalSummaryBuilder builder = builder(
                (playlistId, userId) -> null,
                List.of("深夜电台"));

        ApprovalSummary summary = builder.build(
                List.of(addCall("{\"playlistId\":999,\"songIds\":[1002]}")), USER);

        assertThat(summary.playlistName()).isNull();
        assertThat(summary.text()).doesNotContain("null");
        assertThat(summary.songCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("查询抛异常时降级为 null，审批摘要照常生成")
    void swallowsLookupFailure() {
        ApprovalSummaryBuilder builder = builder(
                (playlistId, userId) -> {
                    throw new IllegalStateException("db down");
                },
                List.of("深夜电台"));

        ApprovalSummary summary = builder.build(
                List.of(addCall("{\"playlistId\":33,\"songIds\":[1002]}")), USER);

        assertThat(summary.playlistName()).isNull();
        assertThat(summary.text()).contains("向歌单添加歌曲");
    }

    @Test
    @DisplayName("没有 userId 时不查库（避免用匿名身份读到别人的歌单名）")
    void doesNotLookupWithoutUser() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        ApprovalSummaryBuilder builder = builder(
                (playlistId, userId) -> {
                    called.set(true);
                    return "别人的歌单";
                },
                List.of("深夜电台"));

        ApprovalSummary summary = builder.build(
                List.of(addCall("{\"playlistId\":33,\"songIds\":[1002]}")), null);

        assertThat(called.get()).isFalse();
        assertThat(summary.playlistName()).isNull();
    }

    @Test
    @DisplayName("单参重载不带用户：不查库，行为等同 userId=null")
    void singleArgOverloadSkipsLookup() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        ApprovalSummaryBuilder builder = builder(
                (playlistId, userId) -> {
                    called.set(true);
                    return "别人的歌单";
                },
                List.of("深夜电台"));

        ApprovalSummary summary = builder.build(
                List.of(addCall("{\"playlistId\":33,\"songIds\":[1002]}")));

        assertThat(called.get()).isFalse();
        assertThat(summary.playlistName()).isNull();
    }

    @Test
    @DisplayName("创建歌单仍用参数里的 name，不去查库")
    void createToolUsesNameFromArgs() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        ApprovalSummaryBuilder builder = builder(
                (playlistId, userId) -> {
                    called.set(true);
                    return "不该被调用";
                },
                List.of("城市霓虹"));

        ApprovalSummary summary = builder.build(
                List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "create_playlist",
                        "{\"name\":\"联调验证-新建\",\"songIds\":[1001]}")),
                USER);

        assertThat(summary.playlistName()).isEqualTo("联调验证-新建");
        assertThat(called.get()).isFalse();
    }

    private static AssistantMessage.ToolCall addCall(String args) {
        return new AssistantMessage.ToolCall(
                "call-1", "function", "add_songs_to_playlist", args);
    }

    private static ApprovalSummaryBuilder builder(
            PlaylistNameResolver resolver,
            List<String> titles
    ) {
        SongTitleLookup effective = new SongTitleLookup() {
            @Override
            public List<String> titles(List<Long> songIds) {
                return titles;
            }

            @Override
            public String playlistName(long playlistId, String userId) {
                return resolver.resolve(playlistId, userId);
            }
        };
        return new ApprovalSummaryBuilder(effective, new ApprovalProperties());
    }

    /**
     * 测试用的歌单名解析器。
     *
     * <p>不能直接写 lambda 给 {@link SongTitleLookup}：它的函数式方法是
     * {@code titles(List<Long>)}，加了这个接口才能把"查名字"单独注入。
     */
    @FunctionalInterface
    private interface PlaylistNameResolver {
        String resolve(long playlistId, String userId);
    }
}
