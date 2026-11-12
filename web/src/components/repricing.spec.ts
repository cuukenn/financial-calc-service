import { describe, expect, it } from 'vitest'

import {
  NO_REPRICING,
  REPRICING_CYCLE_MONTHS,
  addMonthsKeepEom,
  buildRateRuleRows,
  isLprSequence,
  isRepricingCycle,
  nextRepricingDate,
  parseLprSequence,
  periodStartDate,
  planSegmentRates,
  repricingSegments,
  segmentsCoverPeriods,
} from './repricing'

describe('重定价周期取值', () => {
  it('只接受 0（不重定价）与 3 / 6 / 12 个月', () => {
    expect(NO_REPRICING).toBe(0)
    expect([...REPRICING_CYCLE_MONTHS]).toEqual([3, 6, 12])
    for (const cycle of [0, 3, 6, 12]) {
      expect(isRepricingCycle(cycle)).toBe(true)
    }
  })

  it('其它周期直接拒绝，不静默改成 12 个月（央行公告〔2024〕第 11 号只给三个周期）', () => {
    for (const cycle of [1, 2, 4, 9, 24, 3.5, '6', null, undefined, NaN]) {
      expect(isRepricingCycle(cycle)).toBe(false)
    }
  })
})

describe('月对日加月（与引擎 plusMonthsKeepEOM 同口径）', () => {
  it('保月末：31 日加一个月落到当月最后一天', () => {
    expect(addMonthsKeepEom('2024-01-31', 1)).toBe('2024-02-29') // 闰年
    expect(addMonthsKeepEom('2023-01-31', 1)).toBe('2023-02-28')
    expect(addMonthsKeepEom('2024-11-30', 2)).toBe('2025-01-30')
    expect(addMonthsKeepEom('2024-01-30', 1)).toBe('2024-02-29') // 30 日也钳到 2 月末
  })

  it('整年加月回到同月同日；加 0 个月不变', () => {
    expect(addMonthsKeepEom('2026-01-31', 12)).toBe('2027-01-31')
    expect(addMonthsKeepEom('2026-11-01', 0)).toBe('2026-11-01')
  })

  it('非 ISO 日期或非整数月直接抛，不猜一个日期', () => {
    expect(() => addMonthsKeepEom('2026-1-1', 1)).toThrow()
    expect(() => addMonthsKeepEom('2026-11-01', 1.5)).toThrow()
  })
})

describe('期次起点（对齐引擎 PeriodBuilder：月频 IN_ARREARS）', () => {
  const commence = '2026-11-01'
  const first = '2026-12-01'

  it('第 1 期是放款日，第 k 期是首期还款日 + (k − 2) 个月，即上一期的到期日', () => {
    expect(periodStartDate(commence, first, 1)).toBe('2026-11-01')
    expect(periodStartDate(commence, first, 2)).toBe('2026-12-01')
    expect(periodStartDate(commence, first, 3)).toBe('2027-01-01')
    expect(periodStartDate(commence, first, 13)).toBe('2027-11-01')
  })

  it('首期还款日在月末时，后续期次也停在月末（保月末一路传下去）', () => {
    expect(periodStartDate('2026-01-15', '2026-01-31', 2)).toBe('2026-01-31')
    expect(periodStartDate('2026-01-15', '2026-01-31', 3)).toBe('2026-02-28')
    expect(periodStartDate('2024-01-15', '2024-01-31', 3)).toBe('2024-02-29')
  })
})

