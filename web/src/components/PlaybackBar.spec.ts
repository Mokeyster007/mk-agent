import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PlaybackBar from './PlaybackBar.vue'
import { player } from '@/stores/player'

/**
 * 续播卡的界面契约。
 *
 * 三条：
 * 1. 有快照、没有曲目时才出现——它不是"正在播放"；
 * 2. 打开页面**不自动播放**（只有点击才走 resumeLast）；
 * 3. 显示的是"上次听到哪里"，不是"正在播放"。
 */
describe('播放器续播卡', () => {
  beforeEach(() => {
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined)
    player.stop()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    player.state.resume = null
  })

  function seedResume() {
    player.state.resume = {
      userId: 'music-user-1',
      songId: 1901,
      title: '测试音 A（琶音）',
      artist: 'MkAgent Test Fixtures',
      album: null,
      durationSeconds: 10,
      positionSeconds: 125,
      updatedAt: Date.now() - 60_000,
    }
  }

  it('没有快照也没有曲目：不占位', () => {
    const wrapper = mount(PlaybackBar)
    expect(wrapper.find('.player').exists()).toBe(false)
    wrapper.unmount()
  })

  it('有快照：显示曲目与上次进度，但不上报、不自动播放', () => {
    seedResume()
    const resumeLast = vi.spyOn(player, 'resumeLast').mockResolvedValue(undefined)

    const wrapper = mount(PlaybackBar)

    expect(wrapper.find('.player--resume').exists()).toBe(true)
    expect(wrapper.text()).toContain('测试音 A')
    expect(wrapper.text()).toContain('上次听到 2:05')
    expect(resumeLast).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('点击「继续播放」才走 resumeLast', async () => {
    seedResume()
    const resumeLast = vi.spyOn(player, 'resumeLast').mockResolvedValue(undefined)

    const wrapper = mount(PlaybackBar)
    await wrapper.find('.player__resume-btn').trigger('click')
    await Promise.resolve()

    expect(resumeLast).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})
