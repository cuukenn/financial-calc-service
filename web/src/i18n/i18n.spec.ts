import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ConfigProvider } from 'ant-design-vue'
import App from '@/App.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import i18n, { LOCALE_STORAGE_KEY, setLocale } from './index'
import enUS from './locales/en-US'
import zhCN from './locales/zh-CN'

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

import { mountWithI18n } from '@/test-utils/mount'

describe('i18n (R85)', () => {
  afterEach(() => {
    // 恢复默认 locale，避免测试间串扰（模块级单例）。
    setLocale('zh-CN')
  })

  it('keeps zh-CN and en-US key sets in exact parity (runtime guard for new keys, R85/R87)', () => {
    function leafPaths(node: unknown, prefix: string): string[] {
      if (typeof node !== 'object' || node === null) return [prefix]
      return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) =>
        leafPaths(v, prefix ? `${prefix}.${k}` : k),
      )
    }

    const zhPaths = leafPaths(zhCN, '').sort()
    const enPaths = leafPaths(enUS, '').sort()
    expect(enPaths).toEqual(zhPaths)
    // R87 新键在两种语言中均存在。
    expect(zhPaths).toContain('result.cashflowTab.tableTitle')
    expect(zhPaths).toContain('result.cashflowTab.directions.OUTFLOW')
    expect(zhPaths).toContain('charts.cumulativeCashflow')
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

  it('switches locale via a real dropdown menu item click (overlay wired to Menu)', async () => {
    const wrapper = mountWithI18n(LanguageSwitcher)

    // 回归防线（评审 Critical 1）：Dropdown 只消费 overlay 插槽且自身不发 click；
    // 必须真实打开下拉、点真实菜单项，断言 locale 真正切换。
    await wrapper.find('[data-test="language-switcher-btn"]').trigger('click')
    await flushPromises()

    const items = [...document.querySelectorAll('.ant-dropdown-menu-item')]
    expect(items.length).toBe(2)
    expect(items.map((item) => item.textContent?.trim())).toEqual(['简体中文', 'English'])

    const english = items.find((item) => item.textContent?.includes('English'))
    expect(english, 'English menu item should be rendered in the overlay').toBeTruthy()
    english!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    await flushPromises()

    expect(i18n.global.locale.value).toBe('en-US')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en-US')
    // 切换后触发按钮文案同步为当前语言。
    expect(wrapper.find('[data-test="language-switcher-btn"]').text()).toContain('English')
  })
})
