/**
 * 提前结清试算（AGENTS.md §2.5；form-field-gaps tickets 07 租赁 / 08 房贷，纯函数、B 类结果派生）。
 *
 * 口径（全部量由摊销表与结清日推出，不新增输入要素、不二次建模）：
 *
 * - **剩余本金** = 结清日所在期的期初余额（摊销表自带，不是租金总额打折 —— §2.5 硬约束）。
 * - **实际计息天数** = 该期起始日 → 结清日，**算头不算尾**（含起始日、不含结清日），按摊销表
 *   自己的计息基准：30/360 族（THIRTY_360 / THIRTY_E_360）用 30/360 差值，ACT 族用实际天数 ——
 *   分母用摊销表该期的 `days` 字段（同一基准），分子分母同基才不发明汇率。
 * - **应计利息** = 剩余本金 × 该期期利率（`appliedRate`，引擎已按 `年利率 × yearFraction` 算好）
 *   × 天数 ÷ 该期天数，算至分（HALF_UP）。整期时（d = 该期天数）逐位等于该期
 *   `interestPaid`（同一乘法、同一舍入点）；30/360 与 ACT_360 月频下与 「日利率 = 年利率 ÷
 *   360」严格等价；ACT/365 等约定基准按「按约定执行」条款继承摊销表口径。
 * - **提前还款违约金**：按剩余本金的比例（RATE）或固定金额（AMOUNT），合同约定优先。
 * - **罚息**（房贷）：剩余本金 × 罚息年利率 × 逾期天数 ÷ 360；逾期天数 0 或利率 0 即不计罚息。
 * - **结清金额** = 剩余本金 + 应计利息 + 违约金 + 罚息（各明细先算至分、再求和）。
 * - **节省利息** = 结清日及之后各期 `interestPaid` 合计 − 应计利息（不打折、不近似）。
 *
 * 结清日落在某期中间时，应计利息与摊销表中该期利息可对账：d ≤ 期天数 ⇒ 应计 ≤ 该期利息，
 * 且整期时逐位一致（差额 0，远小于验收的 0.01 元容差）。
 */
import type { Installment } from '@/api/types'
import {
  compareDecimal,
  decimalAdd,
  decimalDivide,
  decimalMultiply,
  decimalSubtract,
  isDecimalText,
} from '@/utils/ratio'

/** 结清请求：结清日 + 违约金（率/金额二选一）+ 房贷罚息（可选，缺省不计）。 */
export interface SettlementRequest {
  /** 结清日 ISO yyyy-MM-dd；须落在租期内（首期起始日 ~ 末期期末日）。 */
  settlementDate: string
  /** 违约金模式：RATE 按剩余本金比例，AMOUNT 固定金额。 */
  penaltyMode: 'RATE' | 'AMOUNT'
  /** RATE 模式的年比例，小数字符串（0.01 = 1%）。 */
  penaltyRate?: string
  /** AMOUNT 模式的金额字符串。 */
  penaltyAmount?: string
  /** 罚息逾期天数（≥ 0）；0 = 无逾期，不计罚息。 */
  overdueDays?: number
  /** 罚息年利率，小数字符串；缺省/0 = 不计罚息。 */
  penaltyInterestRate?: string
}

/** 结清报价：§2.5 的四项分解 + 对账与节省利息读数；全部金额为字符串小数（分精度）。 */
export interface SettlementQuote {
  /** 承载结清日的期次号（1 起）。 */
  periodNo: number
  /** 剩余本金 = 该期期初余额。 */
  remainingPrincipal: string
  /** 实际计息天数（算头不算尾，按摊销表计息基准）。 */
  accruedDays: number
  /** 应计利息（算至分）。 */
  accruedInterest: string
  /** 该期整期利息（对账用，摊销表原值）。 */
  periodInterest: string
  /** 提前还款违约金（算至分）。 */
  penalty: string
  /** 罚息（算至分；未提供逾期天数/利率时为 '0'）。 */
  overduePenalty: string
  /** 结清金额 = 剩余本金 + 应计利息 + 违约金 + 罚息。 */
  settlementAmount: string
  /** 结清日及之后的剩余期数。 */
  remainingPeriods: number
  /** 节省利息 = 剩余各期利息合计 − 应计利息。 */
  savedInterest: string
}

/** 30/360 族计息基准的 code（d 按三十/三百六十差值计）。 */
const THIRTY_360_CODES = new Set(['THIRTY_360', 'THIRTY_E_360'])

function parseIsoDate(iso: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) return null
  const ms = Date.parse(`${iso}T00:00:00Z`)
  return Number.isNaN(ms) ? null : ms
}

/** 实际天数（UTC 日期差，整数日，无浮点误差 —— 差值是 86400000 的整数倍）。 */
function actualDays(fromIso: string, toIso: string): number {
  const from = parseIsoDate(fromIso)
  const to = parseIsoDate(toIso)
  if (from === null || to === null) return 0
  return Math.round((to - from) / 86_400_000)
}

/** 30/360 差值天数（含起始日、不含结束日的同款算头不算尾口径）。
 *
 * 钳位规则与引擎 {@code Thirty360}（30/360 Bond Basis）逐条一致：起始日 31 → 30；
 * 结束日 31 仅在起始日已是 30 时 → 30。不钳位的话，月末边缘（如结清日 31 日）上
 * 分子分母不再同基——「可手工复算」的验收会差出 1–2 天利息（代码自审发现 1）。
 */
