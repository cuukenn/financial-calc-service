/**
 * 编辑器行草稿类型（CalculationForm 与三个子编辑器共用）。
 * 与后端 RuleRowDto / FeeDto 的差异：草稿阶段允许留空，提交前由 CalculationForm 规整。
 */

/** 分段利率规则行草稿。 */
export interface RuleRowDraft {
  ruleId: string
  priority?: number
  fromPeriod?: number
  toPeriod?: number
  annualRate: string
}

/** 费用承担方（后端 FeeDto.owner 注册值）。 */
export type FeeOwnerDraft = 'LESSEE' | 'LESSOR'

/** 一笔费用草稿。 */
export interface FeeDraft {
  type: string
  amount: string
  chargeMode: string
  owner: FeeOwnerDraft
}
