/**
 * 监管仪表的阈值状态（Task 6 §3，纯模块：不依赖组件，可脱离 DOM 单测）。
 *
 * 输入一律是十进制小数字符串（P-01），判定只走 `utils/ratio` 的精确比较器
 * （exceedsLimit / downPaymentFloorBlocked / isPositiveDecimal），
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
 *
 * 修复轮 1（评审 Important 1）：首付 15% 这道 [法] 门槛在本模块只判定一次，且判定用的
 * 就是提交闸门用的那个函数（`downPaymentFloorBlocked`，下限常量也只在这里引一次）。
 * 徽章是闸门的视觉对应物：两处各推导一遍，早晚会分叉成「被阻断的提交配着合规徽章」，
 * 那正是 AGENTS.md §2.3 禁止的漂移。表单调 `downPaymentFloorOutcome`，仪表调
 * `collateralLeverageStatus`，两者背后是同一次判定。
 */

import {
  ALL_DEBT_DTI_LIMIT,
  MIN_DOWN_PAYMENT_RATIO,
  MORTGAGE_DTI_LIMIT,
  downPaymentFloorBlocked,
  downPaymentRatio,
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

/** 首付门槛的三种结局：算得出且达线 / 算得出但低于下限 / 算不出（无法判定）。 */
export type DownPaymentFloorOutcome = 'ok' | 'breach' | 'unevaluable'

/**
 * 这道下限本身（表单文案要说「首付 ≥ 15%」时用它的 2 位百分数）。
 * 下限只有一个来源：`ratio.ts` 的 `MIN_DOWN_PAYMENT_RATIO`，而组件里只有本模块引用它。
 */
export function downPaymentFloorRatio(): string {
  return MIN_DOWN_PAYMENT_RATIO
}

/**
 * 抵押物价值 / 贷款成数（LTV）/ 首付比例这一组读数算不算得出。
 *
 * 只回答「算不算得出」这一件事，不判线：抵押物价值缺失、≤ 0 或为负，
 * 或首付比例这条式子本身算不出（分母为 0、输入不可解析）→ 不可计算。
 */
export function leverageReadoutEvaluable(loanAmount: string, collateral: string | null): boolean {
  if (collateral === null || !isPositiveDecimal(collateral)) return false
  return downPaymentRatio(loanAmount, collateral) !== null
}

/**
 * 首付硬下限的判定出口（AGENTS.md §2.3：首付比例 ≥ 15%，「以上」含本数）。
 *
 * 闸门与仪表共用这一个出口：可计算性单独问，判线一律交给提交闸门用的
 * `downPaymentFloorBlocked`（下限 `MIN_DOWN_PAYMENT_RATIO` 在本模块只在这里引一次）。
 * 算不出的门槛同样是阻断的（§2.3「阈值即硬约束」、§9.5 不可计算姿态），
 * 所以 `unevaluable` 与闸门的 true 永远一致。
 */
export function downPaymentFloorOutcome(
  loanAmount: string,
  collateral: string | null,
): DownPaymentFloorOutcome {
  if (!leverageReadoutEvaluable(loanAmount, collateral)) return 'unevaluable'
  return downPaymentFloorBlocked(loanAmount, collateral, MIN_DOWN_PAYMENT_RATIO) ? 'breach' : 'ok'
}

/**
 * 抵押物价值 / 贷款成数（LTV）/ 首付比例这组读数的仪表状态。
 *
 * 与 `downPaymentFloorOutcome` 同源，所以「仪表说 breach」与「提交被阻断」
 * 永远不会打架；不可计算落 neutral —— 既不判 ok 也不显示成 0。
 */
export function collateralLeverageStatus(loanAmount: string, collateral: string | null): RatioStatus {
  return leverageStatusOf(downPaymentFloorOutcome(loanAmount, collateral))
}

/** 结局 → 状态：unevaluable 只能落 neutral，不会落成 ok（模块内部映射）。 */
function leverageStatusOf(outcome: DownPaymentFloorOutcome): RatioStatus {
  if (outcome === 'unevaluable') return 'neutral'
  return outcome
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
