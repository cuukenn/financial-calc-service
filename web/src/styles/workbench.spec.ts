import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { VueWrapper } from '@vue/test-utils'
import { beforeAll, describe, expect, it, vi } from 'vitest'

import CalculationForm from '@/components/CalculationForm.vue'
import MortgageForm from '@/components/MortgageForm.vue'
import { RATIO_MARKER_KEYS, statusClass, type RatioStatus } from '@/components/ratioStatus'
import enUS from '@/i18n/locales/en-US'
import zhCN from '@/i18n/locales/zh-CN'
import { mountWithI18n } from '@/test-utils/mount'

// jsdom 无 canvas：共用样式表的 DOM 对账不需要图表，只要挂载时不炸。
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

/**
 * Task 6 §4：共用样式表是这些规则的唯一出处。
 *
 * Task 5 评审点出的重复（两套 `.block-card` / `.ratio-list`，各带一份 dt/dd 基线与
 * 数字右对齐）已经搬进 `styles/workbench.css`；这里守卫的是「只定义一次」这条硬要求，
 * 防止以后又在某个组件的 `<style>` 块里长出第二份。断言的是规则的归属，不是像素值
 * （配色与栅格长什么样由 Task 7 的浏览器走查看）。
 */

// src/styles/ → src/：共用样式表与几个表单组件的源码都在这一层之下（带尾部分隔符，直接拼接子路径）。
const SRC_ROOT = `${resolve(dirname(fileURLToPath(import.meta.url)), '..')}/`

/** CSS 注释不是声明：注释里写「这条规则已搬去共用样式表」不该被当成重复。 */
function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '')
}

const SHARED_CSS = stripComments(readFileSync(resolve(SRC_ROOT, 'styles/workbench.css'), 'utf8'))
const MAIN_TS = readFileSync(resolve(SRC_ROOT, 'main.ts'), 'utf8')

/** 搬移范围内的那几个组件：共用规则不得再出现在它们的 `<style>` 块里。 */
const FORM_COMPONENTS = [
  'components/CalculationForm.vue',
  'components/MortgageForm.vue',
  'components/MortgageIncomePanel.vue',
  'components/ResultPanel.vue',
  // D-6：结果区按 pane 拆分后的子组件同样受「共享样式只声明一次」的约束。
  'components/result/MetricsPane.vue',
  'components/result/SchedulePane.vue',
  'components/result/CashflowPane.vue',
  'components/result/EirPane.vue',
]

/** 组件自己的 `<style>` 内容（没有则为空串）。 */
function scopedStyle(file: string): string {
  const match = readFileSync(`${SRC_ROOT}${file}`, 'utf8').match(/<style[^>]*>([\s\S]*?)<\/style>/)
  return stripComments(match?.[1] ?? '')
}

/** 整条规则恰好只有这一个类名时才算一次声明（`.ratio-marker.ratio-ok {` 不算）。 */
function declarations(css: string, className: string): number {
  return (css.match(new RegExp(`(?:^|[,{}\\n])\\s*\\.${className}\\s*\\{`, 'gm')) ?? []).length
}

