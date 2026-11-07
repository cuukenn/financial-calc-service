/**
 * R90a/R90b 数值显示格式化（纯函数，供指标卡与各表格共用，禁止散落 toFixed）。
 *
 * 只改显示、不改数据：原始后端字符串永远不丢（原值随 hover tooltip 透出，
 * 对账不丢精度）。非数值/null 的兜底约定与既有占位一致（'—' / 原样透出）。
 */

/** 金额格式：千分位 + 固定 4 位小数（#,##0.0000），locale 固定 en-US 保证千分位为 ','。 */
const MONEY_FORMAT = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 4,
  maximumFractionDigits: 4,
})

function toNumber(value: string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

/** 金额：千分位 + 固定 4 位小数（1032797.15 → 1,032,797.1500）。 */
export function formatMoney(value: string | null | undefined): string {
  const n = toNumber(value)
  if (n === null) return value === null || value === undefined || value === '' ? '—' : value
  return MONEY_FORMAT.format(n)
}

/** 百分比：×100 后固定 4 位小数 + '%'（0.061677800455 → 6.1678%）。 */
export function formatPercent(value: string | null | undefined): string {
  const n = toNumber(value)
  if (n === null) return value === null || value === undefined || value === '' ? '—' : value
  return `${(n * 100).toFixed(4)}%`
}

/** 一般数值：固定 4 位小数，无千分位（0.26841 → 0.2684；久期/回收期/凸性/租费率等）。 */
export function formatNumber(value: string | null | undefined): string {
  const n = toNumber(value)
  if (n === null) return value === null || value === undefined || value === '' ? '—' : value
  return n.toFixed(4)
}
