/**
 * 监管仪表的阈值状态（Task 6 §3，纯模块：不依赖组件，可脱离 DOM 单测）。
 *
 * 输入一律是十进制小数字符串（P-01），判定只走 `utils/ratio` 的精确比较器
 * （compareDecimal / exceedsLimit / belowFloor / isPositiveDecimal），
 * 并且一律用**未舍入原值**（AGENTS.md P-10、§1 补充口径 1）：
 * 禁止从 2 位百分数的展示值或 Number() 解析值反推状态 —— 0.1499999999 显示成
 * 15.00%，状态仍然是 breach。
 *
 * 阈值语义（AGENTS.md §2.3「以上含本数 / 不足不含本数」）：
 * - 首付比例 ≥ 15%：恰好 15% → ok，14.99% → breach；
 * - 月房贷支出/收入比：≤ 50% → ok，> 50% → warn，> 60% → breach；
 * - 月所有债务支出/收入比：≤ 60% → ok，> 60% → breach（该比例只有两档）；
 * - 未提供收入 / 未计算 / 抵押物价值 ≤ 0 → neutral：不可计算既不判 ok 也不判 0。
 *
 * 状态只描述状态：颜色与文案标记由样式表与 i18n 呈现（两者都是测试断言的对象），
 * 本模块不碰 DOM、不碰颜色。
 */

import {
  ALL_DEBT_DTI_LIMIT,
  MIN_DOWN_PAYMENT_RATIO,
  MORTGAGE_DTI_LIMIT,
  belowFloor,
  exceedsLimit,
  isPositiveDecimal,
} from '@/utils/ratio'

export type RatioStatus = 'ok' | 'warn' | 'breach' | 'neutral'

/** 语义类名：在共用样式表 `styles/workbench.css` 里定义一次，组件不再自带颜色。 */
export function statusClass(status: RatioStatus): string {
  return `ratio-${status}`
}

/**
 * 每个状态都配一个文案标记，语义不只靠颜色传达。
 * 下限类读数（首付比例）的 breach 方向相反：文案是「低于下限」而不是「超限」。
 */
export const RATIO_MARKER_KEYS: Record<RatioStatus, string> = {
  ok: 'mortgage.status.ok',
  warn: 'mortgage.status.warn',
  breach: 'mortgage.status.breach',
  neutral: 'mortgage.status.neutral',
}

export const BELOW_FLOOR_MARKER_KEY = 'mortgage.status.belowFloor'

/** breach 时该读数用哪条文案：下限类「低于下限」，上限类「超限」。 */
export function markerKey(status: RatioStatus, floorThreshold = false): string {
  return status === 'breach' && floorThreshold
    ? BELOW_FLOOR_MARKER_KEY
    : RATIO_MARKER_KEYS[status]
}

/**
 * 抵押物价值 / 贷款成数（LTV）/ 首付比例这一组的共同状态。
 *
 * 判定与提交闸门同源（`downPaymentFloorBlocked` 用的是同一个 belowFloor），
 * 所以「仪表说 breach」与「提交被阻断」永远不会打架。
 * 抵押物价值缺失、≤ 0 或为负时不可计算 → neutral（§2.3 阈值即硬约束、§9.5 姿态）。
 */
export function collateralLeverageStatus(
  collateral: string | null,
  downPayment: string | null,
): RatioStatus {
  if (collateral === null || !isPositiveDecimal(collateral) || downPayment === null) {
    return 'neutral'
  }
  return belowFloor(downPayment, MIN_DOWN_PAYMENT_RATIO) ? 'breach' : 'ok'
}

/**
 * 月房贷支出/收入比（DTI）的三档：≤ 50% ok；越过 50% 是 warn（已不符合 §2.2 的
 * 房贷比率上限，但仍在 60% 这条硬顶之内）；越过 60% 才是 breach。
 */
export function dtiStatus(dti: string | null): RatioStatus {
  if (dti === null) return 'neutral'
  if (exceedsLimit(dti, ALL_DEBT_DTI_LIMIT)) return 'breach'
  if (exceedsLimit(dti, MORTGAGE_DTI_LIMIT)) return 'warn'
  return 'ok'
}

/** 月所有债务支出/收入比：上限只有 60% 这一条，所以只有 ok / breach 两档。 */
export function allDebtRatioStatus(ratio: string | null): RatioStatus {
  if (ratio === null) return 'neutral'
  return exceedsLimit(ratio, ALL_DEBT_DTI_LIMIT) ? 'breach' : 'ok'
}
