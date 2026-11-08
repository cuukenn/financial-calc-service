import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import App from '@/App.vue'
import { SCENARIO_STORAGE_KEY } from '@/scenario'
import { setLocale } from '@/i18n'
import { mountWithI18n } from '@/test-utils/mount'
import { activateTab, paneState } from '@/test-utils/tabs'

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    code: string
    status: number
    constructor(code: string, message: string, status: number) {
      super(message)
      this.code = code
      this.status = status
    }
  },
  calculate: vi.fn(),
}))

// jsdom 无 canvas：ECharts 仅需在挂载时不炸即可。
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

/**
 * Task 6 / §1：Tab 记忆。key 固定 fcs.scenario，取值只有 lease / mortgage，
 * 形态与 i18n 的 fcs.locale 一致（读失败回落默认、写失败吞掉 —— 隐私模式可用）。
 * 面板语义仍是「隐藏不销毁」（R130），所以记忆的是选中态，不是缓存的测算结果。
 */
describe('scenario tab memory (fcs.scenario)', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
    setLocale('zh-CN')
  })

  it('writes the chosen scenario to localStorage under fcs.scenario', async () => {
    const wrapper = mountWithI18n(App)

    await activateTab(wrapper, '房贷计算器')
    expect(localStorage.getItem(SCENARIO_STORAGE_KEY)).toBe('mortgage')

    await activateTab(wrapper, '融资租赁')
    expect(localStorage.getItem(SCENARIO_STORAGE_KEY)).toBe('lease')
  })

  it('restores the stored scenario on a fresh mount', () => {
    localStorage.setItem(SCENARIO_STORAGE_KEY, 'mortgage')
    const wrapper = mountWithI18n(App)

    expect(paneState(wrapper, '[data-test="scenario-mortgage"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="mortgage-form-card"]')).toBe('active')
    // 未访问过的租赁面板仍然缺席（Tabs 懒渲染 —— 记忆不改变懒渲染语义）。
    expect(paneState(wrapper, '[data-test="scenario-lease"]')).toBe('absent')
  })

  it('falls back to 融资租赁 for a stored value that is not one of the two keys', () => {
    localStorage.setItem(SCENARIO_STORAGE_KEY, 'crypto')
    const wrapper = mountWithI18n(App)

    expect(paneState(wrapper, '[data-test="scenario-lease"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="scenario-mortgage"]')).toBe('absent')
  })

  it('falls back to 融资租赁 when localStorage throws on read (private mode)', () => {
    // 只还原本测试自己的 spy：test-setup 装的 window.matchMedia 也是 vi.fn，
    // 全局 restoreAllMocks 会把它的实现抹掉，后续测试会在 AntD Row 里炸。
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage unavailable')
    })
    try {
      const wrapper = mountWithI18n(App)
      expect(spy).toHaveBeenCalledWith(SCENARIO_STORAGE_KEY)
      expect(paneState(wrapper, '[data-test="scenario-lease"]')).toBe('active')
    } finally {
      spy.mockRestore()
    }
  })

  it('keeps tab switching working when localStorage throws on write', async () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage unavailable')
    })
    try {
      const wrapper = mountWithI18n(App)
      await activateTab(wrapper, '房贷计算器')
      expect(paneState(wrapper, '[data-test="scenario-mortgage"]')).toBe('active')
    } finally {
      spy.mockRestore()
    }
  })

  it('does not touch the language preference stored under fcs.locale', async () => {
    setLocale('en-US')
    localStorage.setItem('fcs.locale', 'en-US')

    const wrapper = mountWithI18n(App)
    await activateTab(wrapper, 'Mortgage Calculator')
    await flushPromises()

    expect(localStorage.getItem('fcs.locale')).toBe('en-US')
    expect(localStorage.getItem(SCENARIO_STORAGE_KEY)).toBe('mortgage')
  })
})
