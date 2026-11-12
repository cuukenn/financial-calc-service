/**
 * 印花税（AGENTS.md §4 / 《印花税法》融资租赁合同独立税目，2022-07-01 施行）。
 *
 * 计税依据 = Σ 租金（不含单列增值税）= `TOTAL_RENT − TOTAL_TAX`；税率 **0.005%**
 * （万分之零点五）。它是**合同税费**：不进摊销表、不进 IRR 现金流、不与 Σ租金相加
 * 构成新的「总成本」口径 —— 印花税是租金总额派生出来的读数，不是又一笔现金流。
 *
 * 0.005% = 1/20000，所以这里用**除法**而不是乘法：`utils/ratio.ts` 只提供十进制
 * 精确加/减/除，不引入浮点（P-01）。税额按 P-05 输出 2 位（分），HALF_UP；
 * 计税依据保留后端原值，展示时才格式化。
 */
import { decimalDivide, decimalSubtract, isDecimalText } from '@/utils/ratio'

/** 税率：0.005%（万分之零点五），十进制小数形式。 */
export const STAMP_DUTY_RATE = '0.00005'

/** 与 {@link STAMP_DUTY_RATE} 同一个数的除法形态：0.005% = 1/20000。 */
export const STAMP_DUTY_DIVISOR = '20000'

/** 展示用税率文案（与 STAMP_DUTY_RATE 同源，不得另写一份参与运算）。 */
export const STAMP_DUTY_RATE_TEXT = '0.005%'

export interface StampDuty {
  /** 计税依据：Σ租金 − 单列增值税（原值，未格式化）。 */
  base: string
  /** 应纳税额：计税依据 × 0.005%，2 位小数（P-05）。 */
  amount: string
}

/**
 * 从后端指标推出印花税。
 *
 * 租金总额缺失或不可解析 → null（不可计算，界面显示「—」并说明原因，不发明一个 0）。
 * `TOTAL_TAX` 缺失按 0 处理：v1 引擎不做价税分离（`FCS-CALC-3005`），税列为空正是
 * 「租金不含单列增值税」的形态，此时计税依据就是 Σ 租金本身。
 */
export function stampDuty(metrics: Record<string, string | null>): StampDuty | null {
  const totalRent = metrics['TOTAL_RENT']
  if (typeof totalRent !== 'string' || !isDecimalText(totalRent)) {
    return null
  }
  const totalTax = metrics['TOTAL_TAX']
  const tax = typeof totalTax === 'string' && isDecimalText(totalTax) ? totalTax : '0'

  const base = decimalSubtract(totalRent, tax)
  if (base === null) {
    return null
  }
  const amount = decimalDivide(base, STAMP_DUTY_DIVISOR, 2)
  if (amount === null) {
    return null
  }
  return { base, amount }
}
