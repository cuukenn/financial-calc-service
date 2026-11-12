import { describe, expect, it } from 'vitest'

import { STAMP_DUTY_DIVISOR, STAMP_DUTY_RATE, stampDuty } from './stampDuty'

/**
 * 印花税（AGENTS.md §4）：计税依据 = Σ租金 − 单列增值税，税率 0.005%。
 *
 * 本模块返回**规范化十进制串**（`utils/ratio.ts` 的 canonical：小数尾随零去掉），
 * 2 位展示由 `utils/format.ts` 的 formatMoney 负责，所以这里断言的是数值本身。
 */
describe('stampDuty (R149 合同税费，不进摊销表 / 不进 IRR)', () => {
  it('钉住黄金基线：TOTAL_RENT 1,032,797.15 → 51.64 元', () => {
    const duty = stampDuty({ TOTAL_RENT: '1032797.15', TOTAL_TAX: '0.00' })
    // 手算：1 032 797.15 × 0.00005 = 51.6398575 → 51.64（分以下四舍五入）。
    expect(duty).not.toBeNull()
    expect(duty?.base).toBe('1032797.15')
    expect(duty?.amount).toBe('51.64')
  })

  it('计税依据不含单列增值税（P-12 价税分离的税列要减掉）', () => {
    // 租金总额 106 000.02 里含 6 000.02 的税：依据 100 000.00，税额 5.00。
    const duty = stampDuty({ TOTAL_RENT: '106000.02', TOTAL_TAX: '6000.02' })
    expect(duty?.base).toBe('100000')
    expect(duty?.amount).toBe('5')
  })

  it('税列缺失按 0 处理：v1 不做价税分离，Σ租金本身就是依据', () => {
    expect(stampDuty({ TOTAL_RENT: '1032797.15' })?.base).toBe('1032797.15')
    expect(stampDuty({ TOTAL_RENT: '1032797.15', TOTAL_TAX: null })?.amount).toBe('51.64')
  })

  it('分以下 HALF_UP：半分进位，不截断', () => {
    // 100.00 × 0.00005 = 0.005 元 —— 恰好半分，进位成 0.01。
    expect(stampDuty({ TOTAL_RENT: '100' })?.amount).toBe('0.01')
    expect(stampDuty({ TOTAL_RENT: '99' })?.amount).toBe('0')
  })

  it('租金总额缺失或不可解析 → 不可计算（null），不是 0', () => {
    expect(stampDuty({ TOTAL_RENT: null })).toBeNull()
    expect(stampDuty({ TOTAL_TAX: '0' })).toBeNull()
    expect(stampDuty({ TOTAL_RENT: '1,032,797.15' })).toBeNull()
  })

  it('税率与其除法形态同源', () => {
    // 0.00005 = 1 / 20000：模块里只允许用除法，两者必须仍是同一个数。
    expect(STAMP_DUTY_RATE).toBe('0.00005')
    expect(STAMP_DUTY_DIVISOR).toBe('20000')
  })
})