describe('按周期分段', () => {
  const base = {
    commenceDate: '2026-11-01',
    firstRepaymentDate: '2026-12-01',
    periods: 12,
    cycleMonths: 6,
    anchorDate: '2026-11-01',
  }

  it('6 个月周期：第 1–6 期用放款利率，第 7 期起进入第一次重定价', () => {
    const segments = repricingSegments(base)
    expect(segments).toHaveLength(2)
    expect(segments[0]).toEqual({
      index: 0,
      effectiveDate: '2026-11-01',
      fromPeriod: 1,
      toPeriod: 6,
    })
    expect(segments[1]).toEqual({
      index: 1,
      effectiveDate: '2027-05-01',
      fromPeriod: 7,
      toPeriod: 12,
    })
    expect(segmentsCoverPeriods(segments, 12)).toBe(true)
  })

  it('段界落在期起点上时归新段（重定价日照常生效，不留一段重叠）', () => {
    const segments = repricingSegments({ ...base, periods: 24, cycleMonths: 12 })
    expect(segments.map((s) => [s.fromPeriod, s.toPeriod])).toEqual([
      [1, 12],
      [13, 24],
    ])
    expect(segments[1].effectiveDate).toBe('2027-11-01') // 第 13 期起点 = 2026-12-01 + 11 月
    expect(segmentsCoverPeriods(segments, 24)).toBe(true)
  })

  it('3 个月周期把 12 期切成 4 段，且逐段相接', () => {
    const segments = repricingSegments({ ...base, cycleMonths: 3 })
    expect(segments.map((s) => [s.fromPeriod, s.toPeriod])).toEqual([
      [1, 3],
      [4, 6],
      [7, 9],
      [10, 12],
    ])
    expect(segments[2].effectiveDate).toBe('2027-05-01')
    expect(nextRepricingDate(segments)).toBe('2027-02-01')
  })

  it('期限短于一个周期时只有一段：到期前不会重定价，不编造日期', () => {
    const segments = repricingSegments({ ...base, periods: 2 })
    expect(segments).toHaveLength(1)
    expect(nextRepricingDate(segments)).toBeNull()
  })

  it('重定价日晚于放款日：放款到第一次重定价之间归第 0 段，生效日是放款日', () => {
    const segments = repricingSegments({ ...base, periods: 6, cycleMonths: 3, anchorDate: '2027-01-01' })
    expect(segments.map((s) => [s.index, s.fromPeriod, s.toPeriod])).toEqual([
      [0, 1, 2],
      [1, 3, 5],
      [2, 6, 6],
    ])
    expect(segments[0].effectiveDate).toBe('2026-11-01')
    expect(nextRepricingDate(segments)).toBe('2027-01-01')
    expect(segmentsCoverPeriods(segments, 6)).toBe(true)
  })

  it('首期还款日落在月末：保月末一路传下来，段不重叠不留缝（即使段界不落在期起点上）', () => {
    const segments = repricingSegments({
      commenceDate: '2026-01-15',
      firstRepaymentDate: '2026-01-31',
      periods: 12,
      cycleMonths: 6,
      anchorDate: '2026-01-15',
    })
    // 期起点：01-15, 01-31, 02-28, 03-31, 04-30, 05-31, 06-30 | 07-31, 08-31, 09-30, 10-31, 11-30
    // 段界：01-15 | 07-15 | 2027-01-15 —— 第 8 期起点才越过第一个段界。
    expect(segments.map((s) => [s.fromPeriod, s.toPeriod])).toEqual([
      [1, 7],
      [8, 12],
    ])
    expect(segments[1].effectiveDate).toBe('2026-07-15')
    expect(segmentsCoverPeriods(segments, 12)).toBe(true)
  })

  it('非法周期 / 非法日期 / 非正期数直接抛，不静默降级', () => {
    expect(() => repricingSegments({ ...base, cycleMonths: 4 })).toThrow()
    expect(() => repricingSegments({ ...base, cycleMonths: NO_REPRICING })).toThrow()
    expect(() => repricingSegments({ ...base, commenceDate: '2026/11/01' })).toThrow()
    expect(() => repricingSegments({ ...base, periods: 0 })).toThrow()
  })
})

describe('段覆盖检查', () => {
  it('缺口或重叠都拒绝（引擎 RuleSetValidator 也会拒，但要在提交前失败）', () => {
    expect(segmentsCoverPeriods([{ index: 0, effectiveDate: '2026-11-01', fromPeriod: 2, toPeriod: 12 }], 12))
      .toBe(false)
    expect(segmentsCoverPeriods([
      { index: 0, effectiveDate: '2026-11-01', fromPeriod: 1, toPeriod: 6 },
      { index: 1, effectiveDate: '2027-05-01', fromPeriod: 6, toPeriod: 12 },
    ], 12)).toBe(false)
    expect(segmentsCoverPeriods([], 12)).toBe(false)
    expect(segmentsCoverPeriods([{ index: 0, effectiveDate: '2026-11-01', fromPeriod: 1, toPeriod: 11 }], 12))
      .toBe(false)
  })
})

