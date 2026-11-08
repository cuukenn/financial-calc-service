import { describe, expect, it } from 'vitest'

import {
  ALL_DEBT_DTI_LIMIT,
  MIN_DOWN_PAYMENT_RATIO,
  MORTGAGE_DTI_LIMIT,
  allDebtToIncomeRatio,
  belowFloor,
  collateralValue,
  compareDecimal,
  debtToIncomeRatio,
  decimalAdd,
  decimalDivide,
  decimalSubtract,
  downPaymentRatio,
  exceedsLimit,
  formatDecimalPercent,
  isDecimalText,
  loanToValue,
  pickMonthlyPayment,
} from './ratio'

describe('decimalDivide (P-01: decimal-string long division, no float)', () => {
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

describe('collateral value / LTV / 首付比例 (AGENTS.md §2.2)', () => {
  it('takes the lower of 成交价 and 评估价', () => {
    expect(collateralValue('3000000', '2800000')).toBe('2800000')
    expect(collateralValue('2800000', '3000000')).toBe('2800000')
    expect(collateralValue('3000000', '3000000')).toBe('3000000')
    expect(collateralValue('3000000', '')).toBeNull()
  })

  it('computes 贷款成数（LTV）= 贷款金额 ÷ 抵押物价值', () => {
    expect(loanToValue('2380000', '2800000')).toBe('0.85')
    expect(loanToValue('2380280', '2800000')).toBe('0.8501')
    expect(loanToValue('2380000', '0')).toBeNull()
  })

  it('computes 首付比例 = 1 − LTV on exact values', () => {
    expect(downPaymentRatio('2380000', '2800000')).toBe('0.15')
    // 1 - 0.8501 = 0.1499 —— 低于 15% 硬下限，但与本数比较前不先舍入。
    expect(downPaymentRatio('2380280', '2800000')).toBe('0.1499')
    expect(downPaymentRatio('2800000', '2800000')).toBe('0')
  })

  it('applies the 15% floor on unrounded values (equal is not a breach)', () => {
    expect(MIN_DOWN_PAYMENT_RATIO).toBe('0.15')
    expect(belowFloor(downPaymentRatio('2380000', '2800000'), MIN_DOWN_PAYMENT_RATIO)).toBe(false)
    expect(belowFloor(downPaymentRatio('2380280', '2800000'), MIN_DOWN_PAYMENT_RATIO)).toBe(true)
    expect(belowFloor(null, MIN_DOWN_PAYMENT_RATIO)).toBe(false)
  })
})

describe('DTI ratios (AGENTS.md §2.2: 50% / 60%)', () => {
  it('computes 月房贷支出/收入比 = 月供 ÷ 月可支配收入', () => {
    expect(debtToIncomeRatio('8000', '16000')).toBe('0.5')
    expect(debtToIncomeRatio('8001.6', '16000')).toBe('0.5001')
  })

  it('computes 月所有债务支出/收入比 = (月供 + 其他月债务支出) ÷ 月可支配收入', () => {
    expect(allDebtToIncomeRatio('8000', '1600', '16000')).toBe('0.6')
    expect(allDebtToIncomeRatio('8000', '1600.01', '16000')).toBe('0.600000625')
  })

  it('treats a limit equal to the threshold as compliant and anything above as a breach', () => {
    expect(MORTGAGE_DTI_LIMIT).toBe('0.5')
    expect(ALL_DEBT_DTI_LIMIT).toBe('0.6')

    expect(exceedsLimit(debtToIncomeRatio('8000', '16000'), MORTGAGE_DTI_LIMIT)).toBe(false)
    expect(exceedsLimit(debtToIncomeRatio('8001.6', '16000'), MORTGAGE_DTI_LIMIT)).toBe(true)
    expect(exceedsLimit(allDebtToIncomeRatio('8000', '1600', '16000'), ALL_DEBT_DTI_LIMIT)).toBe(
      false,
    )
    expect(
      exceedsLimit(allDebtToIncomeRatio('8000', '1600.01', '16000'), ALL_DEBT_DTI_LIMIT),
    ).toBe(true)
    expect(exceedsLimit(null, MORTGAGE_DTI_LIMIT)).toBe(false)
  })

  it('returns null (not 0) when income is zero, negative or missing', () => {
    expect(debtToIncomeRatio('8000', '0')).toBeNull()
    expect(debtToIncomeRatio('8000', '-1')).toBeNull()
    expect(debtToIncomeRatio('8000', '')).toBeNull()
    expect(allDebtToIncomeRatio('8000', '100', '0')).toBeNull()
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
