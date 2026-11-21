import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PlayCard from './PlayCard.vue'
import { player } from '@/stores/player'
import type { PlaybackCandidateVO } from '@/api/types'

/**
 * 播放卡：只渲染服务端给的候选，不解析文本。
 *
 * 关键断言是"点某一首时队列里只有可播曲目，且从这一首开始"——
 * 否则会出现"点了第 2 首，播的却是第 1 首"这种只有真机才发现的错位。
 */
const candidates: PlaybackCandidateVO[] = [
  {
    songId: 1901,
    title: '测试音 A（琶音）',
    artist: 'MkAgent Test Fixtures',
    album: '技术夹具（非歌曲）',
    durationSeconds: 10,
    playable: true,
    format: 'WAV',
    unavailableReason: null,
  },
  {
    songId: 1001,
    title: '夜航星',
    artist: '星尘',
    album: null,
    durationSeconds: 240,
    playable: false,
    format: null,
    unavailableReason: '暂无可用音源',
  },
  {
    songId: 1902,
    title: '测试音 B（低音）',
    artist: 'MkAgent Test Fixtures',
    album: '技术夹具（非歌曲）',
    durationSeconds: 10,
    playable: true,
    format: 'WAV',
    unavailableReason: null,
  },
]

beforeEach(() => {
  // jsdom 不实现 pause/load：这里只测渲染与派发
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined)
})

afterEach(() => {
  // 先停播放器再恢复 mock：否则 stop() 会调用 jsdom 未实现的 pause()
  player.stop()
  vi.restoreAllMocks()
})

describe('PlayCard', () => {
  it('列出全部候选，并标注可播数量', () => {
    const wrapper = mount(PlayCard, { props: { candidates } })

    expect(wrapper.findAll('.playcard__row')).toHaveLength(3)
    expect(wrapper.text()).toContain('2/3 首可播')
    expect(wrapper.text()).toContain('测试音 A（琶音）')
    wrapper.unmount()
  })

  it('不可播的曲目显示原因，且没有播放按钮', () => {
    const wrapper = mount(PlayCard, { props: { candidates } })
    const blocked = wrapper.findAll('.playcard__row')[1]

    expect(blocked.text()).toContain('暂无可用音源')
    expect(blocked.find('button').exists()).toBe(false)
    wrapper.unmount()
  })

  it('点击第二首可播曲目：队列只含可播项，并从它开始', async () => {
    const spy = vi.spyOn(player, 'playQueue').mockResolvedValue(undefined)
    const wrapper = mount(PlayCard, { props: { candidates } })

    // 第 3 行是"测试音 B"，在可播队列里是第 2 个
    await wrapper.findAll('.playcard__row')[2].find('button').trigger('click')

    expect(spy).toHaveBeenCalledTimes(1)
    const [queue, startIndex] = spy.mock.calls[0]
    expect(queue.map((track) => track.songId)).toEqual([1901, 1902])
    expect(startIndex).toBe(1)

    wrapper.unmount()
  })

  it('明确标注音频是测试夹具，不冒充原曲', () => {
    const wrapper = mount(PlayCard, { props: { candidates } })

    expect(wrapper.text()).toContain('测试音')
    expect(wrapper.text()).toContain('不是原曲')
    wrapper.unmount()
  })
})
