import { mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import type { Component, Plugin } from 'vue'

import i18n from '@/i18n'

/**
 * 挂载辅助：注入全局 i18n 插件（R85）。
 * 默认 locale 为 zh-CN，既有中文文案断言不变；语言切换测试用 setLocale 切换。
 */
export function mountWithI18n(
  component: Component,
  options: { props?: Record<string, unknown>; global?: { plugins?: Plugin[] } } = {},
): VueWrapper {
  const { props, global } = options
  return mount(component, {
    props,
    global: { plugins: [i18n, ...(global?.plugins ?? [])] },
  })
}
