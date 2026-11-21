package com.example.mkagent.playback;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 播放域时钟。
 *
 * <p>抽出来只有一个目的：让"180 秒没有心跳就失效"这种规则**可测**。
 * 直接写 {@code Instant.now()} 的代码只能靠 {@code Thread.sleep} 验证，
 * 而 sleep 既慢又不稳定。
 *
 * <p>它不是通用时间工具：只服务播放域。其它地方继续用 {@code Instant.now()}，
 * 避免一个全局可替换的时钟把无关行为也变成"可被测试篡改"。
 */
@Component
public class PlaybackClock {

    /** 当前时刻。服务端时间，永不采用客户端传入的时间。 */
    public Instant now() {
        return Instant.now();
    }
}
