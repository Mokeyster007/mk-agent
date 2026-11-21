package com.example.mkagent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词与工具能力的一致性约束。
 *
 * <p>背景（真实评测里踩到的坑）：只读提示词写着"当前版本不提供创建歌单"，
 * 而评测执行器又给模型注入了写工具。模型于是自相矛盾——
 * 一边说"不支持创建"，一边真的去创建；c01 甚至因此被判成"合理澄清"。
 *
 * <p>这份测试守住一条规则：**给不给写工具，和提示词怎么写，必须同时成立**。
 * 它只检查文本层面的承诺，不启动 Spring；工具白名单的精确性由
 * {@code MusicToolWhitelistIntegrationTest} 负责。
 */
class MusicPromptCapabilityTest {

    @Test
    @DisplayName("只读提示词必须明确否认写能力（生产默认）")
    void readonlyPromptDeniesWrite() {
        String prompt = MusicAgent.USER_SYSTEM_PROMPT;

        assertTrue(prompt.contains("**不提供**"), "只读提示词必须声明不提供写能力");
        assertTrue(prompt.contains("创建歌单"), "必须点名「创建歌单」这类请求");
        assertTrue(prompt.contains("不得声称已经完成"), "必须禁止虚报完成");
        assertTrue(prompt.contains("terminate_task"), "收尾契约里要提到 terminate_task");
    }

    @Test
    @DisplayName("写能力提示词必须承认写能力，并绑定审批")
    void writePromptGrantsWriteWithApproval() {
        String prompt = MusicAgent.USER_WRITE_SYSTEM_PROMPT;

        assertFalse(prompt.contains("**不提供**"),
                "写能力已启用时不能再声称不提供写能力");
        assertTrue(prompt.contains("必须经用户批准"), "写操作必须绑定审批");
        assertTrue(prompt.contains("用户确认之前"), "审批前不得声称完成");
        assertTrue(prompt.contains("用户拒绝时"), "拒绝后必须零写入并如实说明");
        assertTrue(prompt.contains("不得修改他人的歌单"), "写能力不得越权到他人数据");
        assertTrue(prompt.contains("terminate_task"), "收尾契约里要提到 terminate_task");
    }

    @Test
    @DisplayName("收尾契约：不把 terminate_task 写成硬性要求，且两条路径并列")
    void endingContractAcceptsGroundedText() {
        for (String prompt : new String[]{
                MusicAgent.USER_SYSTEM_PROMPT,
                MusicAgent.USER_WRITE_SYSTEM_PROMPT,
                MusicAgent.ADMIN_SYSTEM_PROMPT}) {
            assertTrue(prompt.contains(MusicAgent.ENDING_CONTRACT),
                    "三段提示词都必须包含同一份收尾契约");
            assertFalse(prompt.contains("必须调用 terminate_task"),
                    "不能把 terminate_task 写成硬性要求：执行器本来也接受纯文本收尾");
            assertTrue(prompt.contains("推荐调用 terminate_task"),
                    "terminate_task 仍应是推荐收尾方式");
            assertTrue(prompt.contains("直接输出这段文本即可结束任务"),
                    "必须明确允许有依据的最终文本收尾");
            assertTrue(prompt.contains("不要写\"任务完成\"这类空话"),
                    "两种收尾都必须禁止空话");
        }
    }

    @Test
    @DisplayName("两份用户提示词都保留反编造与不可信数据约束")
    void bothUserPromptsKeepSafetyRules() {
        for (String prompt : new String[]{
                MusicAgent.USER_SYSTEM_PROMPT,
                MusicAgent.USER_WRITE_SYSTEM_PROMPT}) {
            assertTrue(prompt.contains("不得凭印象"), "必须禁止编造");
            assertTrue(prompt.contains("不可信数据"), "必须声明工具结果不可信");
            assertTrue(prompt.contains("只依据工具返回的数据"), "必须以工具结果为准");
        }
    }

    @Test
    @DisplayName("管理员提示词不因用户侧写能力而扩权")
    void adminPromptStaysReadOnly() {
        String prompt = MusicAgent.ADMIN_SYSTEM_PROMPT;

        assertTrue(prompt.contains("没有"), "管理员必须被告知没有歌单工具");
        assertTrue(prompt.contains("歌单"), "必须点名歌单类请求");
        assertFalse(prompt.contains("可以创建歌单"), "管理员不得获得写能力");
        assertFalse(prompt.contains("向自己的歌单添加歌曲"), "管理员不得获得写能力");
    }

