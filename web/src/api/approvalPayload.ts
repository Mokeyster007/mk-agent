/**
 * `approval_required` 事件的 data 解析。
 *
 * <p>单独成文件（而不是留在某个 store 里）是因为售后与音乐两条链路都要用它：
 * 谁先把解析复制一份，谁就会在"摘要多了一个字段"时先落后。
 *
 * <p>纪律：解析失败一律返回 null，**绝不构造一张编出来的卡**。
 * 界面上少一张卡，好过多一张内容不对的卡——确认卡是"用户同意什么"的唯一凭据。
 */
import type { ApprovalRequiredPayload, ApprovalSummaryDetail } from './types'

function parseDetails(value: unknown): ApprovalSummaryDetail[] {
  if (!Array.isArray(value)) {
    return []
  }
  const details: ApprovalSummaryDetail[] = []
  for (const item of value) {
    if (!item || typeof item !== 'object') {
      continue
    }
    const record = item as Record<string, unknown>
    const label = typeof record.label === 'string' ? record.label : ''
    const text = typeof record.value === 'string' ? record.value : ''
    if (label && text) {
      details.push({ label, value: text })
    }
  }
  return details
}

export function parseApprovalPayload(message: string): ApprovalRequiredPayload | null {
  try {
    const parsed = JSON.parse(message) as Partial<ApprovalRequiredPayload>
    if (!parsed || typeof parsed.approvalId !== 'string' || !parsed.approvalId) {
      return null
    }

    const summary = parsed.summary
    const empty = {
      tool: '',
      toolLabel: '',
      playlistName: null,
      songCount: null,
      songs: [] as string[],
      roundToolCount: 0,
      text: '',
      details: [] as ApprovalSummaryDetail[],
    }

    return {
      approvalId: parsed.approvalId,
      expiresAt: typeof parsed.expiresAt === 'string' ? parsed.expiresAt : '',
      summary:
        summary && typeof summary === 'object'
          ? {
              tool: summary.tool ?? '',
              toolLabel: summary.toolLabel ?? '',
              playlistName: summary.playlistName ?? null,
              songCount: summary.songCount ?? null,
              songs: Array.isArray(summary.songs) ? summary.songs : [],
              roundToolCount: summary.roundToolCount ?? 0,
              text: summary.text ?? '',
              details: parseDetails((summary as { details?: unknown }).details),
            }
          : empty,
    }
  } catch {
    return null
  }
}
