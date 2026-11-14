import { formatDecimalPercent, isDecimalText } from './ratio'

/**
 * R90a/R90b 数值显示格式化（纯函数，供指标卡与各表格共用，禁止散落 toFixed）。
 *
 * 只改显示、不改数据：原始后端字符串永远不丢（原值随 hover tooltip 透出，
 * 对账不丢精度）。非数值/null 的兜底约定与既有占位一致（'—' / 原样透出）。
 *
 * 百分数格式化按关注点分工，仓库里每类百分数只有一个格式化器（Task 6 §5）：
 * 利率类指标（IRR/XIRR/MIRR/_true 年利率_）对外 4 位百分数用下面的 formatPercent；
 * 百分数展示值的 2 位口径是 `utils/ratio.ts` 的
 * `formatDecimalPercent` —— 那一个才是 P-10 安全的（判限在未舍入原值上做，展示才舍入），
 * 所以它留在 ratio.ts 与阈值同源，不得搬到这里、也不得另复制一份。
 */

/** R93-2：金额格式贴合现实记账精度——千分位 + 固定 2 位小数（#,##0.00，分），
 * locale 固定 en-US 保证千分位为 ','。百分比/一般数值仍为 4 位小数不变。 */
const MONEY_FORMAT = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

function toNumber(value: string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

/** 金额：千分位 + 固定 2 位小数（1032797.1500 → 1,032,797.15）；原值随 tooltip 透出。 */
export function formatMoney(value: string | null | undefined): string {
  const n = toNumber(value)
  if (n === null) return value === null || value === undefined || value === '' ? '—' : value
  return MONEY_FORMAT.format(n)
}

/**
 * 百分比：×100 后固定 4 位小数 + '%'（0.061677800455 → 6.1678%）。
 *
 * U7（2026-11-11 scan）：实现换成 `utils/ratio.ts` 的十进制字符串长算
 * （`formatDecimalPercent`，BigInt 定点、HALF_UP）——浮点 `×100 + toFixed` 会让
 * `0.0049999991` 这类边界值在显示层再丢一次精度（P-01 的精神同样约束展示层）。
 * 兜底约定不变：null/空 → '—'，非数值原样透出（调用方的占位契约不动）。
 */
export function formatPercent(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  if (!isDecimalText(value)) return value
  return formatDecimalPercent(value, 4)
}

/** 一般数值：固定 4 位小数，无千分位（0.26841 → 0.2684；久期/回收期/凸性/租费率等）。 */
export function formatNumber(value: string | null | undefined): string {
  const n = toNumber(value)
  if (n === null) return value === null || value === undefined || value === '' ? '—' : value
  return n.toFixed(4)
}
