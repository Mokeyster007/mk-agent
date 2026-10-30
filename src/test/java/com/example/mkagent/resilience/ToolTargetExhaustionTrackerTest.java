package com.example.mkagent.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点级耗尽计数器（纯单元测试，不联网、不启动 Spring）。
 *
 * 保护目标：同一工具 + 同一目标主机连续重试耗尽达到阈值后，
 * 后续调用不再执行底层工具。
 *
 * 关键约定：
 * 1. 同一主机的不同路径算**同一个目标**（"站点打不开"是主机级现象）；
 * 2. 不同主机互不影响；
 * 3. 参数里解析不出 http/https 主机时退化为"按工具名计数"，
 *    绝不因为参数格式问题抛异常。
 */
class ToolTargetExhaustionTrackerTest {

    @Test
    @DisplayName("key 由工具名 + 主机组成")
    void targetKeyContainsToolAndHost() {
        String key = ToolTargetExhaustionTracker.targetKey(
                "web_scrape",
                "{\"url\":\"https://GitHub.com/a/b\"}"
        );

        assertEquals("web_scrape|github.com", key,
                "主机必须小写化，否则同一站点会因大小写被算成两个目标");
    }

    @Test
    @DisplayName("同一主机的不同路径共享同一个目标 key")
    void sameHostDifferentPathSharesKey() {
        String first = ToolTargetExhaustionTracker.targetKey(
                "web_scrape", "{\"url\":\"https://github.com/a\"}");
        String second = ToolTargetExhaustionTracker.targetKey(
                "web_scrape", "{\"url\":\"https://github.com/b/c?x=1\"}");

        assertEquals(first, second,
                "按路径区分会让站点级熔断形同虚设");
    }

    @Test
    @DisplayName("不同主机是不同的目标 key")
    void differentHostsDifferentKeys() {
        String github = ToolTargetExhaustionTracker.targetKey(
                "web_scrape", "{\"url\":\"https://github.com\"}");
        String example = ToolTargetExhaustionTracker.targetKey(
                "web_scrape", "{\"url\":\"https://example.com\"}");

        assertFalse(github.equals(example),
                "不同主机必须互不影响");
    }

    @Test
    @DisplayName("参数里没有 URL 时退化为按工具名计数")
    void noUrlFallsBackToToolLevelKey() {
        String key = ToolTargetExhaustionTracker.targetKey(
                "web_search", "{\"query\":\"Spring AI 文档\"}");

        assertEquals("web_search|", key,
                "web_search 没有主机概念，只能按工具计数");
    }

    @Test
    @DisplayName("非法 JSON / null 参数不抛异常，退化为按工具名计数")
    void invalidInputFallsBackToToolLevelKey() {
        assertEquals("web_scrape|",
                ToolTargetExhaustionTracker.targetKey("web_scrape", "not-a-json"));
        assertEquals("web_scrape|",
                ToolTargetExhaustionTracker.targetKey("web_scrape", null));
        assertEquals("web_scrape|",
                ToolTargetExhaustionTracker.targetKey("web_scrape", ""));
    }

    @Test
    @DisplayName("非 http/https 协议不算站点")
    void nonHttpSchemeIsIgnored() {
        String key = ToolTargetExhaustionTracker.targetKey(
                "read_file", "{\"url\":\"file:///etc/passwd\"}");

        assertEquals("read_file|", key,
                "file: 之类协议不构成'站点'，不该参与站点级熔断");
    }

    @Test
    @DisplayName("嵌套 JSON 里的 URL 也能被识别")
    void nestedJsonIsScanned() {
        String key = ToolTargetExhaustionTracker.targetKey(
                "web_scrape",
                "{\"options\":{\"headers\":{\"referer\":\"https://docs.example.org/x\"}}}"
        );

        assertEquals("web_scrape|docs.example.org", key,
                "字段命名不可控，任何能解析出主机的字符串都应被识别");
    }

    @Test
    @DisplayName("累计计数达到阈值后判定为拦截")
    void recordAndThreshold() {
        ToolTargetExhaustionTracker tracker = new ToolTargetExhaustionTracker();
        String key = "web_scrape|github.com";

        assertEquals(0, tracker.count(key), "初始为 0");
        assertFalse(tracker.isBlocked(key, 2), "0 次不应被拦截");

        assertEquals(1, tracker.record(key));
        assertFalse(tracker.isBlocked(key, 2), "1 次未达阈值");

        assertEquals(2, tracker.record(key));
        assertTrue(tracker.isBlocked(key, 2), "达到阈值应拦截");
        assertTrue(tracker.isBlocked(key, 1), "阈值更低时同样成立");
    }

    @Test
    @DisplayName("阈值 <= 0 表示关闭该保护")
    void nonPositiveThresholdDisablesBlocking() {
        ToolTargetExhaustionTracker tracker = new ToolTargetExhaustionTracker();
        String key = "web_scrape|github.com";
        tracker.record(key);
        tracker.record(key);
        tracker.record(key);

        assertFalse(tracker.isBlocked(key, 0),
                "0 表示关闭，方便线上临时回滚");
        assertFalse(tracker.isBlocked(key, -1));
    }

    @Test
    @DisplayName("不同主机各自独立计数")
    void countsAreIndependentPerTarget() {
        ToolTargetExhaustionTracker tracker = new ToolTargetExhaustionTracker();

        tracker.record("web_scrape|github.com");
        tracker.record("web_scrape|github.com");

        assertTrue(tracker.isBlocked("web_scrape|github.com", 2));
        assertFalse(tracker.isBlocked("web_scrape|example.com", 2),
                "另一个主机不应被牵连");
    }

    @Test
    @DisplayName("hostOfKey 取出主机用于文案；无主机时返回空串")
    void hostOfKeyExtractsHost() {
        assertEquals("github.com",
                ToolTargetExhaustionTracker.hostOfKey("web_scrape|github.com"));
        assertEquals("",
                ToolTargetExhaustionTracker.hostOfKey("web_search|"));
        assertEquals("", ToolTargetExhaustionTracker.hostOfKey(null));
    }

    @Test
    @DisplayName("clear 后计数归零")
    void clearResetsCounts() {
        ToolTargetExhaustionTracker tracker = new ToolTargetExhaustionTracker();
        tracker.record("web_scrape|github.com");

        tracker.clear();

        assertEquals(0, tracker.count("web_scrape|github.com"));
        assertTrue(tracker.snapshot().isEmpty());
    }
}
