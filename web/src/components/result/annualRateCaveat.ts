/**
 * R143：稀疏现金流下按「期」年化的读数不可信。
 *
 * 引擎的 `IRR_ANNUAL` / `IRR_ANNUAL_SIMPLE` / `BORROWER_IRR_ANNUAL*` 按「期」折算成年
 * （(1+r)^每年期数−1）。而 `StandardCashFlowBuilder` 会丢弃金额为 0 的租金后再按列表
 * 下标重新编号，`IrrCalculator` 又按 `flow.sequence()` 折现 —— 到期一次还本付息这类
 * 稀疏现金流因此被当成「1 期」年化：12 期 3.5% 会读成 51%。
 *
 * 判定不按「事件数 < 期数」（先息后本 / 先本后息同样产生 0 金额租金，编号却是对的），
 * 而是按「年化读数与 `XIRR` / `TRUE_ANNUAL_RATE` 的差异超过容差」—— 定义上两者都是
 * 年化收益率，出现差异只可能是指数错了。此处只做标注，不改引擎口径（R135）。
 *
 * 十进制字符串运算，不引入浮点（P-01）。
 */
import { compareDecimal, decimalSubtract, isDecimalText } from '@/utils/ratio'

/** 同源读数允许的差异（0.5 个百分点）。超过即视为指数错误。 */
export const ANNUAL_RATE_DISAGREEMENT_TOLERANCE = '0.005'

/** 参照读数：同源的 XIRR 优先，退回真正的年化利率。 */
export const ANNUAL_RATE_REFERENCE_CODES = ['XIRR', 'TRUE_ANNUAL_RATE'] as const

/** 以「期」折算的收益率 code —— 稀疏现金流下它们不可信。 */
export const PERIOD_INDEXED_ANNUAL_CODES = [
  'IRR_ANNUAL',
  'IRR_ANNUAL_SIMPLE',
  'BORROWER_IRR_ANNUAL',
  'BORROWER_IRR_ANNUAL_SIMPLE',
] as const

/** 取第一个可解析的参照读数；都没有则返回 null（无从判断，不标注）。 */
function pickReference(metrics: Record<string, string | null>): string | null {
  for (const code of ANNUAL_RATE_REFERENCE_CODES) {
    const value = metrics[code]
    if (typeof value === 'string' && isDecimalText(value)) {
      return value
    }
  }
  return null
}

/** 绝对值（十进制字符串）—— ratio.ts 没有 abs，负数只需去掉前导减号。 */
function magnitude(value: string): string {
  return value.startsWith('-') ? value.slice(1) : value
}

/**
 * 返回需要标注「该读数不可信」的 annual code 集合。
 *
 * 无参照读数、或读数不可解析时返回空集合并跳过 —— 标注是附加信息，不能因数据缺失而抛错。
 */
export function annualRateCaveatCodes(metrics: Record<string, string | null>): Set<string> {
  const caveats = new Set<string>()
  const reference = pickReference(metrics)
  if (reference === null) {
    return caveats
  }

  for (const code of PERIOD_INDEXED_ANNUAL_CODES) {
    const value = metrics[code]
    if (typeof value !== 'string' || !isDecimalText(value)) {
      continue
    }
    const difference = decimalSubtract(value, reference)
    if (difference === null) {
      continue
    }
    if (compareDecimal(magnitude(difference), ANNUAL_RATE_DISAGREEMENT_TOLERANCE) > 0) {
      caveats.add(code)
    }
  }

  return caveats
}
