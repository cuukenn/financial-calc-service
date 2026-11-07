import { describe, expect, it } from 'vitest'

import { formatMoney, formatNumber, formatPercent } from './format'

describe('format (R90a/R90b numeric display formatters)', () => {
  it('formats money with thousands separators and fixed 2 decimals (R93-2)', () => {
    expect(formatMoney('1032797.15')).toBe('1,032,797.15')
    expect(formatMoney('1032797.1500')).toBe('1,032,797.15')
    expect(formatMoney('5000.00')).toBe('5,000.00')
    expect(formatMoney('0')).toBe('0.00')
    expect(formatMoney('-1234567.891')).toBe('-1,234,567.89')
    // 原样直显（占位/兜底约定），不发明数字。
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney('')).toBe('—')
    expect(formatMoney('not-a-number')).toBe('not-a-number')
  })

  it('formats percents as ×100 with 4 decimals and a % suffix', () => {
    expect(formatPercent('0.061677800455')).toBe('6.1678%')
    expect(formatPercent('0.0049999991')).toBe('0.5000%')
    expect(formatPercent('0')).toBe('0.0000%')
    expect(formatPercent(null)).toBe('—')
    expect(formatPercent('NaN-ish')).toBe('NaN-ish')
  })

  it('formats plain numbers with fixed 4 decimals and no thousands separator', () => {
    expect(formatNumber('0.26841')).toBe('0.2684')
    expect(formatNumber('1.03279715')).toBe('1.0328')
    expect(formatNumber('12')).toBe('12.0000')
    expect(formatNumber(null)).toBe('—')
  })
})
