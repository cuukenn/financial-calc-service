import { describe, expect, it } from 'vitest'

import type { Installment } from '@/api/types'
import { compareDecimal, decimalAdd } from '@/utils/ratio'

import { quoteEarlySettlement } from './earlySettlement'

/**
 * 合成 12 期摊销表：本金 1,000,000，每期还本 80,000，期利率 0.005（THIRTY_360 月频），
 * 每期利息 = 期初余额 × 0.005。日期为连续月初，THIRTY_360 每期 30 天。
 * 注：金额断言用 canonical 十进制字符串（decimal 工具去尾零），展示层的 2 位补齐在
 * formatMoney —— 这与后端「金额为字符串小数」的契约同构。
 */
function schedule(): Installment[] {
  const rows: Installment[] = []
  for (let i = 1; i <= 12; i++) {
    const opening = 1_000_000 - 80_000 * (i - 1)
    const month = String(i).padStart(2, '0')
    const endMonth = String(i === 12 ? 1 : i + 1).padStart(2, '0')
    const endYear = i === 12 ? '2025' : '2024'
    rows.push({
      periodNo: i,
      startDate: `2024-${month}-01`,
      endDate: `${endYear}-${endMonth}-01`,
      days: 30,
      openingBalance: `${opening}.00`,
      principalDue: '80000.00',
      interestAccrued: `${(opening * 0.005).toFixed(2)}`,
      interestPaid: `${(opening * 0.005).toFixed(2)}`,
      fee: '0.00',
      tax: '0.00',
      rentDue: `${(opening * 0.005 + 80_000).toFixed(2)}`,
      closingBalance: `${opening - 80_000}.00`,
      cumulativePrincipal: `${80_000 * i}.00`,
      cumulativeInterest: '0.00',
      appliedRate: '0.005',
      memo: null,
    })
  }
  return rows
}