function thirty360Days(fromIso: string, toIso: string): number {
  const [, y1, m1, d1] = /^(\d{4})-(\d{2})-(\d{2})$/.exec(fromIso) ?? []
  const [, y2, m2, d2] = /^(\d{4})-(\d{2})-(\d{2})$/.exec(toIso) ?? []
  if (y1 === undefined || y2 === undefined) return 0
  let startDay = Number(d1)
  let endDay = Number(d2)
  if (startDay === 31) startDay = 30
  if (endDay === 31 && startDay === 30) endDay = 30
  return (
    (Number(y2) - Number(y1)) * 360
    + (Number(m2) - Number(m1)) * 30
    + (endDay - startDay)
  )
}

/** 该期起 → 结清日的计息天数，按摊销表自己的基准（分子分母同基）。 */
function accruedDaysBetween(dayCountCode: string, fromIso: string, toIso: string): number {
  return THIRTY_360_CODES.has(dayCountCode)
    ? thirty360Days(fromIso, toIso)
    : actualDays(fromIso, toIso)
}

/** 金额字符串 → 保留原样（不可解析返回 null，调用方按缺省处理，不猜 0）。 */
function money(value: string | undefined): string | null {
  if (value === undefined || !isDecimalText(value)) return null
  return value
}

/**
 * 结清报价。返回 null = 不可计算（摊销表为空、结清日不在租期内、日期不可解析），
 * 界面显示「无法计算 + 原因」，不发明一个 0。
 */
export function quoteEarlySettlement(
  installments: Installment[],
  request: SettlementRequest,
  dayCountCode: string,
): SettlementQuote | null {
  if (installments.length === 0) return null
  const settledMs = parseIsoDate(request.settlementDate)
  if (settledMs === null) return null
  const firstStart = parseIsoDate(installments[0]!.startDate)
  const lastEnd = parseIsoDate(installments[installments.length - 1]!.endDate)
  if (firstStart === null || lastEnd === null || settledMs < firstStart || settledMs > lastEnd) {
    return null
  }

  // 承载期：startDate ≤ 结清日 的最后一期；结清日恰逢某期期初（= 上期期末）时归属**上一期**
  // —— 算头不算尾，上期利息借到该日为止整期计入，且该期本金已到偿还日。只有结清日 = 首期
  // 期初（一笔利息都还没借）才落在首期、计零天。
  let holder: Installment | null = null
  for (const installment of installments) {
    const startMs = parseIsoDate(installment.startDate)
    if (startMs !== null && startMs <= settledMs) holder = installment
  }
  if (holder !== null
      && holder.periodNo > 1
      && parseIsoDate(holder.startDate) === settledMs) {
    holder = installments[holder.periodNo - 2]!
  }
  if (holder === null) return null

  const remainingPrincipal = holder.openingBalance
  const accruedDays = accruedDaysBetween(dayCountCode, holder.startDate, request.settlementDate)
  if (accruedDays < 0 || holder.days <= 0) return null

  // 应计利息 = 剩余本金 × 期利率 × 天数 ÷ 该期天数，算至分（HALF_UP）。
  const proportional = decimalMultiply(remainingPrincipal, holder.appliedRate)
  const accruedInterest =
    proportional === null
      ? null
      : decimalDivide(decimalMultiply(proportional, String(accruedDays)) ?? '', String(holder.days), 2)
  if (accruedInterest === null || accruedInterest === '') return null

  // 违约金：RATE = 剩余本金 × 比例；AMOUNT = 固定金额。两种模式互斥，缺省 0。
  let penalty = '0'
  if (request.penaltyMode === 'RATE') {
    const rate = money(request.penaltyRate)
    if (rate !== null && compareDecimal(rate, '0') > 0) {
      penalty = decimalDivide(decimalMultiply(remainingPrincipal, rate) ?? '', '1', 2) ?? '0'
    }
  } else {
    const amount = money(request.penaltyAmount)
    if (amount !== null) {
      penalty = decimalDivide(amount, '1', 2) ?? '0'
    }
  }

  // 罚息 = 剩余本金 × 罚息利率 × 逾期天数 ÷ 360；逾期天数 0 或利率 0 即不计。
  let overduePenalty = '0'
  const overdueDays = request.overdueDays ?? 0
  const penaltyRate = money(request.penaltyInterestRate)
  if (overdueDays > 0 && penaltyRate !== null && compareDecimal(penaltyRate, '0') > 0) {
    overduePenalty =
      decimalDivide(
        decimalMultiply(decimalMultiply(remainingPrincipal, penaltyRate) ?? '', String(overdueDays))
          ?? '',
        '360',
        2,
      ) ?? '0'
  }

  // 结清金额 = 各明细（均已是分精度）之和（先明细求和再舍入 —— 明细已舍入到分，
  // 合计即分精度精确和）。
  const settlementAmount =
    decimalAdd(
      decimalAdd(decimalAdd(remainingPrincipal, accruedInterest) ?? '', penalty) ?? '',
      overduePenalty,
    ) ?? null
  if (settlementAmount === null) return null

  // 剩余期数与节省利息：结清日及之后各期利息合计 − 应计利息。
  let remainingInterest = '0'
  let remainingPeriods = 0
  for (const installment of installments) {
    if (installment.periodNo >= holder.periodNo && isDecimalText(installment.interestPaid)) {
      remainingInterest = decimalAdd(remainingInterest, installment.interestPaid) ?? remainingInterest
      remainingPeriods += 1
    }
  }
  // 两者都是分精度，差值即分精度精确差。
  const savedInterest = decimalSubtract(remainingInterest, accruedInterest) ?? '0'

  return {
    periodNo: holder.periodNo,
    remainingPrincipal,
    accruedDays,
    accruedInterest,
    periodInterest: holder.interestPaid,
    penalty,
    overduePenalty,
    settlementAmount,
    remainingPeriods,
    savedInterest,
  }
}
