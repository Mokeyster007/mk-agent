package com.example.mkagent.agent;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 音乐 Agent 必须跟着**全局**收尾开关走（mkagent.agent.*）。
 *
 * 背景：M2e 只绑定了 mkagent.agent.music.*（步数/工具次数/超时/sse-buffer），
 * 两个"预算耗尽后怎么收尾"的开关没有绑——于是运维把
 * mkagent.agent.force-final-answer 改成 false 时，只有 Manus 变，
 * 音乐入口照旧多花一次收尾调用。因为代码默认值恰好也是 true / 2，
 * 这个缺口在默认配置下完全看不出来，只能靠改配置的测试暴露。
 */
@HermeticSpringBootTest(properties = {
        "mkagent.agent.force-final-answer=false",
        "mkagent.agent.budget-warning-steps=0"
})
class MusicAgentGlobalSwitchBindingTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    @Qualifier("musicUserAgent")
    private MusicAgent musicUserAgent;

    @Autowired
    @Qualifier("musicAdminAgent")
    private MusicAgent musicAdminAgent;

    @Test
    @DisplayName("两个音乐 Agent 都读取全局开关，而不是只认 music.*")
    void bothMusicAgentsInheritGlobalSwitches() {
        for (MusicAgent agent : new MusicAgent[]{musicUserAgent, musicAdminAgent}) {
            assertFalse(agent.isForceFinalAnswer(),
                    agent.getName() + " 未继承 mkagent.agent.force-final-answer=false");
            assertEquals(0, agent.getBudgetWarningSteps(),
                    agent.getName() + " 未继承 mkagent.agent.budget-warning-steps=0");
        }
    }

    @Test
    @DisplayName("music.* 仍然独立：步数/工具次数/超时不受全局开关影响")
    void musicSpecificBudgetIsUnaffected() {
        for (MusicAgent agent : new MusicAgent[]{musicUserAgent, musicAdminAgent}) {
            assertEquals(8, agent.getMaxSteps(), agent.getName());
            assertEquals(12, agent.getMaxToolCalls(), agent.getName());
        }
    }
}
