package com.example.mkagent.context;

import com.example.mkagent.model.AgentRunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentRunContextHolder：每任务上下文的线程绑定（纯单元测试）。
 *
 * 要证明的三件事：
 * 1. bind 返回旧值、restore 能精确回到旧值（**嵌套**场景不能把外层弄丢）；
 * 2. 取不到上下文时**失败关闭**（抛异常），绝不回落匿名用户；
 * 3. 清除后不留残余（线程池复用不会串任务）。
 */
class AgentRunContextHolderTest {

    @AfterEach
    void tearDown() {
        AgentRunContextHolder.clear();
    }

    @Test
    @DisplayName("bind 返回旧值，restore 恢复到旧值")
    void bindReturnsPreviousAndRestoreRecovers() {
        AgentRunContext outer = new AgentRunContext();
        outer.setUserId("user-outer");

        AgentRunContext previous = AgentRunContextHolder.bind(outer);

        assertNull(previous, "首次绑定前没有旧值");
        assertSame(outer, AgentRunContextHolder.current());

        AgentRunContext inner = new AgentRunContext();
        inner.setUserId("user-inner");

        AgentRunContext outerRestored = AgentRunContextHolder.bind(inner);
        assertSame(outer, outerRestored);
        assertSame(inner, AgentRunContextHolder.current());

        AgentRunContextHolder.restore(outerRestored);
        assertSame(outer, AgentRunContextHolder.current(),
                "嵌套结束后必须回到外层，而不是被清除");

        AgentRunContextHolder.restore(previous);
        assertNull(AgentRunContextHolder.current(),
                "旧值为 null 时应清除，避免线程池残留");
    }

    @Test
    @DisplayName("requireUserId 取当前任务的用户")
    void requireUserIdReadsBoundContext() {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId("music-user-1");

        AgentRunContextHolder.bind(ctx);

        assertEquals("music-user-1", AgentRunContextHolder.requireUserId());
    }

    @Test
    @DisplayName("没有上下文：抛异常（失败关闭），不回落匿名")
    void missingContextFailsClosed() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                AgentRunContextHolder::requireUserId
        );

        assertEquals(AgentRunContextHolder.NO_CONTEXT_MESSAGE, error.getMessage());
        assertTrue(error.getMessage().contains("拒绝"),
                "文案要让模型明白这是被拒绝，而不是'没有数据'");
    }

    @Test
    @DisplayName("上下文里没有有效用户：同样抛异常")
    void blankUserFailsClosed() {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId("   ");

        AgentRunContextHolder.bind(ctx);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                AgentRunContextHolder::requireUserId
        );
        assertEquals(AgentRunContextHolder.NO_USER_MESSAGE, error.getMessage());
    }

    @Test
    @DisplayName("clear 之后 current 为 null")
    void clearRemovesBinding() {
        AgentRunContextHolder.bind(new AgentRunContext());
        AgentRunContextHolder.clear();

        assertNull(AgentRunContextHolder.current());
    }
}