describe('quoteEarlySettlement', () => {
  it('splits a mid-period settlement on the period rate basis (算头不算尾)', () => {
    // 结清日 2024-06-16：承载期 = 第 6 期（期初 600,000），30/360 天数 = 15
    // （含 1 日、不含 16 日）。
    const quote = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-06-16',
      penaltyMode: 'RATE',
      penaltyRate: '0',
    }, 'THIRTY_360')

    expect(quote).not.toBeNull()
    expect(quote!.periodNo).toBe(6)
    expect(quote!.remainingPrincipal).toBe('600000.00')
    expect(quote!.accruedDays).toBe(15)
    // 600,000 × 0.005 × 15 ÷ 30 = 1,500.00（整期利息 3,000.00 的一半，可对账）。
    expect(quote!.accruedInterest).toBe('1500')
    expect(quote!.periodInterest).toBe('3000.00')
    expect(quote!.settlementAmount).toBe('601500')
  })

  it('matches the period interest bit-for-bit at the payment date (d = 期天数)', () => {
    const quote = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-07-01',
      penaltyMode: 'RATE',
      penaltyRate: '0',
    }, 'THIRTY_360')

    // 结清日恰在第 6 期期末（= 第 7 期期初）：归属第 6 期、计整期 —— 该期本息都到偿还日了。
    expect(quote!.periodNo).toBe(6)
    expect(quote!.accruedDays).toBe(30)
    expect(compareDecimal(quote!.accruedInterest, quote!.periodInterest)).toBe(0)
  })

  it('counts zero interest only at the first period start', () => {
    const quote = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-01-01',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'THIRTY_360')

    expect(quote!.periodNo).toBe(1)
    expect(quote!.accruedDays).toBe(0)
    expect(quote!.accruedInterest).toBe('0')
    // canonical 字符串去尾零；展示层 formatMoney 补足 2 位（P-05）。
    expect(compareDecimal(quote!.settlementAmount, '1000000.00')).toBe(0)
  })

  it('maturity-date settlement pays the last principal and interest', () => {
    const quote = quoteEarlySettlement(schedule(), {
      settlementDate: '2025-01-01',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'THIRTY_360')

    expect(quote!.periodNo).toBe(12)
    expect(quote!.remainingPrincipal).toBe('120000.00')
    expect(quote!.accruedInterest).toBe('600')
    expect(quote!.settlementAmount).toBe('120600')
    expect(quote!.penalty).toBe('0')
  })

  it('adds a RATE penalty on the remaining principal and an AMOUNT penalty verbatim', () => {
    const rate = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-06-16',
      penaltyMode: 'RATE',
      penaltyRate: '0.01',
    }, 'THIRTY_360')
    expect(rate!.penalty).toBe('6000')
    expect(rate!.settlementAmount).toBe('607500')

    const amount = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-06-16',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '1250.5',
    }, 'THIRTY_360')
    expect(amount!.penalty).toBe('1250.5')
    expect(amount!.settlementAmount).toBe('602750.5')
  })

  it('charges overdue penalty at principal × rate × days ÷ 360 (P-03)', () => {
    const quote = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-06-16',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
      overdueDays: 30,
      penaltyInterestRate: '0.09',
    }, 'THIRTY_360')

    // 600,000 × 0.09 × 30 ÷ 360 = 4,500.00。
    expect(quote!.overduePenalty).toBe('4500')
    expect(quote!.settlementAmount).toBe('606000')
  })

  it('computes saved interest as the remaining interest minus the accrued part', () => {
    const quote = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-06-16',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'THIRTY_360')

    // 剩余期数 = 第 6..12 期共 7 期；剩余利息（3000, 2600, 2200, 1800, 1400, 1000, 600）
    // 合计 12,600；节省 = 12,600 − 应计 1,500 = 11,100。
    expect(quote!.remainingPeriods).toBe(7)
    let remaining = '0'
    for (let i = 6; i <= 12; i++) {
      const opening = 1_000_000 - 80_000 * (i - 1)
      // 整数运算构造期望值（opening×5÷1000 精确），不走浮点乘 0.005。
      remaining = decimalAdd(remaining, `${(opening * 5) / 1000}`)!
    }
    expect(compareDecimal(remaining, '12600')).toBe(0)
    expect(compareDecimal(quote!.savedInterest, '11100')).toBe(0)
  })

  it('answers null outside the term, on an empty schedule and on an unparseable date', () => {
    expect(quoteEarlySettlement(schedule(), {
      settlementDate: '2023-12-31',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'THIRTY_360')).toBeNull()
    expect(quoteEarlySettlement(schedule(), {
      settlementDate: '2025-01-02',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'THIRTY_360')).toBeNull()
    expect(quoteEarlySettlement([], {
      settlementDate: '2024-06-16',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'THIRTY_360')).toBeNull()
    expect(quoteEarlySettlement(schedule(), {
      settlementDate: 'not-a-date',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'THIRTY_360')).toBeNull()
  })

  it('counts actual days on an ACT_360 schedule and divides by the same-basis days', () => {
    // ACT_360 月频：第 6 期实际 30 天（6-01 → 7-01），结清 6-16 → 实际 15 天。
    const quote = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-06-16',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'ACT_360')

    expect(quote!.accruedDays).toBe(15)
    expect(quote!.accruedInterest).toBe('1500')
  })

  it('uses 30/360 day math on a 31-day calendar month (the basis, not the calendar)', () => {
    // 7 月有 31 天，但 THIRTY_360 期内天数 = 30：结清 7-16 → 30/360 天数 15，
    // 分母仍是摊销表的 30 —— 分子分母同基，整期利息不受日历长度影响。
    const quote = quoteEarlySettlement(schedule(), {
      settlementDate: '2024-07-16',
      penaltyMode: 'AMOUNT',
      penaltyAmount: '0',
    }, 'THIRTY_360')

    expect(quote!.periodNo).toBe(7)
    expect(quote!.accruedDays).toBe(15)
    expect(quote!.accruedInterest).toBe('1300')
    expect(quote!.periodInterest).toBe('2600.00')
  })
})
