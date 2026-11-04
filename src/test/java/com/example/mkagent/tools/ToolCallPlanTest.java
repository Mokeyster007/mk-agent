package com.example.mkagent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用身份计划（纯单元，不启动 Spring）。
 *
 * 计划要解决的是"回调拿不到 toolCallId"这件事：
 * 执行前把整轮 ToolCall 登记下来，回调执行时按工具名 + 声明顺序认领，
 * 认领时再比对一次参数。任何一处对不上都必须失败关闭。
 */
class ToolCallPlanTest {

    private static final Set<String> WRITE = Set.of("create_playlist", "add_songs_to_playlist");

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    @Test
    @DisplayName("按声明顺序认领，id 一一对应")
    void claimsInDeclarationOrder() {
        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("c1", "create_playlist", "{\"name\":\"a\",\"songIds\":[1]}"),
                call("c2", "add_songs_to_playlist", "{\"playlistId\":9,\"songIds\":[2]}")
        ), WRITE);

        assertEquals("c1", plan.claim("create_playlist",
                "{\"name\":\"a\",\"songIds\":[1]}").toolCallId());
        assertEquals("c2", plan.claim("add_songs_to_playlist",
                "{\"playlistId\":9,\"songIds\":[2]}").toolCallId());
    }

    @Test
    @DisplayName("同名同参、不同 id：两次调用各拿各的身份，不会被合并")
    void sameNameSameArgsDifferentIdsAreNotMerged() {
        String args = "{\"name\":\"同名\",\"songIds\":[1]}";

        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("c1", "create_playlist", args),
                call("c2", "create_playlist", args)
        ), WRITE);

        /*
         * 注意这条用例同时钉住了已知残余风险：两条调用在工具名与参数上完全一样，
         * 认领只能按队列顺序发号。若框架某天在同一线程内乱序分发，
         * c2 的回调会认领到 c1——本类检测不到这种交换（见类注释）。
         * 这里验证的是"不会被合并成一次"，不是"能识别任意乱序"。
         */
        assertEquals("c1", plan.claim("create_playlist", args).toolCallId());
        assertEquals("c2", plan.claim("create_playlist", args).toolCallId());
    }

    @Test
    @DisplayName("同名不同参：按顺序认领，参数不符就失败关闭")
    void differentArgumentsStillMatchByOrder() {
        String first = "{\"name\":\"a\",\"songIds\":[1]}";
        String second = "{\"name\":\"b\",\"songIds\":[2]}";

        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("c1", "create_playlist", first),
                call("c2", "create_playlist", second)
        ), WRITE);

        assertEquals("c1", plan.claim("create_playlist", first).toolCallId());
        assertEquals("c2", plan.claim("create_playlist", second).toolCallId());
    }

    @Test
    @DisplayName("参数与计划不符（顺序被打乱）→ 抛异常，绝不错配身份")
    void argumentMismatchFailsClosed() {
        String first = "{\"name\":\"a\",\"songIds\":[1]}";
        String second = "{\"name\":\"b\",\"songIds\":[2]}";

        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("c1", "create_playlist", first),
                call("c2", "create_playlist", second)
        ), WRITE);

        ToolCallPlan.ToolCallPlanException error = assertThrows(
                ToolCallPlan.ToolCallPlanException.class,
                () -> plan.claim("create_playlist", second));

        assertEquals(ToolCallPlan.REASON_MISMATCH, error.getMessage());
    }

    @Test
    @DisplayName("缺少 id → 整轮标记拒绝，任何写工具都认领不到")
    void missingIdRejectsWholeRound() {
        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("", "create_playlist", "{\"name\":\"a\"}")
        ), WRITE);

        assertEquals(ToolCallPlan.REASON_ID_MISSING, plan.rejectedReason());

        ToolCallPlan.ToolCallPlanException error = assertThrows(
                ToolCallPlan.ToolCallPlanException.class,
                () -> plan.claim("create_playlist", "{\"name\":\"a\"}"));
        assertEquals(ToolCallPlan.REASON_ID_MISSING, error.getMessage());
    }

    @Test
    @DisplayName("空白 id 与 null id 同样按缺失处理")
    void blankIdTreatedAsMissing() {
        assertEquals(ToolCallPlan.REASON_ID_MISSING,
                ToolCallPlan.build(List.of(
                        call("   ", "create_playlist", "{}")), WRITE).rejectedReason());

        assertEquals(ToolCallPlan.REASON_ID_MISSING,
                ToolCallPlan.build(List.of(
                        call(null, "create_playlist", "{}")), WRITE).rejectedReason());
    }

    @Test
    @DisplayName("同轮重复 id → 整轮拒绝（无法判断哪条回调对应哪次调用）")
    void duplicateIdRejectsWholeRound() {
        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("dup", "create_playlist", "{\"name\":\"a\"}"),
                call("dup", "add_songs_to_playlist", "{\"playlistId\":1}")
        ), WRITE);

        assertEquals(ToolCallPlan.REASON_ID_DUPLICATED, plan.rejectedReason());
    }

    @Test
    @DisplayName("只读工具不参与身份校验，也不占队列")
    void readOnlyToolsAreIgnored() {
        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("", "search_songs", "{\"q\":\"x\"}"),
                call("c1", "create_playlist", "{\"name\":\"a\"}")
        ), WRITE);

        assertNull(plan.rejectedReason(), "只读工具缺 id 不影响写工具的身份");
        assertEquals("c1", plan.claim("create_playlist", "{\"name\":\"a\"}").toolCallId());
    }

    @Test
    @DisplayName("队列空了还来认领 → 失败关闭（重复回调不会被当成新调用）")
    void exhaustedQueueFailsClosed() {
        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("c1", "create_playlist", "{\"name\":\"a\"}")
        ), WRITE);

        plan.claim("create_playlist", "{\"name\":\"a\"}");

        ToolCallPlan.ToolCallPlanException error = assertThrows(
                ToolCallPlan.ToolCallPlanException.class,
                () -> plan.claim("create_playlist", "{\"name\":\"a\"}"));
        assertEquals(ToolCallPlan.REASON_NOT_BOUND, error.getMessage());
    }

    @Test
    @DisplayName("计划里没有这个工具 → 失败关闭")
    void unknownToolFailsClosed() {
        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("c1", "create_playlist", "{\"name\":\"a\"}")
        ), WRITE);

        ToolCallPlan.ToolCallPlanException error = assertThrows(
                ToolCallPlan.ToolCallPlanException.class,
                () -> plan.claim("add_songs_to_playlist", "{\"playlistId\":1}"));
        assertEquals(ToolCallPlan.REASON_NOT_BOUND, error.getMessage());
    }

    @Test
    @DisplayName("参数格式不同但语义相同 → 仍能认领")
    void formattingDifferencesStillMatch() {
        ToolCallPlan plan = ToolCallPlan.build(List.of(
                call("c1", "create_playlist", "{\"name\":\"a\",\"songIds\":[1,2]}")
        ), WRITE);

        assertEquals("c1", plan.claim(
                "create_playlist",
                "{ \"songIds\" : [ 1 , 2 ] , \"name\" : \"a\" }").toolCallId());
    }

    @Test
    @DisplayName("空轮次：没有计划，认领即失败关闭")
    void emptyPlanFailsClosed() {
        ToolCallPlan plan = ToolCallPlan.build(List.of(), WRITE);

        assertThrows(ToolCallPlan.ToolCallPlanException.class,
                () -> plan.claim("create_playlist", "{}"));
    }

    @Test
    @DisplayName("换线程认领：直接拒绝，且不消耗队列（原线程仍可正常认领）")
    void crossThreadClaimFailsClosed() throws Exception {
        String args = "{\"name\":\"a\",\"songIds\":[1]}";
        ToolCallPlan plan = ToolCallPlan.build(
                List.of(call("c1", "create_playlist", args)), WRITE);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                plan.claim("create_playlist", args);
            } catch (Throwable t) {
                error.set(t);
            }
        }, "plan-other-thread");
        other.start();
        other.join(5_000);

        assertTrue(error.get() instanceof ToolCallPlan.ToolCallPlanException,
                "换线程必须失败关闭，实际：" + error.get());
        assertEquals(ToolCallPlan.REASON_CROSS_THREAD, error.get().getMessage());

        // 跨线程的拒绝不能把这条计划吃掉：原线程仍然认领到同一个 id
        assertEquals("c1", plan.claim("create_playlist", args).toolCallId());
    }
}
