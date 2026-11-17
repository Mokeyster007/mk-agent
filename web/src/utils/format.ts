/**
 * 格式化工具。
 *
 * 一条硬规则：**未知就是未知**。
 * Token、耗时这些字段后端可能没有（老数据、失败任务），
 * 缺失时返回 "暂无数据"，绝不写 0 —— 0 是一个"测到了、值是零"的断言。
 */

export const UNKNOWN = '暂无数据'

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return UNKNOWN
  }
  const date = parseBackendTime(value)
  if (!date) {
    return UNKNOWN
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function formatClock(value: number | null | undefined): string {
  if (!value) {
    return '--:--:--'
  }
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/**
 * 后端 LocalDateTime 序列化成 "2026-11-16T02:33:31"（无时区）。
 * 直接 new Date("2026-11-16T02:33:31") 在各浏览器按本地时间解析，
 * 与后端"本地时间"语义一致，因此这里刻意不补 Z。
 */
function parseBackendTime(value: string): Date | null {
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 毫秒 → "1.2 秒" / "3 分 05 秒" */
export function formatDuration(millis: number | null | undefined): string {
  if (millis === null || millis === undefined) {
    return UNKNOWN
  }
  if (millis < 1000) {
    return `${millis} 毫秒`
  }
  if (millis < 60_000) {
    return `${(millis / 1000).toFixed(1)} 秒`
  }
  const minutes = Math.floor(millis / 60_000)
  const seconds = Math.round((millis % 60_000) / 1000)
  return `${minutes} 分 ${String(seconds).padStart(2, '0')} 秒`
}

export function formatTokens(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return UNKNOWN
  }
  return value.toLocaleString('zh-CN')
}

/** 相对时间，只用于"本地记录"这种低精度场景 */
export function formatRelative(timestamp: number): string {
  const diff = Date.now() - timestamp
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

/** 长 ID 中间省略，保留头尾便于人工比对 */
export function shortId(value: string | null | undefined, keep = 8): string {
  if (!value) {
    return UNKNOWN
  }
  if (value.length <= keep * 2 + 1) {
    return value
  }
  return `${value.slice(0, keep)}…${value.slice(-keep)}`
}

/** 工具轨迹字符串 "a,b,c" → 数组（后端存的是逗号分隔的工具名） */
export function splitTrace(trace: string | null | undefined): string[] {
  if (!trace) {
    return []
  }
  return trace
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}
