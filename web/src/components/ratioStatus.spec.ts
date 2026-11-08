import { describe, expect, it } from 'vitest'

import { formatDecimalPercent } from '@/utils/ratio'

import {
  BELOW_FLOOR_MARKER_KEY,
  RATIO_MARKER_KEYS,
  allDebtRatioStatus,
  collateralLeverageStatus,
  dtiStatus,
  statusClass,
} from './ratioStatus'

/**
 * Task 6 §3：阈值状态的判定（纯模块，脱 DOM 单测）。
 *
 * 硬约束（AGENTS.md P-10 + §1 补充口径 1）：状态只能从未舍入的十进制串上得出，
 * 不得从 2 位百分数的展示值反推。下面每组都刻意含「展示值看起来在阈值上、
 * 未舍入原值其实在阈值外」的样本，用来钉住这条禁止项。
 *
 * 「以上含本数 / 不足不含本数」（§2.3）：恰好等于阈值 → ok。
 */
describe('collateralLeverageStatus (首付比例 ≥ 15%，含本数)', () => {
  const collateral = '2800000'

  it('15.00% 恰好等于下限 → ok（含本数）', () => {
    expect(collateralLeverageStatus(collateral, '0.15')).toBe('ok')
  })

  it('14.99% → breach', () => {
    expect(collateralLeverageStatus(collateral, '0.1499')).toBe('breach')
  })

  it('判限用未舍入原值：显示 15.00% 而原值 0.1499999999 → breach（P-10）', () => {
    const value = '0.1499999999'
    expect(formatDecimalPercent(value)).toBe('15.00%')
    expect(collateralLeverageStatus(collateral, value)).toBe('breach')
  })

  it('抵押物价值缺失 / ≤ 0 / 为负 → neutral（不可计算不是 ok，也不是 0）', () => {
    expect(collateralLeverageStatus(null, '0.85')).toBe('neutral')
    expect(collateralLeverageStatus('0', null)).toBe('neutral')
    expect(collateralLeverageStatus('-100', '0.85')).toBe('neutral')
    expect(collateralLeverageStatus('2800000', null)).toBe('neutral')
  })
})

describe('dtiStatus (月房贷支出/收入比 ≤ 50% / > 60%)', () => {
  it('50.00% 恰好等于上限 → ok（不足不含本数，等于不算超限）', () => {
    expect(dtiStatus('0.5')).toBe('ok')
  })

  it('50.01% → warn（超过 50%，仍在 60% 之内）', () => {
    expect(dtiStatus('0.5001')).toBe('warn')
  })

  it('判限用未舍入原值：显示 50.00% 而原值 0.5000000001 → warn（P-10）', () => {
    const value = '0.5000000001'
    expect(formatDecimalPercent(value)).toBe('50.00%')
    expect(dtiStatus(value)).toBe('warn')
  })

  it('60.00% 仍是 warn，越过 60% 才是 breach', () => {
    expect(dtiStatus('0.6')).toBe('warn')
    expect(dtiStatus('0.6001')).toBe('breach')
  })

  it('未提供收入 / 未计算 → neutral', () => {
    expect(dtiStatus(null)).toBe('neutral')
  })
})

describe('allDebtRatioStatus (月所有债务支出/收入比 ≤ 60%)', () => {
  it('60.00% 恰好等于上限 → ok（含本数）', () => {
    expect(allDebtRatioStatus('0.6')).toBe('ok')
  })

  it('60.01% → breach（该比例只有 ok / breach 两档）', () => {
    expect(allDebtRatioStatus('0.6001')).toBe('breach')
  })

  it('判限用未舍入原值：显示 60.00% 而原值 0.6000000001 → breach（P-10）', () => {
    const value = '0.6000000001'
    expect(formatDecimalPercent(value)).toBe('60.00%')
    expect(allDebtRatioStatus(value)).toBe('breach')
  })

  it('未提供收入 / 未计算 → neutral', () => {
    expect(allDebtRatioStatus(null)).toBe('neutral')
  })
})

describe('状态 → 语义 CSS 类与文案标记', () => {
  it('类名固定为 ratio-ok / ratio-warn / ratio-breach / ratio-neutral（共用样式表里定义一次）', () => {
    expect(statusClass('ok')).toBe('ratio-ok')
    expect(statusClass('warn')).toBe('ratio-warn')
    expect(statusClass('breach')).toBe('ratio-breach')
    expect(statusClass('neutral')).toBe('ratio-neutral')
  })

  it('每个状态都有文案标记（合规 / 接近上限 / 超限 / 无法判断）：语义不只靠颜色', () => {
    expect(RATIO_MARKER_KEYS).toEqual({
      ok: 'mortgage.status.ok',
      warn: 'mortgage.status.warn',
      breach: 'mortgage.status.breach',
      neutral: 'mortgage.status.neutral',
    })
    // 下限类读数的 breach 方向相反：文案是「低于下限」，不是「超限」。
    expect(BELOW_FLOOR_MARKER_KEY).toBe('mortgage.status.belowFloor')
  })
})
