package com.example.mkagent.playback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 把 {@code object_key} 解析成可读取的媒体资源。
 *
 * <p>两种位置，按配置选择：
 * <ul>
 *   <li>配置了 {@code mkagent.playback.media-dir} → 该目录下的文件
 *       （本地演示把测试音放在这里，或将来挂载自有音频）；</li>
 *   <li>未配置 → classpath 下的 {@code media/}（随 jar 打包的测试夹具）。</li>
 * </ul>
 *
 * <p>为什么不用 {@code src/main/resources/static/}：
 * 那是 Spring Boot 默认的公开静态目录，放进去等于任何人拿到 URL 就能下载，
 * 与"服务端鉴权后签短期地址"直接冲突。媒体放在 {@code media/} 下，
 * 只能经 {@code /ai/music/playback/media/{token}} 读。
 *
 * <p>路径校验是硬要求：{@code object_key} 来自数据库（当前由种子写入），
 * 但一旦将来有管理入口，它就是外部可控字符串。
 * 这里只接受"字母数字 + 点 + 横线 + 下划线 + 斜杠"的相对路径，
 * 且规范化后必须仍在根目录内——{@code ../} 一律拒绝。
 */
@Component
public class MediaFileStore {

    private static final Logger log = LoggerFactory.getLogger(MediaFileStore.class);

    /**
     * 允许的字符集：不含反斜杠、冒号、空格，天然排除 Windows 盘符与 URL 协议前缀。
     */
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._/-]{1,255}$");

    /**
     * 只允许音频类型：即使映射表被写脏，也不会让签名地址变成 HTML/脚本的出口。
     */
    private static final Pattern SAFE_CONTENT_TYPE = Pattern.compile("^audio/[A-Za-z0-9.+-]{1,40}$");

    private final Path mediaRoot;

    public MediaFileStore(@Value("${mkagent.playback.media-dir:}") String mediaDir) {
        this.mediaRoot = (mediaDir == null || mediaDir.isBlank())
                ? null
                : Paths.get(mediaDir).toAbsolutePath().normalize();
    }

    /**
     * 解析媒体文件。
     *
     * @return 文件存在且类型合法时返回句柄；否则 empty（调用方给出"不可播"原因）
     */
    public Optional<MediaHandle> open(TrackSource source) {
        if (source == null || source.objectKey() == null || source.objectKey().isBlank()) {
            return Optional.empty();
        }

        String key = source.objectKey().trim();
        if (!SAFE_KEY.matcher(key).matches() || key.contains("..")) {
            log.warn("拒绝非法的媒体定位：songId={}, key={}", source.songId(), key);
            return Optional.empty();
        }

        String contentType = source.contentType() == null ? "" : source.contentType().trim();
        if (!SAFE_CONTENT_TYPE.matcher(contentType).matches()) {
            log.warn("拒绝非音频 Content-Type：songId={}, type={}", source.songId(), contentType);
            return Optional.empty();
        }

        Resource resource = mediaRoot == null
                ? new ClassPathResource("media/" + key)
                : fileResource(key);

        if (resource == null || !resource.exists()) {
            log.warn("媒体文件不存在：songId={}, key={}, root={}",
                    source.songId(), key, mediaRoot == null ? "classpath:media/" : mediaRoot);
            return Optional.empty();
        }

        try {
            long length = resource.contentLength();
            if (length <= 0) {
                return Optional.empty();
            }
            return Optional.of(new MediaHandle(resource, length, contentType));
        } catch (Exception e) {
            log.warn("读取媒体长度失败：songId={}, reason={}",
                    source.songId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 文件模式下额外确认"规范化后仍在根目录内"。
     *
     * <p>正则已经挡住了 {@code ..}，这里是第二道：符号链接可以绕过纯字符串检查。
     */
    private Resource fileResource(String key) {
        Path candidate = mediaRoot.resolve(key).normalize();
        if (!candidate.startsWith(mediaRoot)) {
            return null;
        }
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        return new FileSystemResource(candidate);
    }

    /** 已解析的媒体文件。 */
    public record MediaHandle(Resource resource, long length, String contentType) {
    }
}