describe('每段利率（P-04：段利率 = 该段 LPR + 同一加点）', () => {
  const segments = repricingSegments({
    commenceDate: '2026-11-01',
    firstRepaymentDate: '2026-12-01',
    periods: 24,
    cycleMonths: 6,
    anchorDate: '2026-11-01',
  })

  it('第 0 段用当前 LPR，其后各段用给定序列，加点全段不变', () => {
    expect(segments.map((s) => [s.index, s.fromPeriod, s.toPeriod])).toEqual([
      [0, 1, 6],
      [1, 7, 12],
      [2, 13, 18],
      [3, 19, 24],
    ])
    const plan = planSegmentRates({
      segmentCount: segments.length,
      currentLprPercent: '3.5',
      spreadBp: '35',
      futureLprPercents: ['3.9', '3.1', '3.0'],
      reuseCurrentLpr: false,
    })
    // 3.5% + 35bp = 0.0385；3.9% + 35bp = 0.0425；3.1% + 35bp = 0.0345；3.0% + 35bp = 0.0335
    expect(plan.rates).toEqual(['0.0385', '0.0425', '0.0345', '0.0335'])
    expect(plan.missingSegments).toEqual([])
    expect(plan.assumedSegments).toEqual([])
  })

  it('缺段 LPR 且未勾选沿用 → missingSegments 非空，不得提交（AGENTS.md §3.1 不静默取默认）', () => {
    const plan = planSegmentRates({
      segmentCount: 3,
      currentLprPercent: '3.5',
      spreadBp: '0',
      futureLprPercents: ['3.9'],
      reuseCurrentLpr: false,
    })
    expect(plan.rates).toEqual(['0.035', '0.039', null])
    expect(plan.missingSegments).toEqual([2])
  })

  it('勾选沿用 → 填上当前利率，但该段被记为「假设」，要显式披露', () => {
    const plan = planSegmentRates({
      segmentCount: 3,
      currentLprPercent: '3.5',
      spreadBp: '0',
      futureLprPercents: [],
      reuseCurrentLpr: true,
    })
    expect(plan.rates).toEqual(['0.035', '0.035', '0.035'])
    expect(plan.missingSegments).toEqual([])
    expect(plan.assumedSegments).toEqual([1, 2])
  })

  it('当前 LPR 本身不可解析时不编造沿用值', () => {
    const plan = planSegmentRates({
      segmentCount: 2,
      currentLprPercent: '3,5',
      spreadBp: '0',
      futureLprPercents: [],
      reuseCurrentLpr: true,
    })
    expect(plan.rates[1]).toBeNull()
    expect(plan.missingSegments).toEqual([1])
    expect(plan.assumedSegments).toEqual([])
  })
})

describe('LPR 序列输入', () => {
  it('接受中英文逗号分隔，忽略空白项', () => {
    expect(parseLprSequence(' 3.9 , 3.1，3.0 ')).toEqual(['3.9', '3.1', '3.0'])
    expect(parseLprSequence('')).toEqual([])
  })

  it('每项必须是非负十进制百分数', () => {
    expect(isLprSequence(['3.9', '0', '3.05'])).toBe(true)
    expect(isLprSequence(['3.9', '-0.1'])).toBe(false)
    expect(isLprSequence(['3.9', 'LPR'])).toBe(false)
  })
})

describe('段 → 引擎规则行（复用既有分段能力，不新增契约）', () => {
  const segments = repricingSegments({
    commenceDate: '2026-11-01',
    firstRepaymentDate: '2026-12-01',
    periods: 12,
    cycleMonths: 6,
    anchorDate: '2026-11-01',
  })

  it('每段一行：闭区间相接、完整覆盖 1..periods、缺省列与租赁 Tab 的规则行一致', () => {
    const rows = buildRateRuleRows(segments, ['0.0385', '0.0425'])
    expect(rows).toHaveLength(2)
    expect(rows.map((row) => [row.ruleId, row.priority, row.fromPeriod, row.toPeriod, row.annualRate]))
      .toEqual([
        ['RPRICING-1', 10, 1, 6, '0.0385'],
        ['RPRICING-2', 20, 7, 12, '0.0425'],
      ])
    for (const row of rows) {
      expect(row.principalPlan).toBe('INHERIT')
      expect(row.interestTreatment).toBe('PAY_AS_INCURRED')
      expect(row.paymentTiming).toBe('IN_ARREARS')
      expect(row.specialAction).toBe('NONE')
      expect(row.memo).toContain('期')
    }
  })

  it('利率缺失时抛错：规则行不得带空利率', () => {
    expect(() => buildRateRuleRows(segments, ['0.0385'])).toThrow()
  })
})
