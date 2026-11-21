/**
 * 最小 EQ：前级增益 + 三段（低架 / 峰值 / 高架）+ 一键旁路。
 *
 * 只做**受控来源**（服务端 `capabilities.eq = true`，即自有音频）。
 * 第三方流一律不开放——不是技术做不到，而是没有授权也没有稳定的格式承诺，
 * 界面上标一个能调但实际不生效的 EQ，比不做更糟。
 *
 * 三条实现约束：
 * 1. `MediaElementAudioSourceNode` 对同一个 `<audio>` **只能创建一次**，
 *    重复创建会抛 InvalidStateError，所以这里用 `attached` 守住；
 * 2. `AudioContext` 必须在用户手势之后才允许出声，
 *    因此创建后立即 `resume()`，并在每次 play 时再 `resume()` 一次；
 * 3. 旁路是**真的断开重连**，不是把三个增益设成 0：
 *    后者仍然过一遍滤波器，相位会有细微变化，称不上旁路。
 *
 * 不声称 Bit-Perfect / DAC 独占 / 自动升频：浏览器链路做不到，
 * 这些词只会变成无法验证的营销话术。
 */
export interface EqSettings {
  /** 前级增益（dB），负值用于防削波 */
  preamp: number
  /** 低频搁架（200 Hz）增益（dB） */
  low: number
  /** 中频峰值（1 kHz, Q=0.9）增益（dB） */
  mid: number
  /** 高频搁架（4 kHz）增益（dB） */
  high: number
}

export const FLAT_EQ: EqSettings = { preamp: 0, low: 0, mid: 0, high: 0 }

export interface Equalizer {
  /** 把某个 `<audio>` 接进处理图；重复调用只生效一次 */
  attach(element: HTMLAudioElement): boolean
  apply(settings: EqSettings, bypass: boolean): void
  resume(): void
  dispose(): void
}

type AudioContextCtor = new () => AudioContext

function audioContextCtor(): AudioContextCtor | null {
  const w = window as unknown as {
    AudioContext?: AudioContextCtor
    webkitAudioContext?: AudioContextCtor
  }
  return w.AudioContext ?? w.webkitAudioContext ?? null
}

/**
 * @returns 环境不支持 Web Audio 时返回 null（调用方据此禁用 EQ 入口）
 */
export function createEqualizer(): Equalizer | null {
  const Ctor = audioContextCtor()
  if (!Ctor) {
    return null
  }
  // 单独取一个非空引用：闭包里不会保留外层 if 的收窄结果
  const Context = Ctor

  let ctx: AudioContext | null = null
  let source: MediaElementAudioSourceNode | null = null
  let preamp: GainNode | null = null
  let low: BiquadFilterNode | null = null
  let mid: BiquadFilterNode | null = null
  let high: BiquadFilterNode | null = null
  let bypassed = false

  function build(element: HTMLAudioElement): boolean {
    try {
      ctx = new Context()
      source = ctx.createMediaElementSource(element)

      preamp = ctx.createGain()
      low = ctx.createBiquadFilter()
      low.type = 'lowshelf'
      low.frequency.value = 200

      mid = ctx.createBiquadFilter()
      mid.type = 'peaking'
      mid.frequency.value = 1000
      mid.Q.value = 0.9

      high = ctx.createBiquadFilter()
      high.type = 'highshelf'
      high.frequency.value = 4000

      source.connect(preamp)
      preamp.connect(low)
      low.connect(mid)
      mid.connect(high)
      high.connect(ctx.destination)

      return true
    } catch {
      // 例如同一个元素被重复接入，或环境不支持：回退为直通播放
      ctx = null
      source = null
      return false
    }
  }

  return {
    attach(element: HTMLAudioElement): boolean {
      if (source) {
        return true
      }
      return build(element)
    },

    apply(settings: EqSettings, bypass: boolean): void {
      if (!ctx || !source || !preamp || !low || !mid || !high) {
        return
      }

      // 旁路：断开处理链，直接送到输出
      if (bypass !== bypassed) {
        bypassed = bypass
        source.disconnect()
        if (bypass) {
          source.connect(ctx.destination)
        } else {
          source.connect(preamp)
        }
      }

      preamp.gain.value = dbToLinear(settings.preamp)
      low.gain.value = settings.low
      mid.gain.value = settings.mid
      high.gain.value = settings.high
    },

    resume(): void {
      if (ctx && ctx.state === 'suspended') {
        void ctx.resume()
      }
    },

    dispose(): void {
      try {
        source?.disconnect()
      } catch {
        // 已断开时忽略
      }
      void ctx?.close()
      ctx = null
      source = null
    },
  }
}

function dbToLinear(db: number): number {
  return Math.pow(10, db / 20)
}