    @Test
    @DisplayName("三个提示词版本标识互不相同且非空")
    void promptVersionsAreDistinct() {
        assertNotEquals(MusicAgent.USER_SYSTEM_PROMPT_VERSION,
                MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION);
        assertNotEquals(MusicAgent.USER_SYSTEM_PROMPT_VERSION,
                MusicAgent.ADMIN_SYSTEM_PROMPT_VERSION);
        assertEquals("music-user-readonly/v5", MusicAgent.USER_SYSTEM_PROMPT_VERSION);
        assertEquals("music-user-write-approval/v7", MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION);
        assertEquals("music-admin-readonly/v3", MusicAgent.ADMIN_SYSTEM_PROMPT_VERSION);
    }

    @Test
    @DisplayName("边界契约：检索不到时不得用常识补曲目，三段提示词共用")
    void groundingContractForbidsCommonSenseFilling() {
        for (String prompt : new String[]{
                MusicAgent.USER_SYSTEM_PROMPT,
                MusicAgent.USER_WRITE_SYSTEM_PROMPT,
                MusicAgent.ADMIN_SYSTEM_PROMPT}) {
            assertTrue(prompt.contains(MusicAgent.GROUNDING_CONTRACT),
                    "三段提示词都必须包含同一份边界契约");
            assertTrue(prompt.contains("不得用你自己的知识"), "必须禁止用模型常识补曲目");
            assertTrue(prompt.contains("免责声明不能替代真实检索"),
                    "必须堵住“加一句仅供参考就端出曲库外歌曲”这条路");
            assertTrue(prompt.contains("不提供按场景、心情、氛围推荐歌曲的能力"),
                    "必须写明当前版本没有推荐能力");
        }
    }

    @Test
    @DisplayName("边界契约不按歌手名拉黑：规则必须与具体作品无关")
    void groundingContractIsArtistAgnostic() {
        String prompt = MusicAgent.USER_SYSTEM_PROMPT;

        /*
         * 首版基线里模型编的是 GEM / 五月天 / Dua Lipa。
         * 如果把它们写进提示词，规则就退化成"挡这一次见过的名字"，
         * 换个歌手立刻失效——所以这里断言提示词里不出现任何具体歌手名。
         */
        for (String artist : new String[]{"GEM", "邓紫棋", "五月天", "告五人", "Dua Lipa"}) {
            assertFalse(prompt.contains(artist),
                    "提示词不得点名具体歌手（会退化成黑名单）：" + artist);
        }
    }

    @Test
    @DisplayName("播放契约：两份用户提示词都禁止宣称已播放，管理员提示词不带它")
    void playbackContractOnlyForUserPrompts() {
        for (String prompt : new String[]{
                MusicAgent.USER_SYSTEM_PROMPT,
                MusicAgent.USER_WRITE_SYSTEM_PROMPT}) {
            assertTrue(prompt.contains(MusicAgent.PLAYBACK_CONTRACT),
                    "用户提示词必须包含播放契约");
            assertTrue(prompt.contains("**不能**直接播放音频"), "必须明确模型不能播放");
            assertTrue(prompt.contains("**不得**说\"正在播放\""),
                    "必须禁止宣称已经开始播放");
            assertTrue(prompt.contains("get_now_playing"),
                    "必须要求用工具确认\"正在播放\"的是哪首");
            assertFalse(prompt.contains("http"), "播放契约里不该出现地址样例");
        }

        assertFalse(MusicAgent.ADMIN_SYSTEM_PROMPT.contains(MusicAgent.PLAYBACK_CONTRACT),
                "管理员没有播放工具，提示词不应提到播放链路");

        assertTrue(MusicAgent.USER_WRITE_SYSTEM_PROMPT.contains(MusicAgent.PLAYBACK_WRITE_CONTRACT),
                "写能力版本要说明\"把当前播放曲目加入歌单\"的链路");
        assertFalse(MusicAgent.USER_SYSTEM_PROMPT.contains(MusicAgent.PLAYBACK_WRITE_CONTRACT),
                "只读版本不能出现写链路");
    }

    @Test
    @DisplayName("提示词编号连续：边界契约是第 5 条，收尾是第 6 条")
    void contractNumberingStaysContiguous() {
        String prompt = MusicAgent.USER_SYSTEM_PROMPT;

        assertTrue(MusicAgent.GROUNDING_CONTRACT.startsWith("5."),
                "边界契约必须编号为 5");
        assertTrue(MusicAgent.ENDING_CONTRACT.startsWith("6."),
                "收尾契约必须顺延为 6，否则三份提示词会出现编号跳号");
        assertTrue(prompt.contains("5. 检索不到时的边界"), "只读提示词里编号要能对上");
        assertTrue(prompt.contains("6. 收尾方式"), "只读提示词里编号要能对上");
    }
}
