#!/usr/bin/env python3
"""生成播放链路用的**测试音**（技术夹具，不是歌曲）。

为什么要自己生成：
1. 仓库里不能放任何未获授权的音频；
2. 播放链路需要两个**可区分**的真实媒体文件，才能验证切歌、进度拖拽、
   Range 请求与「播完自动下一首」；
3. 纯标准库（wave + math）即可，不引入 ffmpeg 之类的构建依赖。

产物：src/main/resources/media/test-tone-a.wav / test-tone-b.wav
- 16000 Hz / 单声道 / 16-bit PCM —— 体积小（约 320 KB），浏览器原生可解；
- 首尾各 20ms 淡入淡出，避免切换时的爆音；
- 两条音高与节奏明显不同，人耳一听就能分辨切歌是否生效。

它们**不**代表任何真实歌曲：曲库里以 "测试音 A/B" 命名，
artist 为 "MkAgent Test Fixtures"，genre 为 TEST_FIXTURE。
"""

import math
import os
import struct
import wave

SAMPLE_RATE = 16000
DURATION_SECONDS = 10.0
FADE_SECONDS = 0.02
OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src",
    "main",
    "resources",
    "media",
)


def envelope(t: float, total: float) -> float:
    """首尾淡入淡出，其余为 1。"""
    if t < FADE_SECONDS:
        return t / FADE_SECONDS
    if t > total - FADE_SECONDS:
        return max(0.0, (total - t) / FADE_SECONDS)
    return 1.0


def write_wav(path: str, samples: list[float]) -> None:
    peak = max(1e-9, max(abs(s) for s in samples))
    # 留 12% 余量，避免 16-bit 量化削顶
    scale = 0.88 * 32767 / peak
    frames = b"".join(
        struct.pack("<h", int(max(-32768, min(32767, s * scale)))) for s in samples
    )
    with wave.open(path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(frames)


def tone_a() -> list[float]:
    """测试音 A：A 大调琶音（440 / 554.37 / 659.25 / 880），每音 0.5s，循环。"""
    notes = [440.00, 554.37, 659.25, 880.00]
    total = DURATION_SECONDS
    out: list[float] = []
    for i in range(int(total * SAMPLE_RATE)):
        t = i / SAMPLE_RATE
        note = notes[int(t / 0.5) % len(notes)]
        # 单个音内部再做 8ms 的起落，听感上是"拨奏"而不是连续啸叫
        local = (t % 0.5) / 0.5
        decay = math.exp(-3.2 * local)
        pad = 0.18 * math.sin(2 * math.pi * 220.0 * t)
        value = 0.72 * decay * math.sin(2 * math.pi * note * t) + pad
        out.append(value * envelope(t, total))
    return out


def tone_b() -> list[float]:
    """测试音 B：低八度小调动机（110 / 130.81 / 146.83），每音 0.75s，比 A 明显更低更慢。"""
    notes = [110.00, 130.81, 146.83, 130.81]
    total = DURATION_SECONDS
    out: list[float] = []
    for i in range(int(total * SAMPLE_RATE)):
        t = i / SAMPLE_RATE
        note = notes[int(t / 0.75) % len(notes)]
        # 方波近似（三次谐波叠加），与 A 的正弦琶音音色区分更明显
        base = math.sin(2 * math.pi * note * t)
        third = math.sin(2 * math.pi * note * 3 * t) / 3.0
        fifth = math.sin(2 * math.pi * note * 1.5 * t) / 5.0
        value = 0.62 * (base + 0.45 * third + 0.3 * fifth)
        out.append(value * envelope(t, total))
    return out


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    targets = [
        ("test-tone-a.wav", tone_a()),
        ("test-tone-b.wav", tone_b()),
    ]
    for name, samples in targets:
        path = os.path.join(OUT_DIR, name)
        write_wav(path, samples)
        print(f"{name}: {os.path.getsize(path)} bytes, {len(samples)} frames")


if __name__ == "__main__":
    main()
