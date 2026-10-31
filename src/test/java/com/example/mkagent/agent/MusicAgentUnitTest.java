package com.example.mkagent.agent;

import com.example.mkagent.model.AgentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MusicAgent 的单元测试（不启动 Spring）。
 *
 * 覆盖三件事：
 * 1. 构造器只接受 MUSIC_USER / MUSIC_ADMIN——配错类型要在启动时就炸，
 *    而不是等管理员拿到歌单工具之后才发现；
 * 2. 预算校验与 MkManus 共用 AgentBudgetPolicy，越界回退默认值；
 * 3. 系统提示词里必须出现的约束（先查询、不可信数据、无写能力、terminate_task）。
 *
 * 提示词也要测：它是权限设计的一部分——"当前版本不提供写歌单能力"
 * 这句话一旦被删掉，模型就会开始编造"已为你创建歌单"。
 */
class MusicAgentUnitTest {

    @Test
    @DisplayName("构造器拒绝非音乐类型")
    void constructorRejectsNonMusicAgentType() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> newAgent(AgentType.MANUS, "p")
        );

        assertTrue(error.getMessage().contains("MUSIC_USER"),
                "错误信息应说明允许的类型，实际：" + error.getMessage());
    }

    @Test
    @DisplayName("MUSIC_USER / MUSIC_ADMIN 都能构造，getAgentType 原样返回")
    void agentTypeIsPreserved() {
        assertEquals(AgentType.MUSIC_USER,
                newAgent(AgentType.MUSIC_USER, "p").getAgentType());
        assertEquals(AgentType.MUSIC_ADMIN,
                newAgent(AgentType.MUSIC_ADMIN, "p").getAgentType());
    }

    @Test
    @DisplayName("默认预算与 MkManus 一致（8 / 12 / 2m / 30s）")
    void defaultsMatchMkManus() {
        MusicAgent agent = newAgent(AgentType.MUSIC_USER, "p");

        assertEquals(AgentBudgetPolicy.DEFAULT_MAX_STEPS, agent.getMaxSteps());
        assertEquals(AgentBudgetPolicy.DEFAULT_MAX_TOOL_CALLS, agent.getMaxToolCalls());
        assertEquals(AgentBudgetPolicy.DEFAULT_TIMEOUT, agent.getTimeout());
        assertEquals(BaseAgent.DEFAULT_SSE_BUFFER, agent.getSseBuffer());
    }

    @Test
    @DisplayName("预算越界回退默认值：0 / 超上限")
    void outOfRangeBudgetFallsBack() {
        MusicAgent agent = newAgent(AgentType.MUSIC_USER, "p");

        agent.applyBudget(0, 0, Duration.ZERO, Duration.ZERO);

        assertEquals(AgentBudgetPolicy.DEFAULT_MAX_STEPS, agent.getMaxSteps());
        assertEquals(AgentBudgetPolicy.DEFAULT_MAX_TOOL_CALLS, agent.getMaxToolCalls());
        assertEquals(AgentBudgetPolicy.DEFAULT_TIMEOUT, agent.getTimeout());
        assertEquals(BaseAgent.DEFAULT_SSE_BUFFER, agent.getSseBuffer(),
                "sse-buffer=0 应回退默认 30s（BaseAgent 校验）");

        agent.applyBudget(31, 61, Duration.ofMinutes(31), Duration.ofMinutes(10));

        assertEquals(AgentBudgetPolicy.DEFAULT_MAX_STEPS, agent.getMaxSteps());
        assertEquals(AgentBudgetPolicy.DEFAULT_MAX_TOOL_CALLS, agent.getMaxToolCalls());
        assertEquals(AgentBudgetPolicy.DEFAULT_TIMEOUT, agent.getTimeout());
        assertEquals(BaseAgent.DEFAULT_SSE_BUFFER, agent.getSseBuffer());
    }

    @Test
    @DisplayName("预算边界值被接受")
    void boundaryBudgetIsAccepted() {
        MusicAgent agent = newAgent(AgentType.MUSIC_ADMIN, "p");

        agent.applyBudget(30, 60, Duration.ofMinutes(30), Duration.ofMinutes(5));

        assertEquals(30, agent.getMaxSteps());
        assertEquals(60, agent.getMaxToolCalls());
        assertEquals(Duration.ofMinutes(30), agent.getTimeout());
        assertEquals(Duration.ofMinutes(5), agent.getSseBuffer());
    }

    @Test
    @DisplayName("用户提示词：先查询、不编造、不可信数据、无写能力、terminate_task")
    void userPromptCarriesRequiredConstraints() {
        String prompt = MusicAgent.USER_SYSTEM_PROMPT;

        assertTrue(prompt.contains("先查询后回答"), "缺少「先查询后回答」");
        assertTrue(prompt.contains("不得") && prompt.contains("编造"),
                "缺少「不得编造」约束");
        assertTrue(prompt.contains("不可信数据"), "缺少不可信数据声明");
        assertTrue(prompt.contains("不提供"), "缺少「当前版本不提供写能力」声明");
        assertTrue(prompt.contains("terminate_task"), "缺少终止要求");
        assertTrue(prompt.contains("final_answer"), "缺少 final_answer 要求");

        // 不能把管理员工具写进用户提示词：模型会去调一个不存在的工具。
        assertFalse(prompt.contains("get_play_stats"),
                "用户提示词不得提及管理员工具");
    }

    @Test
    @DisplayName("管理员提示词：只有聚合、不涉及用户级数据、不可信数据、terminate_task")
    void adminPromptCarriesRequiredConstraints() {
        String prompt = MusicAgent.ADMIN_SYSTEM_PROMPT;

        assertTrue(prompt.contains("聚合"), "缺少聚合口径声明");
        assertTrue(prompt.contains("不涉及任何用户级数据"),
                "缺少「不涉及用户级数据」声明");
        assertTrue(prompt.contains("不可信数据"), "缺少不可信数据声明");
        assertTrue(prompt.contains("terminate_task"), "缺少终止要求");
        assertTrue(prompt.contains("get_play_stats"), "应给出统计工具名");
    }

    /**
     * 用 null 依赖构造：本测试只关心预算与提示词，不触发任何执行。
     */
    private MusicAgent newAgent(AgentType agentType, String systemPrompt) {
        return new MusicAgent(
                null,
                new ToolCallback[0],
                Runnable::run,
                null,
                agentType,
                "TestMusicAgent",
                systemPrompt
        );
    }
}
