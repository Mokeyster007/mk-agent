/**
 * 空状态示例任务。
 *
 * 硬约束：示例必须**只出现在当前装配真正支持的范围内**。
 * 生产只读装配下推荐"帮我创建歌单"属于骗用户——点下去必然失败，
 * 所以这里按能力模式分开维护，只读模式下没有任何写操作示例。
 *
 * 措辞都对应后端真实存在的工具：
 *   搜索歌曲 → search_songs
 *   歌曲详情 → get_song_detail
 *   我的歌单 → get_user_playlists
 *   歌单歌曲 → get_playlist_songs
 */

export interface ExampleTask {
  title: string
  prompt: string
  icon: 'search' | 'disc' | 'folder' | 'list' | 'plus'
}

const READ_ONLY_EXAMPLES: ExampleTask[] = [
  {
    title: '搜索某位歌手的歌曲',
    prompt: '帮我找一下夜航星的歌',
    icon: 'search',
  },
  {
    title: '查看歌曲详细信息',
    prompt: '《城市霓虹》是哪一年发行的？属于哪张专辑？',
    icon: 'disc',
  },
  {
    title: '查询我的歌单',
    prompt: '我目前有哪些歌单？',
    icon: 'folder',
  },
  {
    title: '查看歌单里有哪些歌曲',
    prompt: '「深夜通勤」这个歌单里都有哪些歌？',
    icon: 'list',
  },
]

const WRITE_APPROVAL_EXAMPLES: ExampleTask[] = [
  ...READ_ONLY_EXAMPLES.slice(0, 2),
  {
    title: '创建一个歌单（需审批）',
    prompt: '帮我建一个歌单叫「清晨慢跑」，先放《城市霓虹》这一首。',
    icon: 'plus',
  },
  {
    title: '往歌单里加歌（需审批）',
    prompt: '把《午夜电台》加到「深夜通勤」里。',
    icon: 'plus',
  },
]

export function examplesFor(mode: 'readonly' | 'write_approval'): ExampleTask[] {
  return mode === 'write_approval' ? WRITE_APPROVAL_EXAMPLES : READ_ONLY_EXAMPLES
}

export const READ_ONLY_CAPABILITY_TEXT =
  '当前装配为只读：可以搜索歌曲、查看歌曲详情、查询自己的歌单与歌单歌曲。' +
  '创建歌单、修改歌单这类写操作还没有开放。'

export const WRITE_CAPABILITY_TEXT =
  '当前装配包含审批写能力：写操作会先暂停并生成审批卡，需要你明确批准后才会执行。'
