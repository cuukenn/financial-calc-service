import { describe, expect, it } from 'vitest'

import {
  BULLET_MAX_MONTHS,
  MAX_LOAN_MONTHS,
  buildMortgageRules,
  isAmountText,
  isBulletRepaymentAllowed,
  isLoanTermMonths,
  isRepaymentAfterDisbursement,
  isSpreadStepAligned,
} from './mortgageRules'
import type { Translate } from './mortgageRules'

describe('client-side caps (AGENTS.md §2.3, R107/R109)', () => {
  it('pins the UI-only caps the engine does not enforce', () => {
    // 房贷期限 ≤ 40 年 = 480 个月；引擎上限仍是 LeaseElement.MAX_PERIODS = 1200。
    expect(MAX_LOAN_MONTHS).toBe(480)
    // 期限 > 1 年须按月还本付息 → 到期一次还本付息只在 ≤ 12 个月可选。
    expect(BULLET_MAX_MONTHS).toBe(12)
  })
})

describe('isLoanTermMonths (R107: 481 个月不得越过前端上限)', () => {
  it('accepts integers within 1–480', () => {
    expect(isLoanTermMonths(1)).toBe(true)
    expect(isLoanTermMonths(12)).toBe(true)
    expect(isLoanTermMonths(360)).toBe(true)
    expect(isLoanTermMonths(480)).toBe(true)
  })

  it('rejects 481 and beyond, non-integers, non-positive values and missing values', () => {
    expect(isLoanTermMonths(481)).toBe(false)
    expect(isLoanTermMonths(1200)).toBe(false)
    expect(isLoanTermMonths(0)).toBe(false)
    expect(isLoanTermMonths(-1)).toBe(false)
    expect(isLoanTermMonths(12.5)).toBe(false)
    expect(isLoanTermMonths(null)).toBe(false)
    // InputNumber 绑定的是 number；字符串不是合法期限值。
    expect(isLoanTermMonths('360')).toBe(false)
  })
})

describe('isBulletRepaymentAllowed (R109: 到期一次还本付息 only for 期限 ≤ 12 月)', () => {
  it('allows 1–12 months and refuses 13 months', () => {
    expect(isBulletRepaymentAllowed(1)).toBe(true)
    expect(isBulletRepaymentAllowed(12)).toBe(true)
    expect(isBulletRepaymentAllowed(13)).toBe(false)
    expect(isBulletRepaymentAllowed(360)).toBe(false)
    expect(isBulletRepaymentAllowed(null)).toBe(false)
    expect(isBulletRepaymentAllowed(0)).toBe(false)
  })
})

describe('isAmountText (P-01/P-05: decimal money text, no float artefacts)', () => {
  it('accepts plain decimal amounts and leaves presence to the required rule', () => {
    expect(isAmountText('2380000')).toBe(true)
    expect(isAmountText('2380000.01')).toBe(true)
    expect(isAmountText('3.5')).toBe(true)
    expect(isAmountText('0')).toBe(true)
    expect(isAmountText('')).toBe(true)
    expect(isAmountText(undefined)).toBe(true)
  })

  it('refuses anything that is not a decimal literal instead of coercing it', () => {
    expect(isAmountText('-1')).toBe(false)
    expect(isAmountText('1e5')).toBe(false)
    expect(isAmountText('1,000')).toBe(false)
    expect(isAmountText('1 000')).toBe(false)
    expect(isAmountText('abc')).toBe(false)
    expect(isAmountText(1000)).toBe(false)
  })
})

describe('isSpreadStepAligned (P-04: LPR 报价步长 0.05 个百分点 = 5bp)', () => {
  it('accepts integer multiples of 5bp and refuses a 12bp input', () => {
    expect(isSpreadStepAligned('0')).toBe(true)
    expect(isSpreadStepAligned('35')).toBe(true)
    expect(isSpreadStepAligned('-30')).toBe(true)
    expect(isSpreadStepAligned('12')).toBe(false)
    expect(isSpreadStepAligned('2.5')).toBe(false)
    expect(isSpreadStepAligned('')).toBe(false)
    expect(isSpreadStepAligned(undefined)).toBe(false)
  })
})

describe('isRepaymentAfterDisbursement (引擎拒绝首期还款日早于放款日)', () => {
  it('accepts the same day and a later day, refuses an earlier day', () => {
    expect(isRepaymentAfterDisbursement('2026-11-01', '2026-12-01')).toBe(true)
    expect(isRepaymentAfterDisbursement('2026-11-01', '2026-11-01')).toBe(true)
    expect(isRepaymentAfterDisbursement('2026-11-01', '2026-10-31')).toBe(false)
  })

  it('leaves empty dates to the required rule', () => {
    expect(isRepaymentAfterDisbursement('2026-11-01', '')).toBe(true)
    expect(isRepaymentAfterDisbursement('', '2026-12-01')).toBe(true)
  })
})

describe('buildMortgageRules (validators reject on raw values, never silently fix them)', () => {
  // 翻译函数在此只用于证明「消息与插值参数走 i18n」，不测文案本身。
  const translate: Translate = (key, named) => `${key}:${JSON.stringify(named ?? {})}`
  const rules = buildMortgageRules(translate, { commenceDate: '2026-11-01' })

  /** 取字段的第 index 条规则并直接跑它的 validator。 */
  async function runValidator(name: string, index: number, value: unknown): Promise<string | null> {
    const rule = rules[name][index]
    try {
      await rule.validator!(rule, value, () => undefined)
      return null
    } catch (error) {
      return (error as Error).message
    }
  }

  it('names exactly the fields the mortgage form renders', () => {
    expect(Object.keys(rules).sort()).toEqual([
      'appraisalValue',
      'commenceDate',
      'firstRepaymentDate',
      'lprPercent',
      'monthlyIncome',
      'otherMonthlyDebt',
      'periods',
      'principal',
      'spreadBp',
      'transactionPrice',
    ])
  })

  it('rejects 481 months and accepts 480, naming the cap in the message', async () => {
    expect(await runValidator('periods', 1, 481)).toContain(String(MAX_LOAN_MONTHS))
    expect(await runValidator('periods', 1, 480)).toBeNull()
    // 粘贴/程序性写入的越界期数同样被拦住（不依赖 InputNumber 的 max）。
    expect(await runValidator('periods', 1, null)).toContain(String(MAX_LOAN_MONTHS))
  })

  it('rejects a 12bp 加点 instead of rounding it to the 5bp step', async () => {
    expect(await runValidator('spreadBp', 0, '12')).toContain('mortgage.validation.spreadStep')
    expect(await runValidator('spreadBp', 0, '15')).toBeNull()
    expect(await runValidator('spreadBp', 0, '')).toContain('mortgage.validation.spread')
  })

  it('rejects a first repayment date earlier than the disbursement date', async () => {
    expect(await runValidator('firstRepaymentDate', 0, '2026-10-31')).toContain(
      'mortgage.validation.firstRepaymentBeforeCommence',
    )
    expect(await runValidator('firstRepaymentDate', 0, '2026-12-01')).toBeNull()
    expect(await runValidator('firstRepaymentDate', 0, '')).toContain(
      'mortgage.validation.firstRepaymentDate',
    )
  })

  it('rejects a malformed amount and leaves the empty case to the required rule', async () => {
    expect(await runValidator('principal', 1, '1,000,000')).toContain('mortgage.validation.money')
    expect(await runValidator('principal', 1, '1000000')).toBeNull()
    expect(await runValidator('monthlyIncome', 0, '1e5')).toContain('mortgage.validation.money')
  })
})
