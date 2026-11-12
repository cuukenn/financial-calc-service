/**
 * 房贷重定价（纯模块：不依赖组件，可脱离 DOM 单测）。
 *
 * 它在表单与引擎之间只做一件事：把「重定价周期 + 每段 LPR」翻译成人人可以核对的
 * **分段利率规则行**（`ruleRows`）。引擎早就认识分段利率（`AbstractAmortizationGenerator`
 * 按段重解租金、`annualRate()` 逐期取规则利率），所以这里不新增契约、不新增字段口径
 * —— 重定价是既有能力的一次参数化（AGENTS.md §3.2「分段计息（利率调整…）」）。
 *
 * 口径（央行公告〔2019〕第 16 号 / 〔2024〕第 11 号，AGENTS.md §2.2、P-04）：
 * - 重定价周期只有 **3 / 6 / 12 个月**（房贷口径；其它值在表单就拒绝，不静默改成 12）；
 * - 段内利率不变，段起点换利率；每段利率 = **该段起点的 LPR + 同一加点**；
 * - LPR 序列必须由用户给出：表单**不得静默沿用首期 LPR**。缺段 LPR 又没勾选「沿用当前
 *   LPR」就阻断提交。勾选了才允许沿用，并且这是一条被显式标注的**假设**，不是合同事实。
 *
 * 期次边界与引擎的 `PeriodBuilder` 逐字对齐（月频、IN_ARREARS）：
 * 第 1 期起点 = 放款日；第 k 期起点 = 首期还款日 + (k − 2) 个月（保月末），
 * 即第 k 期的起点就是第 k − 1 期的到期日。分段只按**期**给引擎，日期只用来决定
 * 哪一期落在哪一段——日期本身不进入任何金额计算（P-01）。
 */

import type { RuleRow } from '@/api/types'
import { composeLprRate } from '@/utils/rate'
import { isNonNegativeDecimal } from '@/utils/ratio'

/** 不重定价：固定利率路径，payload 与本模块接入前逐位一致。 */
export const NO_REPRICING = 0

/** 央行公告〔2024〕第 11 号给出的三个周期；「0」是「不重定价」，不是「0 个月」。 */
export const REPRICING_CYCLE_MONTHS = [3, 6, 12] as const

/** 重定价周期取值：0（不重定价）或 3 / 6 / 12 个月。 */
export type RepricingCycle = 0 | 3 | 6 | 12

/** 周期取值闸门：非 0 / 3 / 6 / 12 一律拒绝（UI 与提交闸门共用同一个判定）。
 *
 * 这是**前端门槛**，与 480 月上限、≤ 12 期 BULLET 同属一类：引擎不认识「重定价周期」，
 * 它只看见分段利率规则行，所以引擎侧没有对应的非法形态可拦（2026-11-12 裁定，
 * 见 `.scratch/form-field-gaps/issues/02-mortgage-repricing.md`）。文案与 UI 都照此标注。 */
export function isRepricingCycle(value: unknown): value is RepricingCycle {
  return (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    (value === NO_REPRICING ||
      (REPRICING_CYCLE_MONTHS as readonly number[]).includes(value))
  )
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/
const MONTH_LENGTHS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
}

