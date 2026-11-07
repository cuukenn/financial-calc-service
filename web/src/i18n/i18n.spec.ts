import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ConfigProvider } from 'ant-design-vue'

import App from '@/App.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import i18n, { LOCALE_STORAGE_KEY, setLocale } from './index'

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
  ping: vi.fn().mockResolvedValue({ status: 'UP', engine: 'ready' }),
  getAlgorithms: vi.fn().mockResolvedValue([]),
  calculate: vi.fn(),
}))

// jsdom 无 canvas：ECharts 仅需在挂载时不炸即可。
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

import { Dropdown } from 'ant-design-vue'
import { mountWithI18n } from '@/test-utils/mount'

describe('i18n (R85)', () => {
  afterEach(() => {
    // 恢复默认 locale，避免测试间串扰（模块级单例）。
    setLocale('zh-CN')
  })

  it('defaults to zh-CN and persists a switch to localStorage', () => {
    expect(i18n.global.locale.value).toBe('zh-CN')

    setLocale('en-US')
    expect(i18n.global.locale.value).toBe('en-US')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en-US')

    setLocale('zh-CN')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-CN')
  })

  it('re-renders UI text reactively from zh-CN to en-US and back', async () => {
    const wrapper = mountWithI18n(App)

    // zh-CN 默认：页头标题 / 表单卡 / 页脚均为中文。
    expect(wrapper.text()).toContain('租赁测算工作台')
    expect(wrapper.text()).toContain('测算表单')
    expect(wrapper.text()).toContain('租赁本金')

    setLocale('en-US')
    await flushPromises()

    expect(wrapper.text()).toContain('Lease Calculation Workbench')
    expect(wrapper.text()).toContain('Calculation Form')
    expect(wrapper.text()).toContain('Lease principal')
    expect(wrapper.text()).not.toContain('测算表单')

    // AntD ConfigProvider locale 随语言响应式切换（zh_CN ↔ en_US import swap）。
    const provider = wrapper.findComponent(ConfigProvider)
    expect(provider.props('locale')).toMatchObject({ locale: 'en' })

    setLocale('zh-CN')
    await flushPromises()
    expect(wrapper.text()).toContain('测算表单')
    expect(provider.props('locale')).toMatchObject({ locale: 'zh-cn' })
  })

  it('wires the language switcher: a menu click switches locale and persists', async () => {
    const wrapper = mountWithI18n(LanguageSwitcher)

    const dropdown = wrapper.findComponent(Dropdown)
    expect(dropdown.exists()).toBe(true)
    const menu = dropdown.props('menu') as { items: { key: string; label: string }[] }
    expect(menu.items.map((item) => item.key)).toEqual(['zh-CN', 'en-US'])

    // 触发 Dropdown 的 click 事件（父组件 onMenuClick 处理）。
    dropdown.vm.$emit('click', { key: 'en-US' })
    await flushPromises()

    expect(i18n.global.locale.value).toBe('en-US')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en-US')
    expect(wrapper.find('[data-test="language-switcher-btn"]').text()).toContain('English')
  })
})
