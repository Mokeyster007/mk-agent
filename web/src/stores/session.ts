/**
 * 会话级状态：联调身份 + 能力模式 + 入口。
 *
 * 只做三件事：
 * 1. 把 identity 模块里的身份包装成响应式，供界面显示；
 * 2. 暴露能力模式（只读 / 审批写），**仅用于界面文案与示例任务**；
 * 3. 记录当前选择的入口（用户 / 管理员），仅开发模式可改。
 *
 * 明确不做：不在前端判断权限、不把能力模式发到后端、
 * 不提供"打开写能力"的开关。
 */
import { computed, ref } from 'vue'
import {
  ALLOW_DEV_IDENTITY,
  CAPABILITY_MODE,
  getUserId,
  resetDevUserId,
  setDevUserId,
} from '@/api/identity'
import { getCapabilities } from '@/api/music'
import type { MusicEntry } from '@/api/types'

const userId = ref(getUserId())
const entry = ref<MusicEntry>('MUSIC_USER')

/**
 * 能力模式。
 *
 * 初始值是构建期常量（保守默认：只读），启动后用
 * `GET /ai/music/capabilities` 校正为**后端真实装配**。
 *
 * 为什么不在构建期定死：同一份前端会连只读装配和审批写装配两种后端，
 * 构建期常量一错，页面就会显示"审批写"而后端根本没给模型写工具——
 * 用户点了创建，模型只能说"不支持"。反过来更糟：页面说只读，
 * 实际能写，用户不会去审批。
 *
 * 探测失败时保留构建期默认（宁可显示得保守）。
 */
const capabilityModeRef = ref<'readonly' | 'write_approval'>(CAPABILITY_MODE)
const capabilitySource = ref<'build' | 'server'>('build')
const promptVersion = ref<string | null>(null)

/** 只有开发模式才允许切换入口；正式构建固定走用户入口。 */
const canSwitchEntry = ALLOW_DEV_IDENTITY

export const session = {
  userId,
  entry,
  canSwitchEntry,
  allowDevIdentity: ALLOW_DEV_IDENTITY,
  /** 用 getter 暴露，调用方不需要知道它是不是 ref */
  get capabilityMode(): 'readonly' | 'write_approval' {
    return capabilityModeRef.value
  },
  capabilitySource,
  promptVersion,
  isReadOnly: computed(() => capabilityModeRef.value === 'readonly'),

  /**
   * 读取后端能力，校正界面文案。
   *
   * 不抛错：能力探测只是"让文案更准"，失败不该挡住用户使用。
   */
  async loadCapabilities(): Promise<void> {
    try {
      const caps = await getCapabilities()
      capabilityModeRef.value = caps.writeApproval ? 'write_approval' : 'readonly'
      capabilitySource.value = 'server'
      promptVersion.value = caps.promptVersion || null
    } catch {
      capabilitySource.value = 'build'
    }
  },

  updateUserId(next: string): boolean {
    const ok = setDevUserId(next)
    if (ok) {
      userId.value = getUserId()
      // 换了身份就是换了一个人的歌单与任务，能力也要按新身份重探
      void session.loadCapabilities()
    }
    return ok
  },

  resetUserId(): void {
    resetDevUserId()
    userId.value = getUserId()
    void session.loadCapabilities()
  },

  setEntry(next: MusicEntry): void {
    if (!canSwitchEntry) {
      return
    }
    entry.value = next
  },
}
