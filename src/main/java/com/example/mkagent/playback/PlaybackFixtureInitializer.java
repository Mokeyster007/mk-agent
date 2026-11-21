package com.example.mkagent.playback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 播放夹具：把"测试音"作为**独立曲目**写进曲库，并建立音源映射。
 *
 * <p>为什么不直接给种子歌曲（1001~1060）挂测试音：
 * 那等于把《夜航星》标成可播，而实际听到的是一段正弦音阶——
 * 用户会以为"播放器坏了/音质很差"，评审也无法区分"音源没接"和"接错了"。
 * 所以测试音有自己的 ID、自己的标题（"测试音 A/B"）、自己的流派
 * （{@code TEST_FIXTURE}），一眼能看出它是技术夹具，不是歌曲。
 *
 * <p>排在 {@code MusicSeedInitializer} 之后（{@code @Order(100)}）：
 * 后者用"曲库是否非空"之外的判据判断是否初始化，这里不依赖顺序，
 * 但显式排序能让"夹具写进了一个还没建用户的库"这种困惑不再出现。
 *
 * <p>幂等：按固定 ID 逐条判断，已存在就跳过。
 * 不复用 {@code MusicSeedInitializer} 的"曲库非空即跳过"判据——
 * 那个判据会让**已经初始化过的** demo 库永远拿不到这批新数据。
 *
 * <p>同时插入一条"仅另一位用户可播"的曲目（1903）：
 * 越权拒绝必须有真实数据可测，否则只能靠 mock，测不出
 * "验签通过但归属不符"这条路径。
 */
@Component
@Profile("!prod")
@Order(100)
public class PlaybackFixtureInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaybackFixtureInitializer.class);

    /** 测试音 A：A 大调琶音（听感偏高、偏亮）。 */
    public static final long SONG_TEST_A = 1901L;

    /** 测试音 B：低八度小调动机（听感偏低、偏慢）。 */
    public static final long SONG_TEST_B = 1902L;

    /** 测试音 C：只允许 music-user-2 播放，用于验证越权拒绝。 */
    public static final long SONG_RESTRICTED = 1903L;

    /** 受限曲目的 owner（与 MusicSeedInitializer.USER_BOB 一致）。 */
    public static final String RESTRICTED_OWNER = "music-user-2";

    private static final String PROVIDER = "local-test-fixture";

    private static final int DURATION_SECONDS = 10;

    private final JdbcTemplate jdbcTemplate;

    public PlaybackFixtureInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int inserted = 0;

        inserted += ensureSong(SONG_TEST_A, "测试音 A（琶音）", "A 大调琶音，10 秒");
        inserted += ensureSong(SONG_TEST_B, "测试音 B（低音）", "低八度小调动机，10 秒");
        inserted += ensureSong(SONG_RESTRICTED, "测试音 C（受限）", "仅 music-user-2 可播，用于越权校验");

        inserted += ensureSource(SONG_TEST_A, "test-tone-a.wav", null);
        inserted += ensureSource(SONG_TEST_B, "test-tone-b.wav", null);
        inserted += ensureSource(SONG_RESTRICTED, "test-tone-b.wav", RESTRICTED_OWNER);

        if (inserted > 0) {
            log.info("播放夹具已写入：{} 条（测试音 A/B 可播，测试音 C 仅 {} 可播）",
                    inserted, RESTRICTED_OWNER);
        }
    }

    private int ensureSong(long id, String title, String album) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_song WHERE id = ?", Integer.class, id);

        if (existing != null && existing > 0) {
            return 0;
        }

        jdbcTemplate.update(
                "INSERT INTO music_song (id, title, artist, album, duration_seconds, genre, release_year) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                title,
                "MkAgent Test Fixtures",
                album,
                DURATION_SECONDS,
                "TEST_FIXTURE",
                2026
        );
        return 1;
    }

    private int ensureSource(long songId, String objectKey, String ownerUserId) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_track_source WHERE song_id = ? AND provider = ?",
                Integer.class, songId, PROVIDER);

        if (existing != null && existing > 0) {
            return 0;
        }

        jdbcTemplate.update(
                "INSERT INTO music_track_source "
                        + "(song_id, provider, mode, format, object_key, content_type, "
                        + " owner_user_id, processable, is_test_audio, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                songId,
                PROVIDER,
                PlaybackMode.DIRECT_AUDIO.name(),
                "WAV",
                objectKey,
                "audio/wav",
                ownerUserId,
                // 自有受控音频才允许客户端做 EQ；第三方流一律 false
                true,
                true
        );
        return 1;
    }

    /** 供测试引用：三个夹具曲目的 ID（顺序固定）。 */
    public static List<Long> fixtureSongIds() {
        return List.of(SONG_TEST_A, SONG_TEST_B, SONG_RESTRICTED);
    }
}
