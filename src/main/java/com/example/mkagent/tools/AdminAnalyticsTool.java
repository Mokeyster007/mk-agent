package com.example.mkagent.tools;

import com.example.mkagent.music.MusicAnalyticsRepository;
import com.example.mkagent.music.MusicAnalyticsRepository.DayStat;
import com.example.mkagent.music.MusicAnalyticsRepository.PlayStats;
import com.example.mkagent.music.MusicAnalyticsRepository.SongStat;
import com.example.mkagent.music.MusicRoleResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 管理员统计工具（只读聚合，M2c）。
 *
 * 两个工具：播放总览、歌曲播放排行。
 *
 * 三道防线，缺一不可：
 * <ol>
 *   <li>**白名单**：只有 {@code MUSIC_ADMIN} 的工具数组里有这个 Bean，
 *       普通用户的任务里模型根本看不到它；</li>
 *   <li>**角色校验**：工具内部再查一次 {@code music_user.role}，
 *       因为白名单是按 AgentType 配的，一旦将来有人把类型配错、
 *       或者别的入口复用了这个 Bean，白名单就形同虚设；</li>
 *   <li>**返回脱敏**：SQL 里根本不 SELECT user_id，
 *       聚合结果只含次数与歌曲信息，不含任何用户标识。</li>
 * </ol>
 *
 * 越界参数一律**夹取**而不是报错（与 search_songs 的 limit 一致）：
 * 模型经常传 365 想"看一年"，报错只会浪费一轮；
 * 夹到上限既满足意图又不突破查询预算。
 */
@Component
public class AdminAnalyticsTool {

    private static final Logger log =
            LoggerFactory.getLogger(AdminAnalyticsTool.class);

    /**
     * 时间窗口默认天数。
     */
    static final int DEFAULT_DAYS = 7;

    /**
     * 时间窗口上限（天）。
     *
     * 90 天足够看季度趋势；再大就接近全表扫描，
     * 而聚合结果对模型的边际价值已经很低。
     */
    static final int MAX_DAYS = 90;

    /**
     * 排行默认条数。
     */
    static final int DEFAULT_LIMIT = 10;

    /**
     * 排行上限条数。
     */
    static final int MAX_LIMIT = 50;

    /**
     * 无权限时的统一文案。
     *
     * 不区分"不是管理员"与"用户不存在"：
     * 区分开等于告诉调用方"这个 userId 是存在的"。
     */
    static final String NO_PERMISSION_MESSAGE = "没有权限查看播放统计";

    private final MusicAnalyticsRepository repository;

    private final MusicRoleResolver roleResolver;

    public AdminAnalyticsTool(
            MusicAnalyticsRepository repository,
            MusicRoleResolver roleResolver
    ) {
        this.repository = repository;
        this.roleResolver = roleResolver;
    }

    @Tool(
            name = "get_play_stats",
            description = """
                    查看播放统计总览：时间窗口内的总播放次数、有播放的天数、按日明细。

                    仅管理员可用；普通用户调用会返回无权限。
                    返回的是聚合数据，不包含任何用户标识，无法据此定位到人。
                    """
    )
    public String getPlayStats(
            @ToolParam(
                    required = false,
                    description = """
                            统计窗口天数，单位：天。默认 7，范围 1~90。
                            传 0、负数或超过 90 的值会被夹到合法范围。
                            """
            )
            Integer days
    ) {
        if (!ensureAdmin("get_play_stats")) {
            return ToolResponseEnvelope.error(NO_PERMISSION_MESSAGE);
        }

        int window = clamp(days, DEFAULT_DAYS, MAX_DAYS);

        try {
            long total = repository.totalPlays(window);
            List<DayStat> byDay = repository.playsByDay(window);

            return ToolResponseEnvelope.okSingle(
                    new PlayStats(total, byDay.size(), byDay));
        } catch (Exception e) {
            log.warn("播放统计查询失败：days={}, reason={}",
                    window, e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("播放统计查询失败，请稍后重试");
        }
    }

    @Tool(
            name = "get_top_songs",
            description = """
                    查看播放次数最多的歌曲排行（标题、歌手、播放次数）。

                    仅管理员可用；普通用户调用会返回无权限。
                    返回的是聚合数据，不包含任何用户标识。
                    """
    )
    public String getTopSongs(
            @ToolParam(
                    required = false,
                    description = """
                            统计窗口天数，单位：天。默认 7，范围 1~90。
                            传 0、负数或超过 90 的值会被夹到合法范围。
                            """
            )
            Integer days,

            @ToolParam(
                    required = false,
                    description = """
                            返回条数，默认 10，范围 1~50。
                            传 0、负数或超过 50 的值会被夹到合法范围。
                            """
            )
            Integer limit
    ) {
        if (!ensureAdmin("get_top_songs")) {
            return ToolResponseEnvelope.error(NO_PERMISSION_MESSAGE);
        }

        int window = clamp(days, DEFAULT_DAYS, MAX_DAYS);
        int topN = clamp(limit, DEFAULT_LIMIT, MAX_LIMIT);

        try {
            List<SongStat> songs = repository.topSongs(window, topN);
            return ToolResponseEnvelope.okList(songs);
        } catch (Exception e) {
            log.warn("播放排行查询失败：days={}, limit={}, reason={}",
                    window, topN, e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("播放排行查询失败，请稍后重试");
        }
    }

    /**
     * 第二道防线：角色校验。
     *
     * 日志只记工具名与角色枚举，不记 userId、不记查询参数——
     * 越权尝试的日志本身不该成为新的泄露源。
     */
    private boolean ensureAdmin(String toolName) {
        MusicRoleResolver.MusicRole role = roleResolver.resolveCurrent();

        if (role != MusicRoleResolver.MusicRole.ADMIN) {
            log.warn("拒绝访问管理员统计工具：tool={}, role={}", toolName, role);
            return false;
        }
        return true;
    }

    /**
     * 参数归一化：null / ≤0 → 默认值；> 上限 → 上限。
     */
    static int clamp(Integer value, int defaultValue, int max) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return Math.min(value, max);
    }
}
