/**
 * 空状态快捷入口。
 *
 * 硬约束：
 * 1. 只出现当前装配真正支持的入口（只读装配下没有任何写操作示例，点了必然失败）；
 * 2. 入口默认只回填输入框，不自动提交任务；
 * 3. 「查看我的歌单」直接打开本地抽屉——数据本来就在库里，不绕模型。
 *
 * 措辞都对应后端真实存在的工具：
 *   搜索歌曲 → search_songs
 *   歌曲详情 → get_song_detail
 *   我的歌单 → get_user_playlists
 */

export interface QuickEntry {
  title: string
  prompt: string
  icon: 'search' | 'disc' | 'folder' | 'list' | 'plus'
  /** 非任务入口：直接打开本地面板 */
  action?: 'library'
}

export const QUICK_ENTRIES: QuickEntry[] = [
  {
    title: '查夜航星的歌',
    prompt: '查一下夜航星的歌',
    icon: 'search',
  },
  {
    title: '查《城市霓虹》的发行年份',
    prompt: '《城市霓虹》是哪一年发行的？',
    icon: 'disc',
  },
  {
    title: '查看我的歌单',
    prompt: '我目前有哪些歌单？',
    icon: 'folder',
    action: 'library',
  },
]

export const READ_ONLY_CAPABILITY_TEXT =
  '当前为只读装配：可以搜索歌曲、查看歌曲与歌单。写操作还没有开放。'

export const WRITE_CAPABILITY_TEXT =
  '当前为审批写装配：写操作会先暂停并生成审批卡，需要你确认后才会执行。'
