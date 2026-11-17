/**
 * 请求身份（X-User-Id）。
 *
 * 现状：后端没有真实认证，UserContextHolder 只从 X-User-Id 请求头取占位身份。
 * 因此本文件提供的身份：
 *   - 只是本地联调身份，**不代表真实登录**；
 *   - 不参与任何权限判断（权限一律由后端 MusicAccessGuard 决定）；
 *   - 不写进 URL、不写进日志。
 *
 * 安全边界：
 *   正式构建（PROD）里没有任何切换身份的界面，
 *   身份只能来自构建期配置 VITE_DEV_USER_ID；
 *   只有 DEV 且 VITE_ALLOW_DEV_IDENTITY !== 'false' 时，
 *   才允许在"开发联调"面板里改。
 */

const STORAGE_KEY = 'mkagent.dev.userId'

/**
 * 身份策略（纯函数，便于测试）。
 *
 * 正式构建（dev=false）一律不允许切换身份：
 * 界面上没有入口，控制台调用 setDevUserId 也返回 false。
 */
export interface IdentityPolicy {
  dev: boolean
  allowSwitch: boolean
}

export function resolveIdentityPolicy(env: { dev: boolean; allow?: string | undefined }): IdentityPolicy {
  return {
    dev: env.dev,
    allowSwitch: env.dev && (env.allow ?? 'true') !== 'false',
  }
}

const POLICY = resolveIdentityPolicy({
  dev: import.meta.env.DEV,
  allow: import.meta.env.VITE_ALLOW_DEV_IDENTITY,
})

/** 是否处于本地开发模式 */
export const IS_DEV = POLICY.dev

/** 是否允许在界面上修改联调身份 */
export const ALLOW_DEV_IDENTITY = POLICY.allowSwitch

/** 构建期默认身份；缺省用种子数据里的普通用户 */
const CONFIGURED_USER_ID = (import.meta.env.VITE_DEV_USER_ID ?? 'music-user-1').trim()

function readStored(): string | null {
  if (!ALLOW_DEV_IDENTITY) {
    return null
  }
  try {
    const value = window.localStorage.getItem(STORAGE_KEY)
    return value && value.trim() ? value.trim() : null
  } catch {
    // 隐私模式下 localStorage 可能抛异常，降级为构建期身份
    return null
  }
}

let cached: string = readStored() ?? CONFIGURED_USER_ID

/** 当前生效的联调身份。 */
export function getUserId(): string {
  return cached
}

/**
 * 修改联调身份。
 *
 * 只在 ALLOW_DEV_IDENTITY 为真时生效，否则静默忽略——
 * 防止有人通过控制台在正式构建里改身份。
 */
export function setDevUserId(value: string): boolean {
  if (!ALLOW_DEV_IDENTITY) {
    return false
  }
  const next = value.trim()
  if (!next) {
    return false
  }
  cached = next
  try {
    window.localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 存不进去也不影响本次会话
  }
  return true
}

/** 恢复构建期默认身份（清掉本地覆盖）。 */
export function resetDevUserId(): void {
  if (!ALLOW_DEV_IDENTITY) {
    return
  }
  cached = CONFIGURED_USER_ID
  try {
    window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 忽略
  }
}

/**
 * 身份是否来自"正式配置"。
 *
 * 用于界面区分"开发联调身份"与"正式环境身份"，
 * 不用于任何权限判断。
 */
export function isDevIdentity(): boolean {
  return ALLOW_DEV_IDENTITY
}

/**
 * 能力模式。
 *
 * 注意：这是**展示用**的静态配置，不是运行时探测结果。
 * 真实能力由后端 AgentToolProvider 的白名单决定，
 * 前端既无法也不需要影响它——本值永远不会出现在任何请求里。
 *
 * 默认 readonly，与当前生产装配一致（MUSIC_USER 白名单里没有写工具）。
 * 想验证"审批写"界面时，用 VITE_CAPABILITY_MODE=write_approval 构建一个
 * 独立的开发实例，而不是在界面上加一个开关。
 */
export const CAPABILITY_MODE: 'readonly' | 'write_approval' =
  import.meta.env.VITE_CAPABILITY_MODE === 'write_approval' ? 'write_approval' : 'readonly'
