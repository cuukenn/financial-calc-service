/**
 * vue-i18n v9 接入（R85）：zh-CN 默认 + en-US，Composition API（legacy: false），
 * 语言偏好持久化到 localStorage（key: fcs.locale）。
 */
import { createI18n } from 'vue-i18n'

import enUS from './locales/en-US'
import zhCN from './locales/zh-CN'

export type AppLocale = 'zh-CN' | 'en-US'

export const LOCALE_STORAGE_KEY = 'fcs.locale'

function initialLocale(): AppLocale {
  try {
    return localStorage.getItem(LOCALE_STORAGE_KEY) === 'en-US' ? 'en-US' : 'zh-CN'
  } catch {
    return 'zh-CN'
  }
}

const i18n = createI18n({
  legacy: false,
  locale: initialLocale(),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

/** 切换语言并持久化（语言切换器与测试共用）。 */
export function setLocale(locale: AppLocale): void {
  i18n.global.locale.value = locale
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    // localStorage 不可用（如隐私模式）时仅切换本次会话语言。
  }
}

export default i18n