/** 按点分路径取语言包里的文案叶子（与 i18n.spec 的键位对账同源，只是这里只取一条链）。 */
function messageAt(bundle: Record<string, unknown>, key: string): string | undefined {
  const leaf = key.split('.').reduce<unknown>(
    (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
    bundle,
  )
  return typeof leaf === 'string' ? leaf : undefined
}

describe('shared workbench stylesheet (Task 6 §4)', () => {
  it('is imported in main.ts right after the AntD reset', () => {
    const resetAt = MAIN_TS.indexOf('ant-design-vue/dist/reset.css')
    const workbenchAt = MAIN_TS.indexOf('./styles/workbench.css')

    expect(resetAt).toBeGreaterThan(-1)
    expect(workbenchAt, '共用样式表必须在 AntD reset 之后导入').toBeGreaterThan(resetAt)
  })

  it.each(['.block-card', '.compact-form', '.ratio-list', '.ratio-item', '.ratio-readout'])(
    'declares %s in the shared sheet and never inside a component style block',
    (selector) => {
      const bare = selector.replace(/^\./, '')

      expect(SHARED_CSS, `${selector} 必须在共用样式表里出现`).toContain(selector)
      for (const file of FORM_COMPONENTS) {
        expect(scopedStyle(file), `${file} 的样式块不得再自带 ${selector}`).not.toContain(bare)
      }
    },
  )

  it('declares each threshold status class exactly once, and only in the shared sheet', () => {
    const statuses: RatioStatus[] = ['ok', 'warn', 'breach', 'neutral']

    for (const status of statuses) {
      const className = statusClass(status)

      expect(declarations(SHARED_CSS, className), `${className} 必须恰好声明一次`).toBe(1)
      // 颜色只来自这里定义的自定义属性：组件的样式块不得自带阈值配色。
      expect(SHARED_CSS, `${className} 的颜色来自 --fcs-ratio-${status}`).toContain(
        `--fcs-ratio-${status}:`,
      )
      for (const file of FORM_COMPONENTS) {
        expect(scopedStyle(file), `${file} 不得自带阈值配色`).not.toContain(className)
        expect(scopedStyle(file), `${file} 不得自带阈值色值`).not.toContain('--fcs-ratio')
      }
    }
  })

  it('keeps color literals out of the components it covers: colors come from the shared tokens', () => {
    for (const file of FORM_COMPONENTS) {
      const css = scopedStyle(file)
      expect(css, `${file} 的样式块不得再落 hex 色值`).not.toMatch(/#[0-9a-fA-F]{3,6}\b/)
      expect(css, `${file} 的样式块不得再落 rgba 色值`).not.toMatch(/rgba\(/)
    }
  })

  it('pairs every status with an i18n marker key present in both locales', () => {
    // 语义不只靠颜色传达：每个状态都要有中英文两条文案标记。
    const markerKeys = [...Object.values(RATIO_MARKER_KEYS), 'mortgage.status.belowFloor']

    for (const bundle of [zhCN, enUS]) {
      for (const key of markerKeys) {
        const text = messageAt(bundle, key)
        expect(text, `${key} 必须在两种语言里都存在`).toBeTruthy()
        expect(typeof text).toBe('string')
      }
    }
  })

  it('documents one percent formatter per concern (Task 6 §5)', () => {    const formatTs = readFileSync(`${SRC_ROOT}utils/format.ts`, 'utf8')
    const ratioTs = readFileSync(`${SRC_ROOT}utils/ratio.ts`, 'utf8')

    // format.ts 里唯一的百分数格式化器是利率类的 formatPercent；
    // 阈值读数的 2 位百分数（P-10 安全的那一个）住在 ratio.ts，文档必须指过去。
    expect(ratioTs).toContain('export function formatDecimalPercent')
    expect(formatTs.match(/export function format\w*Percent/g), 'format.ts 只能有一个百分数格式化器')
      .toHaveLength(1)
    expect(formatTs, 'format.ts 必须把阈值百分数的归属写清楚').toContain('formatDecimalPercent')
    expect(formatTs, '指针指向 P-10 的判限约定').toContain('P-10')
  })
})

/**
 * 选不中的规则等于没有规则：AntD 的 DOM 不是我们凭印象写的那个形状
 * （卡头是 head > head-wrapper > head-title，直接子代组合符选不到标题），
 * 所以共用样式表依赖的每条选择器都要在真实挂载结果上命中。
 */
describe('shared selectors reach the real AntD DOM (Task 6 §4)', () => {
  const CARD_SELECTORS = [
    '.block-card.ant-card > .ant-card-head',
    // 后代组合符：AntD 把标题嵌在 head-wrapper 里。
    '.block-card.ant-card > .ant-card-head .ant-card-head-title',
    '.block-card.ant-card > .ant-card-body',
    '.compact-form.ant-form .ant-form-item',
  ]
  const RATIO_SELECTORS = ['.ratio-item dt', '.ratio-item dd', '.ratio-item dd.ratio-readout']

  /** 必须写在共用样式表里的那些选择器（`.ratio-item dd.ratio-readout` 只是 DOM 探针：
   *  阈值 dd 的配色由单独立着的 `.ratio-readout` 承担，不需要再叠一条）。 */
  const DECLARED_SELECTORS = [
    ...CARD_SELECTORS,
    '.ratio-list',
    '.ratio-item dt',
    '.ratio-item dd',
    '.ratio-readout',
  ]

  let lease: VueWrapper
  let mortgage: VueWrapper

  beforeAll(() => {
    lease = mountWithI18n(CalculationForm)
    mortgage = mountWithI18n(MortgageForm)
  })

  it('declares every selector it relies on', () => {
    for (const selector of DECLARED_SELECTORS) {
      expect(SHARED_CSS, `共用样式表必须声明 ${selector}`).toContain(selector)
    }
  })

  it('hits the block cards and the compact grid in both scenario forms', () => {
    for (const [name, wrapper] of [['租赁', lease], ['房贷', mortgage]] as const) {
      for (const selector of CARD_SELECTORS) {
        expect(
          wrapper.element.querySelectorAll(selector).length,
          `${name}表单：${selector} 必须命中`,
        ).toBeGreaterThan(0)
      }
    }
  })

  it('hits the dl/dt/dd readouts and the threshold-carrying dd in the mortgage form', () => {
    for (const selector of RATIO_SELECTORS) {
      expect(
        mortgage.element.querySelectorAll(selector).length,
        `房贷表单：${selector} 必须命中`,
      ).toBeGreaterThan(0)
    }
  })
})
