package com.example.mkagent.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 计划绑定是 ThreadLocal：必须能嵌套、必须能恢复、异常路径也不能残留。
 *
 * 线程来自线程池，残留会让下一个任务认领到上一个任务的调用身份——
 * 那是"把副作用记到别人账上"级别的问题，所以这里单独测。
 */
class ToolCallPlanHolderTest {

    private static final Set<String> WRITE = Set.of("create_playlist");

    @AfterEach
    void tearDown() {
        ToolCallPlanHolder.clear();
    }

    @Test
    @DisplayName("绑定后可见，restore 后恢复旧值")
    void bindAndRestore() {
        ToolCallPlan outer = plan("outer");

        ToolCallPlan previous = ToolCallPlanHolder.bind(outer);
        assertNull(previous, "首次绑定前没有旧值");
        assertSame(outer, ToolCallPlanHolder.current());

        ToolCallPlan inner = plan("inner");
        ToolCallPlan previousInner = ToolCallPlanHolder.bind(inner);
        assertSame(outer, previousInner, "嵌套绑定必须返回外层计划");

        ToolCallPlanHolder.restore(previousInner);
        assertSame(outer, ToolCallPlanHolder.current(), "恢复后必须回到外层");

        ToolCallPlanHolder.restore(null);
        assertNull(ToolCallPlanHolder.current(), "最外层恢复后必须清空");
    }

    @Test
    @DisplayName("异常路径：finally 里 restore 之后不残留")
    void restoreInFinallyLeavesNothing() {
        ToolCallPlan plan = plan("call-1");
        ToolCallPlan previous = ToolCallPlanHolder.bind(plan);

        try {
            assertThrows(ToolCallPlan.ToolCallPlanException.class,
                    () -> plan.claim("create_playlist", "{\"name\":\"不一样\"}"));
        } finally {
            ToolCallPlanHolder.restore(previous);
        }

        assertNull(ToolCallPlanHolder.current());
    }

    private ToolCallPlan plan(String callId) {
        return ToolCallPlan.build(
                List.of(new AssistantMessage.ToolCall(
                        callId, "function", "create_playlist", "{\"name\":\"a\"}")),
                WRITE);
    }
}
