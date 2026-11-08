import { readdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import {
  MIN_DOWN_PAYMENT_RATIO,
  downPaymentFloorBlocked,
  downPaymentRatio,
  formatDecimalPercent,
} from '@/utils/ratio'

import {
  BELOW_FLOOR_MARKER_KEY,
  RATIO_MARKER_KEYS,
  allDebtRatioStatus,
  collateralLeverageStatus,
  downPaymentFloorOutcome,
  dtiStatus,
  leverageReadoutEvaluable,
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
 *
 * 修复轮 1（评审 Important 1 / R133 同源要求）：状态不再自己重新推导一遍门槛，
 * 而是调用提交闸门用的同一个 `downPaymentFloorBlocked`。仪表是闸门徽章的另一面，
 * 两处各自推导迟早会分叉成「被阻断的提交配着合规徽章」——正是 §2.3 禁止的那类漂移。
 * 因此本组的输入是（贷款金额, 抵押物价值），与闸门完全一致。
 */
describe('collateralLeverageStatus (首付比例 ≥ 15%，含本数)', () => {
  const collateral = '2800000'

  it('15.00% 恰好等于下限 → ok（含本数）', () => {
    // 2 380 000 ÷ 2 800 000 = 0.85 → 首付 0.15，恰好等于下限。
    expect(collateralLeverageStatus('2380000', collateral)).toBe('ok')
  })

  it('14.99% → breach', () => {
    // 2 380 280 ÷ 2 800 000 = 0.8501 → 首付 0.1499。
    expect(collateralLeverageStatus('2380280', collateral)).toBe('breach')
  })

  it('判限用未舍入原值：显示 15.00% 而原值 0.1499999999 → breach（P-10）', () => {
    // 2 380 000.00028 ÷ 2 800 000 = 0.8500000001 → 首付 0.1499999999，展示 15.00%。
    const loanAmount = '2380000.00028'
    expect(formatDecimalPercent(downPaymentRatio(loanAmount, collateral))).toBe('15.00%')
    expect(collateralLeverageStatus(loanAmount, collateral)).toBe('breach')
  })

  it('抵押物价值缺失 / ≤ 0 / 为负 → neutral（不可计算不是 ok，也不是 0）', () => {
    expect(collateralLeverageStatus('2380000', null)).toBe('neutral')
    expect(collateralLeverageStatus('2380000', '0')).toBe('neutral')
    expect(collateralLeverageStatus('2380000', '-100')).toBe('neutral')
    // 贷款金额本身不可解析（规则之外的输入）同样不可计算。
    expect(collateralLeverageStatus('', collateral)).toBe('neutral')
    expect(collateralLeverageStatus('1,000', collateral)).toBe('neutral')
  })

  it('不可计算与越限是两件事：leverageReadoutEvaluable 只回答算不算得出', () => {
    expect(leverageReadoutEvaluable('2380000', collateral)).toBe(true)
    expect(leverageReadoutEvaluable('2380000', '0')).toBe(false)
    expect(leverageReadoutEvaluable('2380000', null)).toBe(false)
    // 算得出但低于下限 → 仍然是 breach，不是 neutral。
    expect(leverageReadoutEvaluable('2380280', collateral)).toBe(true)
  })
})

/**
 * 修复轮 1（评审 Important 1）：徽章与闸门必须同源。
 * 断言的是「闸门阻断 ⟹ 徽章绝不会是 ok」这条不变式（AGENTS.md §2.3 禁止的正是
 * 被阻断的提交配着合规徽章）。哪天闸门加了新守卫（产品/地区特定的下限、
 * 额外的前置条件），这一组会立刻红，而不是静默地只改一半。
 */
describe('首付门槛：仪表与闸门同源（修复轮 1，评审 Important 1）', () => {
  const SAMPLES: { name: string; loanAmount: string; collateral: string | null }[] = [
    { name: '恰好 15%（含本数）', loanAmount: '2380000', collateral: '2800000' },
    { name: '14.99% 低于下限', loanAmount: '2380280', collateral: '2800000' },
    { name: '原值 0.1499999999 但展示 15.00%', loanAmount: '2380000.00028', collateral: '2800000' },
    { name: '远高于下限', loanAmount: '1000000', collateral: '2800000' },
    { name: '贷款金额大于抵押物价值（首付为负）', loanAmount: '3000000', collateral: '2800000' },
    { name: '抵押物价值缺失', loanAmount: '2380000', collateral: null },
    { name: '抵押物价值 0', loanAmount: '2380000', collateral: '0' },
    { name: '抵押物价值为负', loanAmount: '2380000', collateral: '-100' },
    { name: '贷款金额不可解析', loanAmount: '', collateral: '2800000' },
  ]

  it.each(SAMPLES)('$name：徽章状态与闸门判定必须一致', ({ loanAmount, collateral }) => {
    const status = collateralLeverageStatus(loanAmount, collateral)
    const blocked = downPaymentFloorBlocked(loanAmount, collateral, MIN_DOWN_PAYMENT_RATIO)

    // 闸门阻断 → 徽章只能是 breach 或 neutral，绝不会是 ok（反之亦然）。
    expect(status === 'ok', `${status} vs blocked=${blocked} 不一致`).toBe(!blocked)
    expect(status === 'neutral' || status === 'breach').toBe(blocked)
  })

  it.each(SAMPLES)('$name：outcome 就是闸门 + 可计算性两件事', ({ loanAmount, collateral }) => {
    const outcome = downPaymentFloorOutcome(loanAmount, collateral)
    const evaluable = leverageReadoutEvaluable(loanAmount, collateral)
    const blocked = downPaymentFloorBlocked(loanAmount, collateral, MIN_DOWN_PAYMENT_RATIO)

    expect(outcome === 'unevaluable').toBe(!evaluable)
    if (evaluable) {
      expect(outcome === 'breach').toBe(blocked)
      expect(outcome === 'ok').toBe(!blocked)
    } else {
      // 算不出的门槛一律是阻断的（§2.3「阈值即硬约束」），outcome 与之保持一致。
      expect(blocked).toBe(true)
    }
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

/**
 * 修复轮 1（评审 Important 1）：15% 首付这道 [法] 门槛的判定只能有一处。
 *
 * 之前仪表自己重新推导了闸门的三个守卫（抵押物价值缺失 / 非正 / belowFloor），
 * 与 `ratio.ts` 的 `downPaymentFloorBlocked` 各写一份。今天两处可证等价，
 * 但哪天闸门改了（产品或地区特定的下限、多一道前置守卫）只会改中一处，
 * 于是出现「被阻断的提交配着合规徽章」——AGENTS.md §2.3 明令禁止的那类漂移。
 * 这条静态扫描守的就是「只剩一处判定」：仪表调闸门用的那个函数，表单调仪表与
 * 闸门共用的那个出口，谁都不再自己比一遍。
 */
describe('15% 首付门槛只判定一次（修复轮 1 的同源守卫）', () => {
  const COMPONENT_DIR = dirname(fileURLToPath(import.meta.url))
  const read = (file: string): string => readFileSync(resolve(COMPONENT_DIR, file), 'utf8')

  /** 组件目录下除 ratioStatus.ts 之外的所有实现文件（测试文件不算：它们正是对账的两端）。 */
  function implementationFiles(): string[] {
    return readdirSync(COMPONENT_DIR)
      .filter((name) => /\.(ts|vue)$/.test(name))
      .filter((name) => !name.endsWith('.spec.ts'))
      .filter((name) => name !== 'ratioStatus.ts')
  }

  const ratioStatusTs = read('ratioStatus.ts')
  const mortgageFormVue = read('MortgageForm.vue')

  it('ratioStatus.ts 自己不再推导门槛：判线一律走提交闸门用的那个函数，且只用一次', () => {
    // 自己比一遍的两条 primitive 都不许再出现（belowFloor / 直接 compareDecimal 判下限）。
    expect(count(ratioStatusTs, 'belowFloor('), '仪表不得自己判下限').toBe(0)
    expect(count(ratioStatusTs, 'compareDecimal('), '仪表不得自己比大小判门槛').toBe(0)
    expect(count(ratioStatusTs, 'downPaymentFloorBlocked('), '门槛判定必须恰好一次').toBe(1)
    // 下限常量在本模块内被引用（import + 闸门调用 + 文案出口），别处一处都没有。
    expect(count(ratioStatusTs, 'MIN_DOWN_PAYMENT_RATIO')).toBeGreaterThanOrEqual(2)
  })

  it('MortgageForm.vue 通过同一个出口判定，不再自己调门槛、比下限或命名下限', () => {
    expect(count(mortgageFormVue, 'downPaymentFloorBlocked('), '闸门必须走共用出口').toBe(0)
    expect(count(mortgageFormVue, 'belowFloor('), '表单不得自己判下限').toBe(0)
    expect(count(mortgageFormVue, 'downPaymentFloorOutcome('), '闸门与仪表共用同一个出口').toBeGreaterThanOrEqual(1)
    expect(count(mortgageFormVue, 'MIN_DOWN_PAYMENT_RATIO'), '下限的名字只在 ratioStatus.ts 出现').toBe(0)
    // 字面量 0.15 不得出现在表单侧：下限只有一个来源。
    expect(mortgageFormVue).not.toMatch(/['"]0\.15['"]/)
  })

  it('整个组件目录里只有 ratioStatus.ts 引用这道下限', () => {
    for (const name of implementationFiles()) {
      expect(read(name), `${name} 不得自己引用这道下限`).not.toContain('MIN_DOWN_PAYMENT_RATIO')
    }
  })
})

/** 出现次数（用于「只有一处」这类守卫）。 */
function count(source: string, needle: string): number {
  return source.split(needle).length - 1
}
