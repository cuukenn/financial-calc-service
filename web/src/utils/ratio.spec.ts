import { describe, expect, it } from 'vitest'

import {
  compareDecimal,
  decimalAdd,
  decimalDivide,
  decimalSubtract,
  formatDecimalPercent,
  isDecimalText,
  pickMonthlyPayment,
} from './ratio'

describe('decimalDivide (decimal-string long division, no float)', () => {
  it('divides exactly at the requested scale', () => {
    expect(decimalDivide('2380000', '2800000', 10)).toBe('0.85')
    expect(decimalDivide('2800000', '2800000', 10)).toBe('1')
    expect(decimalDivide('0', '2800000', 10)).toBe('0')
  })

  it('never leaks a binary-float artefact', () => {
    // Number 路径下 1/3 会是 0.3333333333333333；0.1/0.2 会是 0.5000000000000001。
    expect(decimalDivide('1', '3', 10)).toBe('0.3333333333')
    expect(decimalDivide('0.1', '0.2', 10)).toBe('0.5')
    expect(decimalDivide('2', '3', 4)).toBe('0.6667')
  })

  it('rounds half up and keeps the sign', () => {
    expect(decimalDivide('-1', '3', 4)).toBe('-0.3333')
    expect(decimalDivide('1', '-3', 4)).toBe('-0.3333')
    expect(decimalDivide('-2', '-3', 4)).toBe('0.6667')
  })

  it('returns null on a zero denominator or unparseable input', () => {
    expect(decimalDivide('1', '0', 10)).toBeNull()
    expect(decimalDivide('1', '0.000', 10)).toBeNull()
    expect(decimalDivide('abc', '1', 10)).toBeNull()
    expect(decimalDivide('1', '', 10)).toBeNull()
  })
})

describe('compareDecimal (P-10: compare unrounded values)', () => {
  it('orders decimal strings without float coercion', () => {
    expect(compareDecimal('0.85', '0.85')).toBe(0)
    expect(compareDecimal('0.8501', '0.85')).toBe(1)
    expect(compareDecimal('0.1499', '0.15')).toBe(-1)
    expect(compareDecimal('1', '1.000')).toBe(0)
    expect(compareDecimal('-0.5', '0.5')).toBe(-1)
    expect(compareDecimal('-0', '0')).toBe(0)
  })

  it('throws on unparseable input instead of returning a silent 0', () => {
    expect(() => compareDecimal('abc', '1')).toThrow(/unparseable/)
  })
})

describe('isDecimalText (可解析与否：决定哪条规则负责文案)', () => {
  it('accepts any signed decimal literal and refuses everything else', () => {
    expect(isDecimalText('2380000')).toBe(true)
    expect(isDecimalText('3.5')).toBe(true)
    expect(isDecimalText('0')).toBe(true)
    expect(isDecimalText('-1')).toBe(true)
    expect(isDecimalText('1,000')).toBe(false)
    expect(isDecimalText('1e5')).toBe(false)
    expect(isDecimalText('')).toBe(false)
  })
})

describe('decimalAdd / decimalSubtract', () => {
  it('adds without float artefacts', () => {
    expect(decimalAdd('0.1', '0.2')).toBe('0.3')
    expect(decimalAdd('0.035', '-0.003')).toBe('0.032')
    expect(decimalAdd('8000', '1600.01')).toBe('9600.01')
    expect(decimalSubtract('1', '0.8501')).toBe('0.1499')
  })

  it('returns null on unparseable input', () => {
    expect(decimalAdd('1', 'x')).toBeNull()
  })
})

describe('formatDecimalPercent (P-10: 2 decimal places for ratio-type metrics)', () => {
  it('renders two decimals by default', () => {
    expect(formatDecimalPercent('0.85')).toBe('85.00%')
    expect(formatDecimalPercent('0.15')).toBe('15.00%')
    expect(formatDecimalPercent('0.1499')).toBe('14.99%')
    expect(formatDecimalPercent('0.5')).toBe('50.00%')
    expect(formatDecimalPercent('0.5001')).toBe('50.01%')
    expect(formatDecimalPercent('0.032')).toBe('3.20%')
  })

  it('renders a placeholder for null / unparseable values', () => {
    expect(formatDecimalPercent(null)).toBe('—')
    expect(formatDecimalPercent('abc')).toBe('—')
  })
})

describe('pickMonthlyPayment (worst-case month for non-annuity methods)', () => {
  it('uses the first installment for 等额本息', () => {
    expect(pickMonthlyPayment(['8000.00', '8100.00'], 'EQUAL_INSTALLMENT')).toBe('8000')
  })

  it('uses the largest installment for 等额本金 / 到期一次还本付息', () => {
    expect(pickMonthlyPayment(['8000.00', '8100.00'], 'EQUAL_PRINCIPAL')).toBe('8100')
    expect(pickMonthlyPayment(['8000.00', '1008000.00'], 'BULLET_LOAN')).toBe('1008000')
  })

  it('returns null for an empty or unparseable schedule', () => {
    expect(pickMonthlyPayment([], 'EQUAL_INSTALLMENT')).toBeNull()
    expect(pickMonthlyPayment(['abc'], 'EQUAL_PRINCIPAL')).toBeNull()
  })
})