function daysInMonth(year: number, month: number): number {
  return month === 2 && isLeapYear(year) ? 29 : MONTH_LENGTHS[month - 1]
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/**
 * 月对日加月，保月末（31 日 + 1 月 = 2 月末），与引擎 `DateUtils.plusMonthsKeepEOM` 同
 * 口径。纯日历运算：年月日都是整数序号，不是金额、不是利率，P-01 管不到这里；
 * 真正的金额一律由引擎从这些边界推出的「期」上算出来。
 */
export function addMonthsKeepEom(iso: string, months: number): string {
  if (!ISO_DATE.test(iso) || !Number.isInteger(months)) {
    throw new Error(`addMonthsKeepEom 需要 yyyy-MM-dd 与整数月: ${iso} / ${months}`)
  }
  const [year, month, day] = iso.split('-').map(Number)
  const shifted = month - 1 + months
  const targetYear = year + Math.floor(shifted / 12)
  const targetMonth = ((shifted % 12) + 12) % 12 + 1
  const targetDay = Math.min(day, daysInMonth(targetYear, targetMonth))
  return `${pad(targetYear)}-${pad(targetMonth)}-${pad(targetDay)}`
}

/**
 * 第 k 期（1 起）的起点，与引擎 `PeriodBuilder.build` 对月频 IN_ARREARS 的构造一致：
 * k = 1 是放款日；k ≥ 2 是首期还款日 + (k − 2) 个月，也就是上一期的到期日。
 */
export function periodStartDate(
  commenceDate: string,
  firstRepaymentDate: string,
  periodNo: number,
): string {
  return periodNo <= 1
    ? commenceDate
    : addMonthsKeepEom(firstRepaymentDate, periodNo - 2)
}

/** 一段固定利率的覆盖范围：`[fromPeriod, toPeriod]` 是闭区间的期次。 */
export interface RepricingSegment {
  /** 段序号，0 起；与 `ruleRows` 的顺序、与 LPR 序列的下标一一对应。 */
  index: number
  /**
   * 该段利率的生效日。第 0 段是**放款日**——初始执行利率自放款日起生效，第一次重定价
   * 只是把它换掉；第 1 段起才是重定价日本身。
   */
  effectiveDate: string
  fromPeriod: number
  toPeriod: number
}

export interface RepricingSegmentInput {
  commenceDate: string
  firstRepaymentDate: string
  periods: number
  /** 重定价周期（月）：3 / 6 / 12。 */
  cycleMonths: number
  /** 重定价锚点（重定价日）：默认放款日。 */
  anchorDate: string
}

/**
 * 按重定价周期把 1..periods 切成段。
 *
 * 候选段界是 `anchor + j × cycle`（j = 0, 1, 2 …），但**只有晚于放款日的那些界才是这笔
 * 贷款的重定价事件**：界落在放款日当天或之前，是「放款时就在执行的利率」，不是一次重定价。
 * 第 k 期归入「界 ≤ 该期起点」的重定价事件个数那一段——起点正好压在重定价日上，就用新利率。
 * 第 0 段因此是放款到第一次重定价之间，执行放款利率，生效日是放款日；这不是「沿用首期 LPR」
 * 那种静默，而是合同事实上正在执行的利率。
 */
export function repricingSegments(input: RepricingSegmentInput): RepricingSegment[] {
  const { commenceDate, firstRepaymentDate, periods, cycleMonths, anchorDate } = input
  if (!isRepricingCycle(cycleMonths) || cycleMonths === NO_REPRICING) {
    throw new Error(`重定价周期必须是 3 / 6 / 12 个月: ${cycleMonths}`)
  }
  if (!ISO_DATE.test(commenceDate) || !ISO_DATE.test(firstRepaymentDate)
    || !ISO_DATE.test(anchorDate)) {
    throw new Error(`重定价需要 yyyy-MM-dd 日期: ${commenceDate} / ${firstRepaymentDate} / ${anchorDate}`)
  }
  if (!Number.isInteger(periods) || periods < 1) {
    throw new Error(`重定价分段需要正整数期数: ${periods}`)
  }

  const segments: RepricingSegment[] = []
  // 真正的重定价日序列（已剔除落在放款日当天或之前的界）。期起点单调递增，
  // 所以边界游标只需线性前进一次，段与段自然相接、不重叠。
  const repricingDates: string[] = []
  let cursor = 0
  let next = addMonthsKeepEom(anchorDate, 0)
  while (next <= commenceDate) {
    cursor++
    next = addMonthsKeepEom(anchorDate, cursor * cycleMonths)
  }
  for (let periodNo = 1; periodNo <= periods; periodNo++) {
    const start = periodStartDate(commenceDate, firstRepaymentDate, periodNo)
    while (next <= start) {
      repricingDates.push(next)
      cursor++
      next = addMonthsKeepEom(anchorDate, cursor * cycleMonths)
    }
    const index = repricingDates.length
    const last = segments[segments.length - 1]
    if (last && last.index === index) {
      last.toPeriod = periodNo
      continue
    }
    segments.push({
      index,
      effectiveDate: index === 0 ? commenceDate : repricingDates[index - 1],
      fromPeriod: periodNo,
      toPeriod: periodNo,
    })
  }
  return segments
}

/** 逗号（含中文逗号）分隔的 LPR 序列；空串表示「没有给出」。 */
export function parseLprSequence(raw: string): string[] {
  return raw
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter((item) => item !== '')
}

/** 序列里每一项都必须是可解析的非负百分数（LPR 可以为 0，不能是 '3,9' 这种字面量）。 */
export function isLprSequence(valid: string[]): boolean {
  return valid.every((item) => isNonNegativeDecimal(item))
}

export interface SegmentRateInput {
  segmentCount: number
  /** 当前 LPR（百分数串），第 0 段用它。 */
  currentLprPercent: string
  /** 加点（bp），全段共用同一加点——重定价只换 LPR，不换合同加点。 */
  spreadBp: string
  /** 第 1 段起的 LPR 序列（百分数串），由用户给出。 */
  futureLprPercents: string[]
  /** 显式勾选「缺段沿用当前 LPR」；未勾选时缺段返回 null，由表单阻断。 */
  reuseCurrentLpr: boolean
}

export interface SegmentRatePlan {
  /** 与段同序的年化小数字符串（P-02）；`null` 表示该段 LPR 缺失且未获准沿用。 */
  rates: (string | null)[]
  /** 按「沿用当前 LPR」假设填上的段序号——结果区要把它作为假设披露，不得伪装成合同值。 */
  assumedSegments: number[]
  /** 缺 LPR 且未获准沿用的段序号（非空即不得提交）。 */
  missingSegments: number[]
}

/**
 * 每段利率 = 该段 LPR + 同一加点（P-04：加点是合同条款，重定价只重定 LPR）。
 * 任何一段解析不出合法利率都会以 `null` 显式返回，绝不静默取默认值（AGENTS.md §3.1）。
 */
export function planSegmentRates(input: SegmentRateInput): SegmentRatePlan {
  const { segmentCount, currentLprPercent, spreadBp, futureLprPercents, reuseCurrentLpr } = input
  const currentRate = composeLprRate(currentLprPercent, spreadBp)
  const rates: (string | null)[] = []
  const assumedSegments: number[] = []
  const missingSegments: number[] = []
  for (let index = 0; index < segmentCount; index++) {
    if (index === 0) {
      rates.push(currentRate)
      continue
    }
    const lpr = futureLprPercents[index - 1]
    if (lpr !== undefined && lpr !== '') {
      rates.push(composeLprRate(lpr, spreadBp))
      continue
    }
    if (reuseCurrentLpr && currentRate !== null) {
      rates.push(currentRate)
      assumedSegments.push(index)
      continue
    }
    rates.push(null)
    missingSegments.push(index)
  }
  return { rates, assumedSegments, missingSegments }
}

/**
 * 段 → 引擎规则行，形态与租赁 Tab 的 `CalculationForm.mapRuleRows` 一致（同一套缺省列、
 * 同一 priority 间距），所以后端 `RuleSetValidator` 的「完整覆盖 1..N」检查同样成立。
 * 本金计划 `INHERIT`：重定价换的是利率，不动本金计划（§2.2 的还款方式另有字段表达）。
 */
export function buildRateRuleRows(segments: RepricingSegment[], rates: string[]): RuleRow[] {
  return segments.map((segment, index) => {
    const rate = rates[index]
    if (rate === undefined || rate === null) {
      throw new Error(`第 ${segment.index + 1} 段缺少利率，规则行不得带空利率`);
    }
    return {
      ruleId: `RPRICING-${segment.index + 1}`,
      priority: (index + 1) * 10,
      fromPeriod: segment.fromPeriod,
      toPeriod: segment.toPeriod,
      annualRate: rate,
      principalPlan: 'INHERIT',
      interestTreatment: 'PAY_AS_INCURRED',
      paymentTiming: 'IN_ARREARS',
      specialAction: 'NONE',
      memo: `重定价段 ${segment.index + 1}：${segment.effectiveDate} 起，第 ${segment.fromPeriod}–${segment.toPeriod} 期`,
    }
  })
}

/**
 * 表单派生读数：下一次重定价日。第 1 段存在就是它；只有一段（期限短于一个周期）
 * 时答案是 null —— 这笔贷款在到期前不会重定价，不能编一个日期出来。
 */
export function nextRepricingDate(segments: RepricingSegment[]): string | null {
  return segments.length > 1 ? segments[1].effectiveDate : null
}

/**
 * 段覆盖检查（提交闸门的最后一道）：段必须从第 1 期起、逐段相接、最后一段收到第
 * periods 期。引擎侧 `RuleSetValidator` 也会拒绝缺口，但让它在网络往返之前失败更有价值。
 */
export function segmentsCoverPeriods(segments: RepricingSegment[], periods: number): boolean {
  if (segments.length === 0 || segments[0].fromPeriod !== 1) return false
  let expected = 1
  for (const segment of segments) {
    if (segment.fromPeriod !== expected || segment.toPeriod < segment.fromPeriod) return false
    expected = segment.toPeriod + 1
  }
  return expected === periods + 1
}
