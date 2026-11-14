import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { VueWrapper } from '@vue/test-utils'
import { beforeAll, describe, expect, it, vi } from 'vitest'

import CalculationForm from '@/components/CalculationForm.vue'
import MortgageForm from '@/components/MortgageForm.vue'
import { mountWithI18n } from '@/test-utils/mount'

// jsdom 无 canvas：共用样式表的 DOM 对账不需要图表，只要挂载时不炸。
vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

/**
 * Task 6 §4：共用样式表是这些规则的唯一出处。
 *
 */

// src/styles/ → src/：共用样式表与几个表单组件的源码都在这一层之下（带尾部分隔符，直接拼接子路径）。
const SRC_ROOT = `${resolve(dirname(fileURLToPath(import.meta.url)), '..')}/`

/** CSS 注释不是声明：注释里写「这条规则已搬去共用样式表」不该被当成重复。 */
function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '')
}

const SHARED_CSS = stripComments(readFileSync(resolve(SRC_ROOT, 'styles/workbench.css'), 'utf8'))
const MAIN_TS = readFileSync(resolve(SRC_ROOT, 'main.ts'), 'utf8')

describe('shared workbench stylesheet (Task 6 §4)', () => {
  it('is imported in main.ts right after the AntD reset', () => {
    const resetAt = MAIN_TS.indexOf('ant-design-vue/dist/reset.css')
    const workbenchAt = MAIN_TS.indexOf('./styles/workbench.css')

    expect(resetAt).toBeGreaterThan(-1)
    expect(workbenchAt, '共用样式表必须在 AntD reset 之后导入').toBeGreaterThan(resetAt)
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

  let lease: VueWrapper
  let mortgage: VueWrapper

  beforeAll(() => {
    lease = mountWithI18n(CalculationForm)
    mortgage = mountWithI18n(MortgageForm)
  })

  const DECLARED_SELECTORS = [...CARD_SELECTORS]

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

})
