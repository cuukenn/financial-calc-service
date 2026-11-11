import { describe, expect, it } from 'vitest'

import {
  ANNUAL_RATE_DISAGREEMENT_TOLERANCE,
  annualRateCaveatCodes,
} from './annualRateCaveat'

/**
 * R143：稀疏现金流下按「期」年化的读数必须被标注为不可信。
 * 判定依据是「与 XIRR / TRUE_ANNUAL_RATE 的差异」，不是「事件数 < 期数」。
 */
describe('annualRateCaveatCodes (R143)', () => {
  it('flags the period-indexed readings of a sparse 12-期 bullet run (measured live)', () => {
    // 走查实测：到期一次还本付息 12 期，年利率 3.5%
    const caveats = annualRateCaveatCodes({
      IRR_ANNUAL: '0.511068951676',
      IRR_ANNUAL_SIMPLE: '0.498',
      XIRR: '0.0350000168',
      TRUE_ANNUAL_RATE: '0.0350000168',
    })

    expect([...caveats].sort()).toEqual(['IRR_ANNUAL', 'IRR_ANNUAL_SIMPLE'])
  })

  it('stays quiet when a dense schedule agrees with XIRR', () => {
    // 走查实测：360 期等额本息，IRR 年化 6.1678% vs XIRR 6.1639%（差 0.4bp）
    const caveats = annualRateCaveatCodes({
      IRR_ANNUAL: '0.061678',
      IRR_ANNUAL_SIMPLE: '0.061678',
      XIRR: '0.061639',
      TRUE_ANNUAL_RATE: '0.061639',
    })

    expect(caveats.size).toBe(0)
  })

  it('flags the borrower-side annual readings too (they come from the same root)', () => {
    const caveats = annualRateCaveatCodes({
      BORROWER_IRR_ANNUAL: '0.511068951676',
      BORROWER_IRR_ANNUAL_SIMPLE: '0.498',
      XIRR: '0.0350000168',
    })

    expect([...caveats].sort()).toEqual([
      'BORROWER_IRR_ANNUAL',
      'BORROWER_IRR_ANNUAL_SIMPLE',
    ])
  })

  it('treats the tolerance as a strict bound — equality is not a caveat', () => {
    // 0.5 个百分点恰好等于容差：不标注；再多 1e-4 就标注（未舍入原值比较，P-10）
    const atBoundary = annualRateCaveatCodes({
      IRR_ANNUAL: '0.055',
      XIRR: '0.05',
    })
    const pastBoundary = annualRateCaveatCodes({
      IRR_ANNUAL: '0.0551',
      XIRR: '0.05',
    })

    expect(ANNUAL_RATE_DISAGREEMENT_TOLERANCE).toBe('0.005')
    expect(atBoundary.size).toBe(0)
    expect([...pastBoundary]).toEqual(['IRR_ANNUAL'])
  })

  it('falls back to TRUE_ANNUAL_RATE when XIRR is absent', () => {
    const caveats = annualRateCaveatCodes({
      IRR_ANNUAL: '0.511068951676',
      XIRR: null,
      TRUE_ANNUAL_RATE: '0.0350000168',
    })

    expect([...caveats]).toEqual(['IRR_ANNUAL'])
  })

  it('annotates nothing when there is no reference reading at all', () => {
    expect(annualRateCaveatCodes({ IRR_ANNUAL: '0.511068951676' }).size).toBe(0)
    expect(annualRateCaveatCodes({}).size).toBe(0)
  })

  it('skips unparseable or absent values instead of throwing', () => {
    const caveats = annualRateCaveatCodes({
      IRR_ANNUAL: 'not-a-number',
      IRR_ANNUAL_SIMPLE: null,
      XIRR: '0.035',
    })

    expect(caveats.size).toBe(0)
  })
})
