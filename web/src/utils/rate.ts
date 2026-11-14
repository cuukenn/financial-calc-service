/**
 * R92-5/R92-6 利率输入换算（纯函数，表单/装配层专用，禁止散落手写除法）。
 *
 * 后端契约不变：JSON 中 money/rate 一律为十进制小数字符串（如 "0.06"）。
 * 本模块只负责「用户输入口径 → 小数字符串」的换算：
 * - 裸数字按百分数理解：6 → 0.06（R92-5/R92-6 百分数口径）；
 * - 带后缀 %：6% → 0.06；
 * - 带后缀 bp（基点，1bp = 0.01%）：600bp → 0.06（大小写不敏感）；
 * - 非法输入返回 null（由调用方决定校验/透传行为）。
 *
 * 精度约定：不做浮点乘除，直接对十进制字符串移位小数点，输入多少位就保留多少位。
 *
 * Task 5 追加（R101）：`composeLprRate`（LPR + 加点 → 年化小数字符串）与
 * `isLprStepAligned`（5bp 报价步长）。加法本身借用 utils/ratio 的精确十进制加法，
 * 本模块仍是纯函数，仍然不出现 Number()/parseFloat/toFixed。
 */

import { decimalAdd } from './ratio'

const NUMERIC_RE = /^[+-]?\d+(\.\d+)?$/

/** 十进制字符串除以 10^places（小数点左移 places 位），保精度。 */
function shiftDecimalLeft(numStr: string, places: number): string {
  const negative = numStr.startsWith('-')
  const digits = numStr.replace(/^[+-]/, '')
  const [intPart, fracPart = ''] = digits.split('.')
  const all = intPart + fracPart
  const pointAt = intPart.length - places

  let result: string
  if (pointAt > 0) {
    result = `${all.slice(0, pointAt)}.${all.slice(pointAt)}`
  } else {
    result = `0.${'0'.repeat(-pointAt)}${all}`
  }

  let [outInt, outFrac = ''] = result.split('.')
  // 去多余前导零（保留个位 0）与尾随零；整数无小数部分时不留点。
  outInt = outInt.replace(/^0+(?=\d)/, '')
  outFrac = outFrac.replace(/0+$/, '')
  const out = outFrac ? `${outInt}.${outFrac}` : outInt
  if (negative && out !== '0') return `-${out}`
  return out
}

/**
 * R101：LPR + 加点 → 年化小数字符串（提交给引擎的 nominalRate）。
 *
 * - lprPercent 为百分数口径（'3.5' = 3.5%），按十进制移位 ÷100；
 * - spreadBp 为基点（1bp = 0.01 个百分点），按十进制移位 ÷10000，可为负；
 * - 只用十进制移位与精确加法（ratio.decimalAdd），不经过浮点。
 * - 非法输入，或加分后为负（房贷年利率不可能为负）→ null，由表单阻断提交。
 */
export function composeLprRate(lprPercent: string, spreadBp: string): string | null {
  const lpr = lprPercent.trim()
  const spread = spreadBp.trim()
  if (!NUMERIC_RE.test(lpr) || !NUMERIC_RE.test(spread)) return null

  const base = shiftDecimalLeft(lpr, 2)
  const spreadDecimal = shiftDecimalLeft(spread, 4)
  const composed = decimalAdd(base, spreadDecimal)
  if (composed === null || composed.startsWith('-')) return null
  return composed
}

/**
 * LPR 报价步长 0.05 个百分点 = 5bp。
 *
 * 本项目没有 LPR 数据源（用户自填 LPR 与加点），因此把步长约束加在「加点」输入上：
 * 只接受 5bp 的整数倍（含 0 与负加点），非法即拒绝，绝不静默就近取整。
 */
export function isLprStepAligned(spreadBp: string): boolean {
  const text = spreadBp.trim()
  if (!/^[+-]?\d+$/.test(text)) return false
  return Number.parseInt(text, 10) % 5 === 0
}

/**
 * 解析利率输入为十进制小数字符串：
 * '6' → '0.06'，'6%' → '0.06'，'600bp' → '0.06'，'0' → '0'；
 * 空串或非法输入 → null。
 */
export function parseRateInput(raw: string): string | null {
  const input = raw.trim().toLowerCase()
  if (input === '') return null

  let numPart = input
  let places = 2
  if (input.endsWith('bp')) {
    numPart = input.slice(0, -2).trim()
    places = 4
  } else if (input.endsWith('%')) {
    numPart = input.slice(0, -1).trim()
    places = 2
  }
  if (!NUMERIC_RE.test(numPart)) return null

  return shiftDecimalLeft(numPart, places)
}
